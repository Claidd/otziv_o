package com.hunt.otziv.a_login.services.service;

import com.hunt.otziv.a_login.dto.UserDTO;

import java.security.Principal;

public interface UserService {
    boolean create(UserDTO userDto);

    Object getUserByPrincipal(Principal principal);
}
