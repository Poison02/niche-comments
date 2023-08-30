package com.zch.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.zch.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 这个拦截器的作用就是刷新token，因为用户不可能只操作我们指定的拦截页面
 * 这个拦截器的作用只有刷新token，并不会做任何拦截操作！！！这是一个坑，我栽了
 * @author Zch
 * @date 2023/8/23
 **/
public class RefreshTokenLoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenLoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /*// 1. 获取session
        HttpSession session = request.getSession();*/
        // 1. 获取token，从header中
        String token = request.getHeader("authorization");
        // 如果为空 不拦截
        if (StrUtil.isBlank(token)) {
            return true;
        }
        /*// 2. 从session中获取用户
        Object user = session.getAttribute("user");*/
        // 2. 从redis中获取用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(RedisConstants.LOGIN_USER_KEY + token);
        // 3. 判断用户是否存在 不拦截
        if (userMap.isEmpty()) {
            return true;
        }
        // 3. 将userMap转为对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 5. 用户存在就保存在ThreadLocal并放行
        UserHolder.saveUser(userDTO);
        // 6. 刷新token，为什么要刷新token？
        // 因为这里是模仿session，session是用户超过30min没操作的话就会自动删除，那么我们应该也要继续刷新token
        // 而不是不管有没有操作 30min都要删除
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 7. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
