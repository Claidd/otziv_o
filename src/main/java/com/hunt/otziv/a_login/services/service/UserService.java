package com.hunt.otziv.a_login.services.service;




import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;
import java.util.Optional;

public interface UserService extends UserDetailsService{

    User save(RegistrationUserDTO userDto);
    UserDetails loadUserByUsername(String username);

    public List<RegistrationUserDTO> getAllUsers();
    RegistrationUserDTO findById(Long id);

//
//    Object getUserByPrincipal(Principal principal);
//    Optional<User> findByUserName(String username);


}
