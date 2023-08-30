package com.zch.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zch.entity.UserInfo;
import com.zch.mapper.UserInfoMapper;
import com.zch.service.IUserInfoService;
import org.springframework.stereotype.Service;

/**
 * @author Zch
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
