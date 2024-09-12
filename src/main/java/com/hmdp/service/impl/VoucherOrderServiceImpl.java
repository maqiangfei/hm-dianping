package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 秒杀Lua脚本（判断库存，一人一单）
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    }

    /**
     * 阻塞队列，存储订单任务
     */
    private final BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024 * 1024);

    /**
     * 线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 该对象的代理对象，需要在自己线程中获取
     */
    private IVoucherOrderService proxy;

    /**
     * 类初始化时开启处理订单的线程
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 处理订单线程
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取队列中的订单
                    VoucherOrder voucherOrder = orderTask.take();
                    // 处理订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                }
            }
        }
    }

    /**
     * 处理订单方法
     * @param voucherOrder 订单对象
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户id
        Long userId = voucherOrder.getUserId();
        // 获取Redisson锁对象
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 重复下单
            log.error("重复下单");
            return;
        }
        try {
            // 事务方法必须通过代理对象调用才生效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    /**
     * 秒杀优惠券
     * @param voucherId 优惠券的id
     * @return 结果
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 数据库查询秒杀券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            // 秒杀券不存在
            return Result.fail("秒杀券不存在");
        }
        if (LocalDateTime.now().isBefore(voucher.getBeginTime())) {
            // 秒杀尚未开始
            return Result.fail("秒杀尚未开始");
        }
        if (LocalDateTime.now().isAfter(voucher.getEndTime())) {
            // 秒杀结束
            return Result.fail("秒杀已经结束");
        }
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 执行Lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                userId.toString(),
                voucherId.toString());
        assert result != null;
        int r = result.intValue();
        if (r != 0) {
            // 不为0，购买失败
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 购买成功，生成订单id
        long orderId = redisIdWorker.nextId("order");
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        // 将订单加入阻塞队列
        orderTask.add(voucherOrder);
        // 获取该线程的代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);
    }

    /**
     * 创建订单，需要保证扣减库存和保存订单的原子性
     * @param voucherOrder 订单对象
     */
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 实现一人一单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 查询数据库中该用户的订单
        Long count = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, userId)
                .count();
        if (count > 0) {
            // 该用户已经下过单
            log.error("不允许重复下单");
            return;
        }
        // 扣件库存
        boolean flag = seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)  // 乐观锁
                .update();
        if (!flag) {
            // 扣减失败
            log.error("扣减库存失败");
            return;
        }
        // 保存订单
        save(voucherOrder);
    }
}
