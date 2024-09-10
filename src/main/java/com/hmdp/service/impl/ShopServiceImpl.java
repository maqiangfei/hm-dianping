package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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

    /**
     * 实现商铺缓存，互斥锁 解决 缓存击穿（高并发且重建时间长的缓存失效，导致大量的请求打到数据库）
     * @param id 商铺id
     * @return 商铺对象
     */
    @Override
    public Shop queryById(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;

        // 查询Redis缓存
        String json = stringRedisTemplate.opsForValue().get(shopKey);

        if (StrUtil.isNotBlank(json)) {
            // 缓存命中，刷新缓存有效期并返回
            stringRedisTemplate.expire(shopKey, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return JSONUtil.toBean(json, Shop.class);
        }

        if (json != null) {
            // 命中空对象
            return null;
        }

        Shop shop = null;
        try {
            // 尝试获取锁
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            if (!isLock) {
                // 获取锁失败，等待后再次尝试
                Thread.sleep(50);
                return queryById(id);
            }

            // 获取锁成功，查询数据库
            shop = lambdaQuery().eq(Shop::getId, id).one();

            Thread.sleep(200); // 模拟重构缓存延时

            if (shop == null) {
                // 数据库中没有该店铺，缓存空对象
                stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 将店铺添加进缓存
            String shopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(shopKey, shopJson, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(LOCK_SHOP_KEY + id);
        }

        // 返回Shop
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
}
