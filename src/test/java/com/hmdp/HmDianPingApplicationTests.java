package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testSetWithLogicalExpire() {
        Shop shop = shopService.getById(1L);
        CacheClient cacheClient = new CacheClient(stringRedisTemplate);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1, shop, 1L, TimeUnit.SECONDS);
    }
}
