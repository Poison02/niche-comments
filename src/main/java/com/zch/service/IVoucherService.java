package com.zch.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zch.dto.Result;
import com.zch.entity.Voucher;

/**
 * @author Zch
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);
}
