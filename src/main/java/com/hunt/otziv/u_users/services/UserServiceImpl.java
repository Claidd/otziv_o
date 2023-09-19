package com.hunt.otziv.u_users.services;


import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.OperatorDTO;
import com.hunt.otziv.u_users.dto.RegistrationUserDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.repository.RoleRepository;
import com.hunt.otziv.u_users.repository.UserRepository;

import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserServiceImpl  implements UserService {
    private final UserRepository userRepository;
    private final RoleService roleService;
    private final OperatorService operatorService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, RoleRepository roleRepository, RoleService roleService, OperatorService operatorService, ManagerService managerService, WorkerService workerService, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleService = roleService;
        this.operatorService = operatorService;
        this.managerService = managerService;
        this.workerService = workerService;
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



    // Перевод юзера в дто - начало
    private RegistrationUserDTO toDto(User user){
//        List<SubCategoryDTO> subCategoryDTOList = subCategoryRepository.findAllByCategory(category.getId()).stream()
//                .map(subCategory -> new SubCategoryDTO(subCategory.getId(), subCategory.getSubCategoryTitle(), null))
//                .collect(Collectors.toList());
//        categoryDTO.setSubCategories(subCategoryDTOList);
        System.out.println(user.getOperators());
        System.out.println(user.getManagers());
        System.out.println(user.getWorkers());
//        for (Operator operator:user.getOperators()) {
//            System.out.println(operator.getUser());
//        }
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
                .operators(user.getOperators() == null ? new HashSet<>() : user.getOperators())
                .managers(user.getManagers() == null ? new HashSet<>() : user.getManagers())
                .workers(user.getWorkers() == null ? new HashSet<>() : user.getWorkers())
                .manager(new Manager())
                .coefficient(user.getCoefficient())
                .build();
        //                .active(user.getActivateCode() == null)
    }
    // Перевод юзера в дто - конец
//      =====================================CREATE USERS - START=======================================================
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
                .operators(userDto.getOperators())
                .managers(userDto.getManagers())
                .workers(userDto.getWorkers())
//                .managerId(new HashSet<>())
//                .workerId(new HashSet<>())
                .active(true)
                .activateCode(UUID.randomUUID().toString())
                .build();
        log.info("5. Юзер успешно создан");
//        this.save(user);
        return userRepository.save(user);
    }
    // Создание нового пользователя "Клиент" - конец
//      =====================================CREATE USERS - START=======================================================

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

    @Override
    public Optional<User> findByFio(String operator) {
        return userRepository.findByFio(operator);
    }
    // Взять одного юзера - конец

    //      =====================================UPDATE USERS - START=======================================================
    // Обновить профиль юзера - начало
    @Override
    @Transactional
    public void updateProfile(RegistrationUserDTO userDTO, String role, OperatorDTO operatorDTO, ManagerDTO managerDTO, WorkerDTO workerDTO) {
        log.info("Вошли в обновление");

        if (userDTO.getOperators() == null){
            userDTO.setOperators(new HashSet<>());
        }
        if (userDTO.getManagers() == null){
            userDTO.setManagers(new HashSet<>());
        }
        if (userDTO.getWorkers() == null){
            userDTO.setWorkers(new HashSet<>());
        }

        /*Ищем пользоваеля, если пользователь не найден, то выбрасываем сообщение с ошибкой*/
        User saveUser = findByUserName(userDTO.getUsername()).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", userDTO.getUsername())
        ));
        log.info("Достали юзера по имени из дто");
        boolean isChanged = false;

        System.out.println(!Objects.equals(userDTO.getOperators(), saveUser.getOperators()));
        System.out.println(!Objects.equals(userDTO.getManagers(), saveUser.getManagers()));
        System.out.println(!Objects.equals(userDTO.getWorkers(), saveUser.getWorkers()));
        System.out.println("coefficient: " + !Objects.equals(userDTO.getCoefficient(), saveUser.getCoefficient()));
        System.out.println(userDTO.getCoefficient());
        System.out.println(saveUser.getCoefficient());


        /*Проверяем не равна ли роль предыдущей, если нет, то меняем флаг на тру*/
        if (!Objects.equals(userDTO.getRoles(), saveUser.getRoles())){
            log.info("Вошли в обновление роли");
            System.out.println(userDTO.getRoles());
            System.out.println(saveUser.getRoles());
//            saveUser.setRoles(List.of(roleService.getUserRole(role)));
            List<Role> roles = new ArrayList<>();
            roles.add(roleService.getUserRole(role));
            saveUser.setRoles(roles);
            isChanged = true;
            log.info("Обновили роль");
//            if (role.equals("ROLE_ADMIN")){
//                return;
//            }
            if (role.equals("ROLE_CALLING")){
                log.info("Вошли в удаления из менеджера в операторе");
                managerService.deleteManager(saveUser);
                log.info("Вышли из удаления из менеджера");
                workerService.deleteWorker(saveUser);
                log.info("Вышли из удаления из работника");
                operatorService.saveNewOperator(saveUser);
                log.info("Создали нового оператора");
            }
            if (role.equals("ROLE_MANAGER")){
                log.info("Вошли в удаления работника в менеджере");
                workerService.deleteWorker(saveUser);
                log.info("Вышли из удаления из работника");
                log.info("Вошли в удаления оператора в менеджере");
                operatorService.deleteOperator(saveUser);
                log.info("Вышли из удаления из оператора в менеджере");
                managerService.saveNewManager(saveUser);
                log.info("Создали нового менеджера");
            }
            if (role.equals("ROLE_WORKER")){
                log.info("Вошли в удаления менеджера в работнике");
                managerService.deleteManager(saveUser);
                log.info("Вышли из удаления менеджера в работнике");
                log.info("Вошли в удаления оператора в работнике");
                operatorService.deleteOperator(saveUser);
                log.info("Вышли из удаление оператора в работнике");
                log.info("Переходим в создание нового работника");
                workerService.saveNewWorker(saveUser);
                log.info("Создали нового работника");
            }
            if (role.equals("ROLE_CLIENT")){
                workerService.deleteWorker(saveUser);
                log.info("Вышли из удаления из работника");
                managerService.deleteManager(saveUser);
                log.info("Вышли из удаления из менеджера");
                operatorService.deleteOperator(saveUser);
                log.info("Вышли из удаления из оператора");
            }
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
        if (!Objects.equals(userDTO.getPhoneNumber(), saveUser.getPhoneNumber())){
            saveUser.setPhoneNumber(userDTO.getPhoneNumber());
            isChanged = true;
            log.info("Обновили телефон");
        }
        /*Проверяем не равен ли мейл предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(userDTO.isActive(), saveUser.isActive())){
            saveUser.setActive(userDTO.isActive());
            System.out.println(userDTO.isActive());
            isChanged = true;
            log.info("Обновили активность");
        }
        /*Проверяем не равен ли мейл предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(userDTO.getCoefficient(), saveUser.getCoefficient())){
            if (userDTO.getCoefficient().compareTo(new BigDecimal("0.30")) < 0){
                saveUser.setCoefficient(userDTO.getCoefficient());
                System.out.println(userDTO.getCoefficient());
                isChanged = true;
                log.info("Обновили коэффициент");
            }
        }
        /*Проверяем не равен ли оператор предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(userDTO.getOperators(), saveUser.getOperators()) || operatorDTO.getOperatorId() != 0){
            log.info("зашли в обновление операторов");
            System.out.println(userDTO.getOperators()==null);
            System.out.println(saveUser.getOperators()==null);
            System.out.println(!Objects.equals(userDTO.getOperators(), saveUser.getOperators()));
            System.out.println(operatorDTO.getOperatorId());
            System.out.println(operatorDTO.getOperatorId() != 0);
            System.out.println((userDTO.getOperators()==null && operatorDTO.getOperatorId() != 0));
            log.info("зашли в обновление операторов");
            System.out.println(operatorService.getOperatorById(operatorDTO.getOperatorId()));
            userDTO.getOperators().add(operatorService.getOperatorById(operatorDTO.getOperatorId()));
            saveUser.setOperators(userDTO.getOperators());
            isChanged = true;
            log.info("Обновили операторов");
        }

        /*Проверяем не равен ли менеджера предыдущему, если нет, то меняем флаг на тру*/
        if (Objects.equals(userDTO.getManagers(), saveUser.getManagers()) || managerDTO.getManagerId() != 0){
            System.out.println(userDTO.getManagers()==null);
            System.out.println(saveUser.getManagers()==null);
            System.out.println(!Objects.equals(userDTO.getManagers(), saveUser.getManagers()));
            System.out.println(managerDTO.getManagerId());
            System.out.println(managerDTO.getManagerId() != 0);
            System.out.println((userDTO.getManagers()==null && managerDTO.getManagerId() != 0));
            System.out.println(userDTO.getManagers());
            log.info("зашли в обновление менеджеров");
//            System.out.println(managerService.getManagerById(userDTO.getManager().getId()));


            if (managerDTO.getManagerId() != 0){
//        Проверка есть ли уже какие-то менеджеры, если да, то добавляем, если нет то загружаем новый список
                Set<Manager> existingManagers = saveUser.getManagers(); // пытаемся получить текущий список филиалов из компании
                if (existingManagers == null) {
                    existingManagers = new HashSet<>();// если он пустой, то создаем новый set
                }
                Set<Manager> newManager = new HashSet<>();
                newManager.add(managerService.getManagerById(managerDTO.getManagerId()));// берем список из дто
                existingManagers.addAll(newManager); // объединяем эти списки
                saveUser.setManagers(existingManagers); // устанавливаем компании объединенный список
                //        Проверка есть ли уже какие-то филиалы, если да, то добавляем, если нет то загружаем новый список
            }
            else if (userDTO.getManagers().size() == 1) {
                Set<Manager> newManagerList2 = new HashSet<>();
                newManagerList2.add(managerService.getManagerById(userDTO.getManager().getId()));
                saveUser.setManagers(newManagerList2);
            }
            else {
                //        Проверка есть ли уже какие-то менеджеры, если да, то добавляем, если нет то загружаем новый список
                Set<Manager> existingManagers = saveUser.getManagers(); // пытаемся получить текущий список филиалов из компании
                if (existingManagers == null) {
                    existingManagers = new HashSet<>();// если он пустой, то создаем новый set
                }
                Set<Manager> newManager = userDTO.getManagers(); // берем список из дто
                existingManagers.addAll(newManager); // объединяем эти списки
                saveUser.setManagers(existingManagers); // устанавливаем компании объединенный список
                //        Проверка есть ли уже какие-то филиалы, если да, то добавляем, если нет то загружаем новый список
            }
//            userDTO.getManagers().add(managerService.getManagerById(managerDTO.getManagerId()) == null ? managerService.getManagerById(userDTO.getManager().getId()) : managerService.getManagerById(managerDTO.getManagerId()));
//            System.out.println(userDTO.getManagers());
//            saveUser.setManagers(userDTO.getManagers());
            isChanged = true;
            log.info("Обновили менеджера");
        }
        /*Проверяем не равен ли специалист предыдущему, если нет, то меняем флаг на тру*/

        if (!Objects.equals(userDTO.getWorkers(), saveUser.getWorkers()) || workerDTO.getWorkerId() != 0){
            System.out.println(saveUser.getWorkers()==null);
            System.out.println(userDTO.getWorkers()==null);
            System.out.println(!Objects.equals(userDTO.getWorkers(), saveUser.getWorkers()));
            System.out.println(workerDTO.getWorkerId());
            System.out.println(workerDTO.getWorkerId() != 0);
            System.out.println((userDTO.getWorkers()==null && workerDTO.getWorkerId() != 0));
            log.info("зашли в обновление работников");
//            System.out.println(workerService.getWorkerById(workerDTO.getId()));
            userDTO.getWorkers().add(workerService.getWorkerById(workerDTO.getWorkerId()));
            saveUser.setWorkers(userDTO.getWorkers());
            isChanged = true;
            log.info("Обновили специалиста");
        }

//        if (userDTO.getOperators().isEmpty() && userDTO.)
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

//      =====================================UPDATE USERS - START=======================================================

    //      =====================================DELETE OPERATOR MANAGER WORKER =======================================================
    @Override
    public void deleteOperator(String username, Long operatorId) {
        log.info("1. Вошли в удаление оператора");
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", username)
        ));
        log.info("2. Нашли юзера");
        Set<Operator> operators = user.getOperators();
        operators.remove(operatorService.getOperatorById(operatorId));

        user.setOperators(operators);
        log.info("3. Обновили список операторов");
        userRepository.save(user);
        log.info("4. Сохранили юзера");
    }

    @Override
    public void deleteManager(String username, Long managerId) {
        log.info("1. Вошли в удаление менеджера");
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", username)
        ));
        log.info("2. Нашли юзера");
        Set<Manager> managers = user.getManagers();
        managers.remove(managerService.getManagerById(managerId));

        user.setManagers(managers);
        log.info("3. Обновили список менеджеров");
        userRepository.save(user);
        log.info("4. Сохранили юзера");
    }

    @Override
    public void deleteWorker(String username, Long workerId) {
        log.info("1. Вошли в удаление работника");
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", username)
        ));
        log.info("2. Нашли юзера");
        Set<Worker> workers = user.getWorkers();
        workers.remove(workerService.getWorkerById(workerId));

        user.setWorkers(workers);
        log.info("3. Обновили список работников");
        userRepository.save(user);
        log.info("4. Сохранили юзера");
    }


}
