package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 设置Redis地址和密码
        config.useSingleServer().setAddress("redis://localhost:6379").setPassword("redis");
        return Redisson.create(config);
    }
}
