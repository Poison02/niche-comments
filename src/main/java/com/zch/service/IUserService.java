package com.zch.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zch.dto.LoginFormDTO;
import com.zch.dto.Result;
import com.zch.entity.User;

import javax.servlet.http.HttpSession;

/**
 * @author Zch
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();
}
