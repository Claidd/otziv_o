package com.hunt.otziv.a_login.services;

import com.hunt.otziv.a_login.dto.UserDTO;
import com.hunt.otziv.a_login.model.Role;
import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.a_login.repository.UserRepository;
import com.hunt.otziv.a_login.services.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;


    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

// Создание нового пользователя "Клиент"
    public boolean create(UserDTO userDto){
        log.info("3. Заходим в создание нового юзера и проверяем совпадение паролей");
        if(!Objects.equals(userDto.getPassword(), userDto.getMatchingPassword())){
            throw new RuntimeException("Password is not equal");
        }
        log.info("4. Создаем юзера");
        User user = User.builder()
                .username(userDto.getUsername())
//                .password(passwordEncoder.encode(userDto.getPassword()))
                .password(userDto.getPassword())
                .email(userDto.getEmail())
                .phoneNumber(userDto.getPhoneNumber().replaceFirst("8", "+7"))
                .roles(Role.WORKER)
                .active(true)
                .activateCode(UUID.randomUUID().toString())
                .build();
        userRepository.save(user);
        log.info("5. Юзер успешно создан");
//        this.save(user);
        return true;

    }

    public User getUserByPrincipal(Principal principal) {
        if (principal == null) return new User();
        return userRepository.findByEmail(principal.getName());
    }





//    @Override
//    @Transactional
//    public void save(User user) {
//        userRepository.save(user);
//        if(user.getActivateCode() != null && !user.getActivateCode().isEmpty()){
//            mailSenderService.sendActivateCode(user);
//        }
//    }


//    @Override
//    @Transactional
//    public boolean activateUser(String activateCode) {
//        if(activateCode == null || activateCode.isEmpty()){
//            return false;
//        }
//        User user = userRepository.findFirstByActivateCode(activateCode);
//        if(user == null){
//            return false;
//        }
//
//        user.setActivateCode(null);
//        userRepository.save(user);
//
//        return true;
//    }
}
