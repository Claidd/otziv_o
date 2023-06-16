package com.hunt.otziv.a_login.services.service;




import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

public interface UserService extends UserDetailsService{

    User create(RegistrationUserDTO userDto);


    UserDetails loadUserByUsername(String username);

//
//    Object getUserByPrincipal(Principal principal);
//    Optional<User> findByUserName(String username);


}
