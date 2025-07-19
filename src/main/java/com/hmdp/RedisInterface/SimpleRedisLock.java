package com.hmdp.RedisInterface;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    /**
     * 业务名称
     * 可扩展
     */
    private final String name;

    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 构成业务标识
     */
    private static final String KEY_PREFIX = "lock:";

    /**
     * 构成 锁标识
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";


    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId,
                timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        // 获取锁中线程标识
        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        if(threadId.equals(lockId)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
