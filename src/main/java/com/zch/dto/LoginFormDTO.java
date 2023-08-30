package com.zch.dto;

import lombok.Data;

/**
 * @author Zch
 */
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
