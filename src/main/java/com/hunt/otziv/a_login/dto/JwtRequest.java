package com.hunt.otziv.a_login.dto;

import lombok.Data;

@Data
public class JwtRequest {
    private String username;
    private String password;
}
