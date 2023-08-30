package com.zch.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author Zch
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
