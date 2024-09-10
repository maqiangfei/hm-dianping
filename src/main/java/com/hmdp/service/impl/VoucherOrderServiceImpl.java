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
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足");
        }
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 集群/分布式下，多个JVM则失效
        synchronized (userId.toString().intern()) {
            // 事务方法必须通过代理对象调用才生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    /**
     * 创建订单，保证扣减库存和保存订单的原子性
     * @param voucherId 优惠券id
     * @return 结果对象
     */
    @Transactional
    @Override
    public Result createVoucherOrder(Long voucherId) {
        // 实现一人一单
        Long userId = UserHolder.getUser().getId();
        // @Transactional事务在方法执行完毕才submit，此时锁已经释放，其它线程可能在submit前查询用户是否下过单
        // synchronized (userId.toString().intern()) {
            // 查询数据库中该用户的订单
            Long count = lambdaQuery()
                    .eq(VoucherOrder::getVoucherId, voucherId)
                    .eq(VoucherOrder::getUserId, userId)
                    .count();
            if (count > 0) {
                // 该用户已经下过单
                return Result.fail("不允许重复下单");
            }
            // 扣件库存
            boolean flag = seckillVoucherService.lambdaUpdate()
                    .setSql("stock = stock - 1")
                    .eq(SeckillVoucher::getVoucherId, voucherId)
                    .gt(SeckillVoucher::getStock, 0)  // 乐观锁
                    .update();
            if (!flag) {
                // 扣减失败
                return Result.fail("库存不足");
            }
            // 生成订单id
            long orderId = redisIdWorker.nextId("order");
            // 创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(UserHolder.getUser().getId());
            voucherOrder.setVoucherId(voucherId);
            // 保存订单
            save(voucherOrder);
            // 返回订单id
            return Result.ok(orderId);
        // }
    }
}
