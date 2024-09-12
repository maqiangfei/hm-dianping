package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        // Redis中的消息队列名
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAM stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        // 获取失败，没有消息，继续下次循环
                        continue;
                    }
                    // 解析订单消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    VoucherOrder voucherOrder = parse2VoucherOrder(record);
                    // 处理订单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    handlePendingList();
                }
            }
        }

        /**
         * 处理pending-list中为确认的订单信息
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAM stream.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        // 获取失败，pending-list中没有消息，结束循环
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    // 解析订单消息
                    VoucherOrder voucherOrder = parse2VoucherOrder(record);
                    // 处理订单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

        /**
         * 解析订单信息，封装为订单对象
         * @param record 订单信息
         * @return 订单对象
         */
        private VoucherOrder parse2VoucherOrder(MapRecord<String, Object, Object> record) {
            Map<Object, Object> values = record.getValue();
            return BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
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
        // 生成订单id
        long orderId = redisIdWorker.nextId("order");
        // 执行Lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                userId.toString(),
                voucherId.toString(),
                String.valueOf(orderId));
        assert result != null;
        int r = result.intValue();
        if (r != 0) {
            // 不为0，购买失败
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
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
