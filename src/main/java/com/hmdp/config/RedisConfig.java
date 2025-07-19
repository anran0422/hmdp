package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient() {
        // 配置类
        Config config = new Config();
        // 添加 Redis 地址，这里是单点地址
        // 也可以使用 useClusterServers() 集群地址
        config.useSingleServer().setAddress("redis://localhost:6379");
        // 创建客户端
        return Redisson.create(config);
    }
}
