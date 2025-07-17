package com.hmdp.service;

import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result createVocherOrder(Long voucherId);
}
