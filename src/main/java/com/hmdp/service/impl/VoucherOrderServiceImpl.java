package com.hmdp.service.impl;

import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.SeckillVoucher;
import com.hmdp.model.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    public Result createVocherOrder(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否开始或者结束
        Integer stock = seckillVoucher.getStock();
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        // a. 没开始，抛出异常返回
        if(LocalDateTime.now().isBefore(beginTime)) {
            return Result.fail("秒杀尚未开始！");
        }
        // b. 结束，抛出异常返回
        if(LocalDateTime.now().isAfter(endTime)) {
            return Result.fail("秒杀已经结束！");
        }
        // 3. 开始，判断库存是否充足（这可能就有并发问题）
        // a. 不充足，抛出异常
        if(stock < 1) {
            return Result.fail("秒杀券库存不足！");
        }

        // 4. 一人一单
        Long userId = UserHolder.getUser().getId();
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
