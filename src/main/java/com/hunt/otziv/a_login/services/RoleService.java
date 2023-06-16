package com.hunt.otziv.a_login.services;

import com.hunt.otziv.a_login.model.Role;
import com.hunt.otziv.a_login.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoleService{
    private  final RoleRepository roleRepository;

    public Role getUserRole(){
        return roleRepository.findByName("ROLE_WORKER").get();
    }
}
