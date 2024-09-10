package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    /**
     * 实现商铺缓存，逻辑过期 解决 缓存击穿（高并发且重建时间长的缓存失效，导致大量的请求打到数据库）
     * @param id 商铺id
     * @return 商铺对象
     */
    @Override
    public Shop queryById(Long id) {
        // 查询Redis缓存
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        if (StrUtil.isBlank(json)) {
            // 缓存未命中
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        // 获取逻辑过期时间
        LocalDateTime expireTime = redisData.getExpireTime();

        if (LocalDateTime.now().isBefore(expireTime)) {
            // 没有过期，直接返回
            return shop;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        // 加锁
        boolean isLock = tryLock(lockKey);
        if (!isLock) {
            // 没锁上，其它线程在重建缓存，返回旧数据
            return shop;
        }
        // 开启新线程重建缓存
        executorService.submit(() -> {
            try {
                // 重建缓存
                saveShop2Redis(id, TimeUnit.MINUTES.toSeconds(CACHE_SHOP_TTL));
                // 模拟缓存重建时间
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                // 释放锁
                unlock(lockKey);
            }
        });

        // 返回旧 shop
        return shop;
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

    /**
     * 缓存预热
     * @param id 商铺id
     * @param expireSeconds 逻辑过期时间
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 查询数据库
        Shop shop = getById(id);

        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);

        // 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
