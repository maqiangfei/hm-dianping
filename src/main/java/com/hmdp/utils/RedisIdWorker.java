package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static com.hmdp.utils.RedisConstants.INCR_KEY;


@Component
public class RedisIdWorker {

    /**
     * 2024年9月1日0时0分0秒的时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1725148800L;

    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成订单id
     * @param keyPrefix 自增key前缀
     * @return 订单id
     */
    public long nextId(String keyPrefix) {
        // 获取时间戳
        long timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        // 将日期作为key
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 获取序列号（Redis整型自增）
        long incr = stringRedisTemplate.opsForValue().increment(INCR_KEY + keyPrefix + ":" + date);

        // 拼接时间戳和序列号并返回
        return timestamp << COUNT_BITS | incr;
    }
}
