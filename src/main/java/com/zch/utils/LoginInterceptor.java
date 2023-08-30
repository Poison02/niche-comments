package com.zch.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author Zch
 * @date 2023/8/23
 **/
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 这个拦截器只需要判断ThreadLocal里面是否有用户即可
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        // 没有用户则拦截
        return true;
    }
}
