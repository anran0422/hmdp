package com.hmdp.service;

import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopService extends IService<Shop> {

    Result getShopById(Long id) throws InterruptedException;

    Result updateShopById(Shop shop);

    Result getShopByType(Integer typeId, Integer current, Double x, Double y);
}
