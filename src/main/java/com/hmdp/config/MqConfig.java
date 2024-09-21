package com.hmdp.config;

import com.hmdp.utils.MqConstants;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MqConfig {

    /**
     * 发送和解析队列消息使用Json方式
     * @return 消息转换器
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 消息消费异常，重试次数耗尽任然异常，则由messageRecoverer将失败消息投递到异常队列
     * @param rabbitTemplate rabbitmq api
     * @return 消息恢复实现
     */
    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, MqConstants.ERROR_DIRECT, MqConstants.ERROR_KEY);
    }

}
