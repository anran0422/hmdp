package com.hmdp.RedisInterface;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collection;
import java.util.Collections;
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

    /**
     * 加载 lua 脚本
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId,
                timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }

    /**
     * Lua 脚本版本
     */
    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }

    /**
     * 存在原子性问题
     */
//    @Override
//    public void unlock() {
//        // 线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//
//        // 获取锁中线程标识
//        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//
//        if(threadId.equals(lockId)) {
//            // 释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
