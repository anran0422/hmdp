package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.SeckillVoucher;
import com.hmdp.model.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 *  服务实现类
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

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
                voucherId.toString(), userId.toString()
        );
        // 2. 判断结果是否 为 0
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不可重复下单");
        }

        // todo 保存到阻塞队列
        // 3. 返回订单 Id
        return Result.ok(orderId);
    }

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

    @Transactional
    public Result handleOneOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 4. 一人一单
        Integer count = this.query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if(count > 0) {
            return Result.fail("秒杀优惠券一人只能购买一张，不可以贪心~");
        }

        // 5. 充足，扣减库存
        boolean res = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if(!res) {
            Result.fail("更新库存失败！");
        }
        // 6. 创建订单（优惠订单 voucher_order）
        VoucherOrder voucherOrder = new VoucherOrder();
        // a. 订单 id
        voucherOrder.setId(redisIdWorker.nextId("order"));
        // b. 用户 id
        voucherOrder.setUserId(userId);
        // c. 代金券的 id
        voucherOrder.setVoucherId(voucherId);

        // 7. 保存订单
        this.save(voucherOrder);
        // 8. 返回订单 ID
        return Result.ok(voucherOrder.getId());
    }
}
