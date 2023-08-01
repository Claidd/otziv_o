package com.hunt.otziv.a_login.services;


import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.Role;
import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.a_login.repository.RoleRepository;
import com.hunt.otziv.a_login.repository.UserRepository;

import com.hunt.otziv.a_login.services.service.UserService;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

//      =====================================SECURITY=======================================================

    // INSERT INTO otziv_o.roles (name) values ('ROLE_WORKER');

    // Метод поиска юзера по имени в БД
    public Optional<User> findByUserName(String username){
        return userRepository.findByUsername(username);
    }

// Метод для секьюрити от имплеменитрованного DetailsUsers
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


    // Обновить профиль юзера - начало
    @Override
    @Transactional
    public void updateProfile(RegistrationUserDTO userDTO, String role) {
        log.info("Вошли в обновление");
        /*Ищем пользоваеля, если пользователь не найден, то выбрасываем сообщение с ошибкой*/
        User saveUser = findByUserName(userDTO.getUsername()).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", userDTO.getUsername())
        ));
        log.info("Достали юзера по имени из дто");
        boolean isChanged = false;

        /*Проверяем не равна ли роль предыдущей, если нет, то меняем флаг на тру*/
        if (!Objects.equals(userDTO.getRoles(), saveUser.getRoles())){
//            saveUser.setRoles(List.of(roleService.getUserRole(role)));
            List<Role> roles = new ArrayList<>();
            roles.add(roleService.getUserRole(role));
            saveUser.setRoles(roles);
            isChanged = true;
            log.info("Обновили роль");
        }
        /*Проверяем, не равен ли пароль предыдущему */
//        if (userDTO.getPassword() != null && !userDTO.getPassword().isEmpty()){
//            saveUser.setPassword(passwordEncoder.encode(userDTO.getPassword()));
//            isChanged = true;
//        }
        /*Проверяем не равен ли мейл предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(userDTO.getEmail(), saveUser.getEmail())){
            saveUser.setEmail(userDTO.getEmail());
            isChanged = true;
            log.info("Обновили мейл");
        }
        /*Проверяем не равен ли мейл предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(userDTO.isActive(), saveUser.isActive())){
            saveUser.setActive(userDTO.isActive());
            System.out.println(userDTO.isActive());
            isChanged = true;
            log.info("Обновили активность");
        }
        /*если какое-то изменение было и флаг сменился на тру, то только тогда мы изменяем запись в БД
         * А если нет, то и обращаться к базе данны и грузить ее мы не будем*/
        if  (isChanged){
            log.info("Начали сохранять обновленного юзера в БД");
            userRepository.save(saveUser);
            log.info("Сохранили обновленного юзера в БД");
        }
        else {
            log.info("Изменений не было, сущность в БД не изменена");
        }
    }
    // Обновить профиль юзера - конец


    // Перевод юзера в дто - начало
    private RegistrationUserDTO toDto(User user){
        log.info("Перевод юзера в дто");
        return RegistrationUserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .password(user.getPassword())
                .fio(user.getFio())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .roles(user.getRoles())
                .active(user.isActive())
                .createTime(user.getCreateTime())
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
                .fio((userDto.getFio()))
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

    // Вспомогательный метод для корректировки номера телефона
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
            User user = userRepository.findById(id).orElseThrow();
        log.info("Начинается поиск пользователя по id - конец");
        return toDto(user);
    }

    @Override
    public List<String> getAllUsersByFio(String roleName) {
        return userRepository.findAllActiveFioByRole(roleName);
    }
    // Взять одного юзера - конец

    // Взять одного юзера - начало















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
