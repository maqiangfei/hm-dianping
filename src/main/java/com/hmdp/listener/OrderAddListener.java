package com.hmdp.listener;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.MqConstants;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Component
public class OrderAddListener {

    @Resource
    public ISeckillVoucherService seckillVoucherService;

    @Resource
    public IVoucherOrderService voucherOrderService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(MqConstants.ORDER_ADD_QUEUE),
            exchange = @Exchange(MqConstants.ORDER_TOPIC),
            key = MqConstants.ORDER_ADD_KEY
    ))
    @Transactional
    public void orderAddListener(VoucherOrder voucherOrder) {
        // 扣库存
        boolean isSuccess = seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                .gt(SeckillVoucher::getStock, 0)  // 乐观锁
                .update();
        if (!isSuccess) {
            throw new RuntimeException("库存扣减失败");
        }
        // 保存订单信息
        voucherOrderService.save(voucherOrder);
    }

}
