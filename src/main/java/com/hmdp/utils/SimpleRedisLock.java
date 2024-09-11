package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOCK_KEY;

public class SimpleRedisLock {

    private final StringRedisTemplate stringRedisTemplate;
    /**
     * 锁名，Redis的key
     */
    private final String LOCK;
    /**
     * 锁标识前缀
     */
    private static final String ID_PREFIX = UUID.randomUUID(true) + "-";
    /**
     * Lua脚本
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setResultType(Long.class);
        // 设置脚本位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    }

    /**
     * 锁构造器
     * @param lock 业务名+标识
     * @param stringRedisTemplate redis api
     */
    public SimpleRedisLock(String lock, StringRedisTemplate stringRedisTemplate) {
        LOCK = LOCK_KEY + lock;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 加锁
     * @param timeoutSec 锁超时释放时间
     * @return 是否加锁成功
     */
    public boolean tryLock(long timeoutSec) {
        String id = ID_PREFIX + Thread.currentThread().getId();
        // 设置锁，指定锁id
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK, id, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁，lua脚本中判断锁后释放锁，保证原子性
     * 防止判断成功后阻塞，ttl后锁超时释放，第2线程进来，此时阻塞结束误将第2线程的锁释放，然后第3线程进来与第2线程并发执行
     */
    public void unlock() {
        // 执行lua脚本，判断锁并释放
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(LOCK),
                ID_PREFIX + Thread.currentThread().getId());
    }

    // 释放锁，不能保证判断锁和释放锁的原子性
    /*public void unlock() {
        // 获取当前锁id
        String lockId = stringRedisTemplate.opsForValue().get(LOCK);
        if (lockId != null && lockId.equals(ID_PREFIX + Thread.currentThread().getId())) {
            // 当前锁是自己的
            stringRedisTemplate.delete(LOCK);
        }
    }*/
}
