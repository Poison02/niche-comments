package com.zch.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 这个类是用来生成全局唯一ID的工具类
 * 使用64位存储，
 * - 第一位是符号位始终为0
 * - 中间31位是从2023-01-01 00:00:00开始用秒计数
 * - 后32位采用redis的自增
 * @author Zch
 * @date 2023/8/25
 **/
@Component
public class RedisIDWorker {

    private static final int COUNT_BITS = 32;

    private static final long BEGIN_TIMESTAMP = 1672531200L;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIDWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long noeSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = noeSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号
        // 2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 使用redis的自增长，后面加上date是为了以后更好的统计生成的id数
        long count = stringRedisTemplate.opsForValue().increment("incr:" + keyPrefix + ":" + date);

        // 3. 拼接返回，使用位运算拼接！格式是：时间戳加自增长
        return timestamp << COUNT_BITS | count;
    }
}
