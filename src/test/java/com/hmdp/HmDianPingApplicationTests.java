package com.hmdp;

import com.hmdp.constant.RedisConstants;
import com.hmdp.model.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private ExecutorService executorService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void saveToRedis() throws InterruptedException {
        shopService.saveShopToRedis(1L, 20L);
    }

    @Test
    void testId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void loadData() {
        // 1. 查询店铺信息
        List<Shop> list = shopService.list();
        // 2. 店铺按照 typeId 分组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 按照类型分批写入到 Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // a. 获取 typeId
            Long typeId = entry.getKey();
            // b. 获取同类型的店铺集合
            List<Shop> value = entry.getValue();
            // c. 写入 Redis，GEOADD key 经度 维度 member
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
//                stringRedisTemplate.opsForGeo().add(key,
//                        new Point(shop.getX(), shop.getY()),
//                        shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }

    }

    @Test
    void testHyperLogLog() {
        String[] users = new String[1000];
        int index = 0;
        for (int i = 1; i <= 1000000; i++) {
            users[index++] = "user_" + i;
            if(i % 1000 == 0) {
                index = 0;
                stringRedisTemplate.opsForHyperLogLog().add("hpll", users);
            }
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hpll");
        System.out.println("count = " + count);
    }
}
