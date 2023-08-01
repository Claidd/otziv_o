package com.hunt.otziv.a_login.services.service;




import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.User;
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

//
//    Object getUserByPrincipal(Principal principal);
//    Optional<User> findByUserName(String username);


}
