package com.hunt.otziv.u_users.services.service;




import com.hunt.otziv.u_users.dto.*;
import com.hunt.otziv.u_users.model.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface UserService extends UserDetailsService{

    void updateProfile(RegistrationUserDTO userDTO, String role, OperatorDTO operatorDTO, ManagerDTO managerDTO, WorkerDTO workerDTO, MarketologDTO marketologDTO, MultipartFile imageFile) throws IOException;

    User save(RegistrationUserDTO userDto, MultipartFile file) throws IOException;
    UserDetails loadUserByUsername(String username);

    List<RegistrationUserDTO> getAllUsers();

    RegistrationUserDTO findById(Long id);

    List<String> getAllUsersByFio(String roleName);

    Optional<User> findByFio(String operator);

    Optional<User> findByUserName(String username);

    void deleteOperator(String username, Long operatorId);

    void deleteManager(String username, Long managerId);

    void deleteWorker(String username, Long workerId);
    void deleteMarketolog(String username, Long marketologId);

    void save(User user);

    Optional<User> findByChatId(long chatId);


//
//    Object getUserByPrincipal(Principal principal);
//    Optional<User> findByUserName(String username);


}
