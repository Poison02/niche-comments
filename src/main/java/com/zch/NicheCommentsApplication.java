package com.zch;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * @author Zch
 * @date 2023/8/30
 **/
// 使得能够暴露代理对象，防止事务失效
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.zch.mapper")
@SpringBootApplication
public class NicheCommentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(NicheCommentsApplication.class);
    }

}
