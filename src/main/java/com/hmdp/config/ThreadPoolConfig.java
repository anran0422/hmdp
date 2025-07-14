package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ThreadPoolConfig {
    @Bean
    public ExecutorService executorService() {
        // 创建一个固定大小为10的线程池（可以根据需要修改）
        return Executors.newFixedThreadPool(10);
    }
}
