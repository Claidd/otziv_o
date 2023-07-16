package com.hunt.otziv.a_login.services;

import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.Role;
import com.hunt.otziv.a_login.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService{
    private  final RoleRepository roleRepository;

    // Метод поиск роли в БД по определённой строке, для создания юзера
    public Role getUserRole(){
        return roleRepository.findByName("ROLE_ADMIN").get();
    }

    // Метод поиск роли в БД по переданной строке, для смены роли
    public Role getUserRole(String role){
        return roleRepository.findByName(role).get();
    }

    // Взять все роли - начало
    public Collection<Role> getAllRoles() {
        return (Collection<Role>) roleRepository.findAll();
    }
    // Взять все роли - конец

}
