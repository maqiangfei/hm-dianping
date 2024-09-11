package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOCK_KEY;

public class SimpleRedisLock {

    private final StringRedisTemplate stringRedisTemplate;
    private final String LOCK;

    /**
     * 锁构造器
     * @param lock 业务名+标识
     * @param stringRedisTemplate redis api
     */
    public SimpleRedisLock(String lock, StringRedisTemplate stringRedisTemplate) {
        this.LOCK = LOCK_KEY + lock;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 加锁
     * @param timeoutSec 锁超时释放时间
     * @return 是否加锁成功
     */
    public boolean tryLock(long timeoutSec) {
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK, "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁
     */
    public void unlock() {
        stringRedisTemplate.delete(LOCK);
    }
}
