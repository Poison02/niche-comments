package com.zch.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zch.dto.Result;
import com.zch.entity.VoucherOrder;

/**
 * @author Zch
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillOrder(Long voucherId);

    // Result createVoucherOrder(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);
}
