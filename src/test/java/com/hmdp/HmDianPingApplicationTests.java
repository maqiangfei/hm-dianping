package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IUserService userService;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Test
    void testSetWithLogicalExpire() {
        Shop shop = shopService.getById(1L);
        CacheClient cacheClient = new CacheClient(stringRedisTemplate);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1, shop, 1L, TimeUnit.SECONDS);
    }

    /**
     * 将用户信息存入Redis，并返回token文件
     */
    @Test
    void testCacheUser2Redis() throws FileNotFoundException {
        // 查询出所有的用户
        List<User> users = userService.list();
        List<UserDTO> userDTOS = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 输出用户数量
        System.out.println(userDTOS.size());
        PrintStream out = new PrintStream(new FileOutputStream("/Users/maffy/Documents/tokens.txt"));
        for (UserDTO userDTO : userDTOS) {
            // 生成token
            String token = UUID.randomUUID().toString(true);
            String key = LOGIN_USER_KEY + token;
            // 将用户属性及值存入map
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((k, v) -> v.toString()));
            // 将用户登录信息存入Redis
            stringRedisTemplate.opsForHash().putAll(key, userMap);
            // 将用户输出到文件
            out.println(token + ",");
        }
    }

    /**
     * 将商铺的位置信息保存进GEO类型中
     */
    @Test
    void loadShopData() {
        // 查询店铺信息
        List<Shop> list = shopService.list();
        // 店铺按照typeId分组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 分批写入Redis
        map.forEach((typeId, shops) -> {
            String key = SHOP_GEO_KEY + typeId;
            // 获取GeoLocation集合
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            for (Shop shop : shops) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        });
    }

    /**
     * HyperLogLog用于UV(独立访问量)、PV(页面访问量)
     * 将用户标识存入HyperLogLog类型中，去重与计数，单个HLL的内存永远小于16kb，计数误差小于0.81%
     */
    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int index = 0;
        for (int i = 1; i <= 1000000; i++) {
            values[index++] =  "user_" + i;
            if (i % 1000 == 0) {
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl", values );
                index = 0;
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl");
        System.out.println(count);
    }

    /**
     * 测试RabbitMQ发送消息
     */
    @Test
    void testRabbitMQ() {
        String queueName = "simple.queue";
        String msg = "hello, amqp";
        rabbitTemplate.convertAndSend(queueName, msg);
    }
}
