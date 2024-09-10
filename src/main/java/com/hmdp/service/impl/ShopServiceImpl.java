package com.hmdp.service.impl;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
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
        CacheClient cacheClient = new CacheClient(stringRedisTemplate);
        // 缓存穿透
        // return cacheClient.queryWithPassThrough(
        //         "shop", id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 缓存击穿（互斥锁）
        return cacheClient.queryWithMutex(
                "shop", id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 缓存击穿（逻辑过期）
        // return cacheClient.queryWithLogicalExpire(
        //         "shop", id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }
}
