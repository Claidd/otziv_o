package com.hunt.otziv.u_users.services.service;




import com.hunt.otziv.u_users.dto.RegistrationUserDTO;
import com.hunt.otziv.u_users.model.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;
import java.util.Optional;

public interface UserService extends UserDetailsService{

    void updateProfile(RegistrationUserDTO userDTO, String role);

    User save(RegistrationUserDTO userDto);
    UserDetails loadUserByUsername(String username);

    List<RegistrationUserDTO> getAllUsers();

    RegistrationUserDTO findById(Long id);

    List<String> getAllUsersByFio(String roleName);

    Optional<User> findByFio(String operator);

    Optional<User> findByUserName(String username);

//
//    Object getUserByPrincipal(Principal principal);
//    Optional<User> findByUserName(String username);


}
