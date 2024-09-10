package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 添加缓存到Redis
     * @param key 缓存标识
     * @param value 缓存对象
     * @param expire 过期时间
     * @param timeUnit 时间单位
     */
    public void set(String key, Object value, Long expire, TimeUnit timeUnit) {
        String jsonStr = JSONUtil.toJsonStr(value);
        // 存入Redis
        stringRedisTemplate.opsForValue().set(key, jsonStr, expire, timeUnit);
    }

    /**
     * 添加逻辑过期缓存到Redis
     * @param key 缓存标识
     * @param value 缓存对象
     * @param expire 过期时间
     * @param timeUnit 时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long expire, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // 设置过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expire)));
        // 存入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 通用根据id查询方法 -> 解决：缓存穿透
     * @param keyPrefix 缓存标识前缀（业务名）
     * @param id 查询id
     * @param clazz 结果类型
     * @param doFallBack 数据库查询逻辑
     * @param expire 过期时间
     * @param timeUnit 时间单位
     * @return 查询结果
     * @param <R> 结果类型
     * @param <ID> id类型
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> clazz,
                                          Function<ID, R> doFallBack, Long expire, TimeUnit timeUnit) {
        String cacheKey = CACHE_KEY + keyPrefix + ":" + id;
        // 查询Redis缓存
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(json)) {
            // 命中有效缓存
            return JSONUtil.toBean(json, clazz);
        }
        if (json != null) {
            // 命中空对象，数据库中没有
            return null;
        }
        // 查询数据库
        R r = doFallBack.apply(id);
        if (r == null) {
            // 缓存空对象
            this.set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 缓存到Redis
        this.set(cacheKey, r, expire, timeUnit);
        // 返回
        return r;
    }

    /**
     * 通用根据id查找方法 -> 解决缓存击穿（逻辑过期）
     * @param keyPrefix 缓存标识前缀（业务名）
     * @param id 查询id
     * @param clazz 结果类型
     * @param doFallBack 数据库查询逻辑
     * @param expire 过期时间
     * @param timeUnit 时间单位
     * @return 查询结果
     * @param <R> 结果类型
     * @param <ID> id类型
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> clazz,
                                    Function<ID, R> doFallBack, Long expire, TimeUnit timeUnit) {
        String cacheKey = CACHE_KEY + keyPrefix + id;
        // 从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(json)) {  //Blank -> ""  "\n\t"  null
            // 有可用的缓存
            return JSONUtil.toBean(json, clazz);  // 存在直接返回
        }
        if (json != null) {
            // 命中的为空值，数据库中没有
            return null;
        }
        R r = null;
        String lockKey = LOCK_KEY + keyPrefix + id;
        try {
            // 获取互斥锁
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, clazz, doFallBack, expire, timeUnit);
            }
            // 成功，根据id查询数据库
            r = doFallBack.apply(id);
            // 模拟重建的延时
            // Thread.sleep(200);
            if (r == null) {
                // 不存在，将空值写入redis，解决缓存穿透问题
                this.set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存在，写入redis
            this.set(cacheKey, r, expire, timeUnit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockKey);
        }
        // 返回
        return r;
    }

    /**
     * 通用根据id查询方法 -> 解决：缓存击穿（逻辑过期时间）
     * @param keyPrefix 缓存标识前缀（业务名）
     * @param id 查询id
     * @param clazz 结果类型
     * @param doFallBack 数据库查询逻辑
     * @param expire 过期时间
     * @param timeUnit 时间单位
     * @return 查询结果
     * @param <R> 结果类型
     * @param <ID> id类型
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> clazz,
                                            Function<ID, R> doFallBack, Long expire, TimeUnit timeUnit) {
        String cacheKey = CACHE_KEY + keyPrefix + ":" + id;
        // 查询Redis缓存
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isBlank(json)) {
            // 缓存未命中
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);
        // 获取逻辑过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        if (LocalDateTime.now().isBefore(expireTime)) {
            // 没有过期，直接返回
            return r;
        }
        String lockKey = LOCK_KEY + keyPrefix + ":" + id;
        // 加锁
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 成功获取锁，开启新线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 数据库查询
                    R r1 = doFallBack.apply(id);
                    // 模拟复杂业务
                    // Thread.sleep(200);
                    // 重建缓存
                    this.setWithLogicalExpire(cacheKey, r1, expire, timeUnit);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 返回旧数据
        return r;
    }

    /**
     * 使用 setnx 命令尝试获取锁
     * @param lock 锁标识
     * @return 是否获取锁
     */
    private boolean tryLock(String lock) {
        // 使用 setnx 命令尝试获取锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lock, "", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 直接返回Boolean需要拆箱，有空指针的风险
        return Boolean.TRUE.equals(flag);
    }

    /**
     * 释放锁
     * @param lock 锁标识
     */
    private void unlock(String lock) {
        stringRedisTemplate.delete(lock);
    }

}
