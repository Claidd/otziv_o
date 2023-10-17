package com.hunt.otziv.u_users.dto;

import lombok.Data;

@Data
public class JwtRequest {

    private String username;
    private String password;
}
