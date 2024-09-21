package com.hmdp.listener;

import com.hmdp.utils.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ErrorMessageListener {

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(MqConstants.ERROR_QUEUE),
            exchange = @Exchange(MqConstants.ERROR_DIRECT),
            key = MqConstants.ERROR_KEY
    ))
    public void errorMessageListener(Exception e) {
        log.error(e.getMessage(), e);
    }
}
