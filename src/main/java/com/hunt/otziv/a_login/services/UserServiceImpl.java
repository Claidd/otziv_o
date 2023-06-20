package com.hunt.otziv.a_login.services;


import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.a_login.repository.RoleRepository;
import com.hunt.otziv.a_login.repository.UserRepository;

import com.hunt.otziv.a_login.services.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserServiceImpl  implements UserService {
    private final UserRepository userRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, RoleRepository roleRepository, RoleService roleService, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
    }

// INSERT INTO otziv_o.roles (name) values ('ROLE_WORKER');
//      =====================================SECURITY=======================================================

    public Optional<User> findByUserName(String username){
        return userRepository.findByUsername(username);
    }

    @Transactional
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", username)
        ));
        System.out.println(user.getUsername() + user.getPassword()
                + user.getRoles().stream().map(role -> new SimpleGrantedAuthority(role.getName())).toList());
//        return new UserDetailsImpl(user.get());
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                user.getRoles().stream().map(role -> new SimpleGrantedAuthority(role.getName())).collect(Collectors.toList())
        );
    }
    //      =====================================SECURITY - END=======================================================

    //      =====================================CREATE USERS - START=======================================================
    // Взять всех юзеров - начало
    @Override
    public List<RegistrationUserDTO> getAllUsers() {
        log.info("Берем все юзеров");
        return userRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());

    }
    // Взять всех юзеров - конец

    // Перевод юзера в дто - начало
    private RegistrationUserDTO toDto(User user){
        log.info("Перевод юзера в дто");
        return RegistrationUserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .password(user.getPassword())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .roles(user.getRoles())
                .active(user.isActive())
//                .active(user.getActivateCode() == null)
                .build();
    }
    // Перевод юзера в дто - конец

    // Создание нового пользователя "Клиент" - начало
    public User save(RegistrationUserDTO userDto){
        log.info("3. Заходим в создание нового юзера и проверяем совпадение паролей");
        if(!Objects.equals(userDto.getPassword(), userDto.getMatchingPassword())){
            throw new RuntimeException("Пароли не совпадают");
        }
        log.info("4. Создаем юзера");
        User user = User.builder()
                .username(userDto.getUsername())
                .password(passwordEncoder.encode(userDto.getPassword()))
//                .password(userDto.getPassword())
                .email(userDto.getEmail())
                .phoneNumber(
                        changeNumberPhone(userDto.getPhoneNumber())
                )
                .roles((List.of(roleService.getUserRole())))
                .active(true)
                .activateCode(UUID.randomUUID().toString())
                .build();
        log.info("5. Юзер успешно создан");
//        this.save(user);
        return userRepository.save(user);
    }
    // Создание нового пользователя "Клиент" - конец

    public String changeNumberPhone(String phone){
        String[] a;
        a = phone.split("9");
        a[0] = "+79";
        String b = a[0] + a[1];
        System.out.println(b);
        return b;
//        userDto.getPhoneNumber().replaceFirst("8", "+7")
    }

    // Взять одного юзера - начало
    @Override
    public RegistrationUserDTO findById(Long id) {
        log.info("Начинается поиск пользователя по id - начало");
//        return userRepository.findById(id);
        log.info("Начинается поиск пользователя по id - конец");
        return null;
    }
    // Взять одного юзера - конец






//    public User getUserByPrincipal(Principal principal) {
//        if (principal == null) return new User();
//        return userRepository.findByEmail(principal.getName());
//    }





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


//      =====================================SECURITY=======================================================
    //    @Transactional
//    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
//        Optional<User> user = findByUserName(username);
//        if (user.isEmpty()){
//            throw new UsernameNotFoundException("Пользователь не найден");
//        }
//
//        UserDetails userDetailsImpl = new UserDetailsImpl(user.get());
//        System.out.println(userDetailsImpl.getUsername() + "  " +  userDetailsImpl.getPassword() + "   " + userDetailsImpl.getAuthorities());
//        return userDetailsImpl;
//
//    }
    //      =====================================SECURITY - END=======================================================
}
