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

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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
     * 实现商铺缓存
     * @param id 商铺id
     * @return 商铺对象
     */
    @Override
    public Shop queryById(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;

        // 查询Redis缓存
        String json = stringRedisTemplate.opsForValue().get(shopKey);

        if (StrUtil.isNotBlank(json)) {
            // 缓存命中
            return JSONUtil.toBean(json, Shop.class);
        }

        // 缓存没命中，查询数据库
        Shop shop = lambdaQuery().eq(Shop::getId, id).one();

        if (shop == null) {
            // 数据库中没有该店铺
            return null;
        }

        // 将店铺添加进缓存
        String shopJson = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(shopKey, shopJson, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 返回shop
        return shop;
    }
}
