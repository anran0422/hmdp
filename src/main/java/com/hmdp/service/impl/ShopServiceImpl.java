package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.RedisConstants;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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

    @Override
    public Result getShopById(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //  1. 从 Redis 查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //  2. 是否命中缓存
        if(StrUtil.isNotBlank(shopJson)) {
            //  3. 命中，直接返回商铺
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //  4. 未命中，根据 id 查询数据库
        Shop shop = this.getById(id);
        //  5. 不存在，返回 404
        if(ObjectUtil.isNull(shop)) {
            return Result.fail("查询商铺不存在!");
        }
        //  6. 存在，写入到 Redis 中 并且返回结果
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));

        return Result.ok(shop);
    }
}
