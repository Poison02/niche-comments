package com.zch.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * redis实现分布式锁的实现类
 * @author Zch
 * @date 2023/8/26
 **/
public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String LOCK_PREFIX = "lock:";
    private static final String LOCK_ID = UUID.randomUUID().toString(true) + "-";

    // lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取当前线程标识
        String lockId = LOCK_ID + Thread.currentThread().getId();
        // 获取锁
        Boolean isLock = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_PREFIX + name, lockId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);
    }

    @Override
    public void unLock() {
        // 使用Lua脚本删除，保证redis命令的原子性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_PREFIX + name),
                LOCK_ID + Thread.currentThread().getId()
        );

        // 使用编码方式删除
        /*// 在删除锁之前判断该锁是否是自己的，是自己的才能山
        String lockId = LOCK_ID + Thread.currentThread().getId();
        String lockVal = stringRedisTemplate.opsForValue().get(LOCK_PREFIX + name);
        if (lockId.equals(lockVal)) {
            stringRedisTemplate.delete(LOCK_PREFIX + name);
        }*/
    }
}
