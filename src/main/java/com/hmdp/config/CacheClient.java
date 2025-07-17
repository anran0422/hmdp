package com.hmdp.config;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.RedisConstants;
import com.hmdp.model.dto.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    @Resource
    private ExecutorService executorService;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意 java 对象序列化为 json
     * 存储在 string 的 key 中
     * 并且可以设置 TTL 过期值
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将任意 java 对象序列化为 json
     * 存储在 string 的 key 中
     * 并且可以设置 逻辑过期时间，用于解决缓存击穿问题
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setLogicalExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入 Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的 key 查询缓存
     * 并反序列化为指定类型
     * 利用缓存空值，解决缓存穿透问题
     */
    public <R,ID> R getByCacheNull(
            String keyPrefix, ID id, Class<R> classType, Function<ID,R> dbCallback,
            Long time, TimeUnit unit) {

        String key = keyPrefix + id;
        //  1. 从 Redis 查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //  2. 是否命中缓存
        if(StrUtil.isNotBlank(json)) {
            //  3. 命中 且不是空值 直接返回
            return JSONUtil.toBean(json, classType);
        }
        // 命中 为空值
        if(json != null) {
            // 返回错误信息
            return null;
        }

        //  4. 未命中，根据 id 查询数据库
        R r = dbCallback.apply(id);
        //  5. 不存在，返回 404
        if(ObjectUtil.isNull(r)) {
            // 缓存穿透优化
            // 写入空值
            stringRedisTemplate.opsForValue().set(key, "",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //  6. 存在，写入到 Redis 中 并且返回结果
        this.set(key, r, time, unit);

        return r;
    }

    /**
     * 根据指定的 key 查询缓存
     * 并反序列化为指定类型
     * 利用逻辑过期时间，解决缓存击穿问题
     */
    public <R,ID> R getByLogicalExpireTime(
            String keyPrefix, ID id, Class<R> classType, Function<ID,R> dbCallback,
            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //  1. 从 Redis 查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //  2. 是否命中缓存
        if(StrUtil.isBlank(json)) {
            //  3. 未命中 直接返回
            return null;
        }
        // 4. 命中 把 Json 反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, classType);
        LocalDateTime logicalExpireTime = redisData.getLogicalExpireTime();

        // 5. 判断是否过期
        // 5.1 未过期 直接返回店铺
        if(logicalExpireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 5.2 过期 缓存重建
        // 6. 缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Boolean getLock = this.tryLock(lockKey);
        // 6.1 判断互斥锁是否获取锁成功
        if(getLock) {
            // 6.3 成功
            // 优化：获取锁成功再见检测 Redis 缓存是否过期，DoubleCheck；存在无需构建缓存
            json = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(json, RedisData.class);
            data = (JSONObject) redisData.getData();
            logicalExpireTime = redisData.getLogicalExpireTime();
            if(logicalExpireTime.isAfter(LocalDateTime.now())) {
                return JSONUtil.toBean(data, classType);
            }

            // 开启独立线程，实现缓存重建
            executorService.submit(() -> {
                try {
                    // 重新构建缓存
                    // 查询数据库
                    R r1 = dbCallback.apply(id);
                    // 存入到缓存中
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    this.unlock(lockKey);
                }
            });
        }
        // 6.2 失败，返回过期的商铺信息
        return r;
    }

    /**
     * 根据指定的 key 查询缓存
     * 并反序列化为指定类型
     * 利用互斥锁，解决缓存击穿问题
     */
    public <R,ID> R getByMutex(
            String keyPrefix, ID id, Class<R> classType, Function<ID,R> dbCallback,
            Long time, TimeUnit unit
    ) throws InterruptedException {

        String key = keyPrefix + id;
        //  1. 从 Redis 查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //  2. 是否命中缓存
        if(StrUtil.isNotBlank(json)) {
            //  3. 命中 且不是空值 直接返回
             return JSONUtil.toBean(json, classType);
        }
        // 命中 判断是否为空值
        if(json != null) {
            // 返回错误信息
            return null;
        }

        // 4. 未命中，尝试获取到锁进行缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean getLock = this.tryLock(lockKey);
            // 4.1 失败 休眠重新尝试
            if(!getLock) {
                Thread.sleep(50);
                // todo
                return getByMutex(keyPrefix, id, classType,dbCallback, time, unit);
            }

            // 4.2 todo 优化 DoubleCheck 缓存是否存在
            json = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, classType);
            }
            // 这个地方应该没有了，毕竟再返回 谁重建缓存呢？
            //if(shopJson != null) {
            //    return null;
            //}

            // 4.3 成功 根据 id 查询数据库
            r = dbCallback.apply(id);
            // 模拟重建延迟
            Thread.sleep(200);

            //  5. 不存在，返回 404
            if(ObjectUtil.isNull(r)) {
                // 缓存穿透优化
                // 写入空值
                stringRedisTemplate.opsForValue().set(key, "",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //  6. 存在，写入到 Redis 中 并且返回结果
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 释放互斥锁
            this.unlock(lockKey);
        }

        return r;
    }

    private Boolean tryLock(String key) {
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 20, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(isLock);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
