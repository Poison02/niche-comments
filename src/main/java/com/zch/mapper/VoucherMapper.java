package com.zch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zch.entity.Voucher;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author Zch
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
