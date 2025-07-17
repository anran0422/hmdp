package com.hmdp.service;

import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 *  服务类
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);
}
