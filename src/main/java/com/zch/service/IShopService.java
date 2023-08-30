package com.zch.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zch.dto.Result;
import com.zch.entity.Shop;

/**
 * @author Zch
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
