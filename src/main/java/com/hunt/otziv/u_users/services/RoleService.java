package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;

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
