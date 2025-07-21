package com.hmdp.service;

import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 *  服务类
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result createVoucherOrder(Long voucherId);

    Result handleOneOrder(Long voucherId);
}
