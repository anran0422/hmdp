package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.CacheClient;
import com.hmdp.constant.RedisConstants;
import com.hmdp.model.dto.RedisData;
import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 *  服务实现类
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ExecutorService executorService;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result getShopById(Long id) throws InterruptedException {
        Shop shop = null;
        // Null 解决缓存穿透
//        shop = getShopWithNull(id);
        shop = cacheClient.getByCacheNull(
                RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁 解决缓存击穿
//        shop = getShopWithLock(id);
//        shop = cacheClient.getByMutex(
//                RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
//                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期时间 解决缓存击穿
//        shop = getShopWithLogicalExpireTime(id);
//        shop = cacheClient.getByLogicalExpireTime(
//                RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
//                this::getById, 20L, TimeUnit.SECONDS);

        if(ObjectUtil.isNull(shop)) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    private Shop getShopWithLogicalExpireTime(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //  1. 从 Redis 查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //  2. 是否命中缓存
        if(StrUtil.isBlank(shopJson)) {
            //  3. 未命中 直接返回
            return null;
        }
        // 4. 命中 把 Json 反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime logicalExpireTime = redisData.getLogicalExpireTime();

        // 5. 判断是否过期
        // 5.1 未过期 直接返回店铺
        if(logicalExpireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 5.2 过期 缓存重建
        // 6. 缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Boolean getLock = tryLock(lockKey);
        // 6.1 判断互斥锁是否获取锁成功
        if(getLock) {
            // 6.3 成功
            // 优化：获取锁成功再见检测 Redis 缓存是否过期，DoubleCheck；存在无需构建缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            data = (JSONObject) redisData.getData();
            logicalExpireTime = redisData.getLogicalExpireTime();
            if(logicalExpireTime.isAfter(LocalDateTime.now())) {
                return JSONUtil.toBean(data, Shop.class);
            }

            // 开启独立线程，实现缓存重建
            executorService.submit(() -> {
                try {
                    // 重新构建缓存封装了
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 6.2 失败，返回过期的商铺信息
        return shop;
    }

    /**
     * 互斥锁解决缓存击穿
     */
    private Shop getShopWithLock(Long id) throws InterruptedException {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //  1. 从 Redis 查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //  2. 是否命中缓存
        if(StrUtil.isNotBlank(shopJson)) {
            //  3. 命中 且不是空值 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 命中 判断是否为空值
        if(shopJson != null) {
            // 返回错误信息
            return null;
        }

        // 4. 未命中，尝试获取到锁进行缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean getLock = tryLock(lockKey);
            // 4.1 失败 休眠重新尝试
            if(!getLock) {
                Thread.sleep(50);
                return getShopWithLock(id);
            }

            // 4.2 todo 优化 DoubleCheck 缓存是否存在
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            // 这个地方应该没有了，毕竟再返回 谁重建缓存呢？
            //if(shopJson != null) {
            //    return null;
            //}

            // 4.3 成功 根据 id 查询数据库
            shop = this.getById(id);
            // 模拟重建延迟
            Thread.sleep(200);

            //  5. 不存在，返回 404
            if(ObjectUtil.isNull(shop)) {
                // 缓存穿透优化
                // 写入空值
                stringRedisTemplate.opsForValue().set(key, "",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //  6. 存在，写入到 Redis 中 并且返回结果
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                    RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 释放互斥锁
            unlock(lockKey);
        }

        return shop;
    }

    /**
     * Null 值解决缓存穿透
     */
    private Shop getShopWithNull(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //  1. 从 Redis 查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //  2. 是否命中缓存
        if(StrUtil.isNotBlank(shopJson)) {
            //  3. 命中 且不是空值 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 命中 为空值
        if(shopJson != null) {
            // 返回错误信息
            return null;
        }

        //  4. 未命中，根据 id 查询数据库
        Shop shop = this.getById(id);
        //  5. 不存在，返回 404
        if(ObjectUtil.isNull(shop)) {
            // 缓存穿透优化
            // 写入空值
            stringRedisTemplate.opsForValue().set(key, "",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //  6. 存在，写入到 Redis 中 并且返回结果
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    @Override
    @Transactional
    public Result updateShopById(Shop shop) {
        Long id = shop.getId();
        if(ObjectUtil.isNull(id) || id <= 0) {
            return Result.fail("商铺 id 不能为空！");
        }
        // 1. 更新数据库
        this.updateById(shop);
        // 2. 删除缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(key);

        return Result.ok();
    }

    private Boolean tryLock(String key) {
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 20, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(isLock);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 缓存预热
     * 模拟这个动作
     */
    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
        // 查询数据库
        Shop shop = this.getById(id);
        Thread.sleep(200L);
        // 封装数据
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setLogicalExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 存储到 Redis 中
        // 逻辑过期时间，不是设置 key 的过期时间
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

}
