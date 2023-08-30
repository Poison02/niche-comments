package com.zch.config;

import com.zch.utils.LoginInterceptor;
import com.zch.utils.RefreshTokenLoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @author Zch
 * @date 2023/8/23
 **/
@Configuration
public class WebMvcInterceptor implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 普通拦截器只需要在指定页面检测用户是否存在即可
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/blog/hot",
                        "/upload/**",
                        "/user/login",
                        "/user/code"
                )
                .order(1);
        // 刷新token的拦截器需要拦截所有请求
        registry.addInterceptor(new RefreshTokenLoginInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);
    }
}
