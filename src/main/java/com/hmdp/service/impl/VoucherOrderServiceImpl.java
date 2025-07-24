package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.SeckillVoucher;
import com.hmdp.model.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *  服务实现类
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 阻塞队列
     */
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    /**
     * 异步线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 阻塞队列 处理秒杀下单
     */
//    private class VoucherOrderHandler implements Runnable {
//
//        @Override
//        public void run() {
//            while(true) {
//                try {
//                    // 1. 获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 2. 创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.info("订单处理异常", e);
//                }
//            }
//        }
//    }

    /**
     * 消息队列 处理秒杀下单
     */
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while(true) {
                try {
                    // 1. 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2. 判断订单信息是否为空
                    if(list == null || list.isEmpty()) {
                        // 如果为 null, 没有消息 继续循环
                        continue;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4. 确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1",record.getId());
                } catch (Exception e) {
                    log.info("订单处理异常", e);
                    // 处理异常消息
                    try {
                        handlePendingList();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
        private void handlePendingList() throws InterruptedException {
            while(true) {
                try {
                    // 1. 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2. 判断订单信息是否为空
                    if(list == null || list.isEmpty()) {
                        // 如果为 null, 没有异常消息 结束循环
                        break;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4. 确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1",record.getId());
                } catch (Exception e) {
                    log.info("处理异pendding订单异常", e);
                    Thread.sleep(20);
                }
            }
        }
    }


    /**
     * 异步处理
     * 真正的下单流程
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 新开线程获取 userId
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3. 获取锁对象
        boolean isLock = lock.tryLock();
        // 4. 是否获取成功
        if(!isLock) {
            // 获取失败 返回错误或者重试
            log.error("不允许重复下单");
            return;
        }

        // 获取锁之后进行处理
        try {
            // 获取代理对象（事务）
            proxy.handleOneOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    /**
     * 利用 Redis + 消息队列 异步秒杀下单
     */
    @Override
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1. 执行 Lua 脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2. 判断结果是否 为 0
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不可重复下单");
        }

        // 4. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 5. 返回订单 Id
        return Result.ok(orderId);
    }


    /**
     * 利用 Redis + 阻塞队列 异步秒杀下单
     */
//    @Override
//    public Result createVoucherOrder(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        long orderId = redisIdWorker.nextId("order");
//        // 1. 执行 Lua 脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        // 2. 判断结果是否 为 0
//        int r = result.intValue();
//        if (r != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不可重复下单");
//        }
//
//        // 3. 创建订单信息 保存到阻塞队列 这里不下单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        // 保存到阻塞队列
//        orderTasks.add(voucherOrder);
//
//        // 4. 获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        // 5. 返回订单 Id
//        return Result.ok(orderId);
//    }

    /**
     * 使用锁去进行 秒杀下单
     */
//    @Override
//    public Result createVocherOrder(Long voucherId) {
//        // 1. 查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 2. 判断秒杀是否开始或者结束
//        Integer stock = seckillVoucher.getStock();
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        // a. 没开始，抛出异常返回
//        if(LocalDateTime.now().isBefore(beginTime)) {
//            return Result.fail("秒杀尚未开始！");
//        }
//        // b. 结束，抛出异常返回
//        if(LocalDateTime.now().isAfter(endTime)) {
//            return Result.fail("秒杀已经结束！");
//        }
//        // 3. 开始，判断库存是否充足（这可能就有并发问题）
//        // a. 不充足，抛出异常
//        if(stock < 1) {
//            return Result.fail("秒杀券库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        // 创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 获取锁对象
//        boolean isLock = lock.tryLock();
//        // 是否获取成功
//        if(!isLock) {
//            // 获取失败 返回错误或者重试
//            return Result.fail("不允许重复下单");
//        }
//
//        // 获取锁之后进行处理
//        try {
//            // 获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.handleOneOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//    }

    /**
     * 处理一人一单业务
     */
    @Transactional
    public void handleOneOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 4. 一人一单
        Integer count = this.query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if(count > 0) {
            // 用户买过了
            log.error("用户已经买过了");
            return;
        }

        // 5. 充足，扣减库存
        boolean res = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if(!res) {
            log.error("库存不足");
            return;
        }
        // 7. 保存订单
        this.save(voucherOrder);
    }
}
