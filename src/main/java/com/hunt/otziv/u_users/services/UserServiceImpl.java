package com.hunt.otziv.u_users.services;


import com.hunt.otziv.u_users.dto.*;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.repository.ImageRepository;
import com.hunt.otziv.u_users.repository.RoleRepository;
import com.hunt.otziv.u_users.repository.UserRepository;

import com.hunt.otziv.u_users.services.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
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
    private final MarketologService marketologService;
    private final PasswordEncoder passwordEncoder;
    private final ImageRepository imageRepository;

    public UserServiceImpl(UserRepository userRepository, RoleRepository roleRepository, RoleService roleService, OperatorService operatorService, ManagerService managerService, WorkerService workerService, MarketologService marketologService, PasswordEncoder passwordEncoder, ImageRepository imageRepository) {
        this.userRepository = userRepository;
        this.roleService = roleService;
        this.operatorService = operatorService;
        this.managerService = managerService;
        this.workerService = workerService;
        this.marketologService = marketologService;
        this.passwordEncoder = passwordEncoder;
        this.imageRepository = imageRepository;
    }

//      =====================================SECURITY=======================================================


    public Optional<User> findByUserName(String username){ // Метод поиска юзера по имени в БД
        return userRepository.findByUsername(username);
    } // Метод поиска юзера по имени в БД

    @Transactional
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException { // Метод для секьюрити от имплеменитрованного DetailsUsers
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", username)
        ));
        System.out.println(user.getUsername() + user.getPassword()
                + user.getRoles().stream().map(role -> new SimpleGrantedAuthority(role.getName())).toList());
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                user.getRoles().stream().map(role -> new SimpleGrantedAuthority(role.getName())).collect(Collectors.toList())
        );
    } // Метод для секьюрити от имплеменитрованного DetailsUsers

//      =====================================SECURITY - END=======================================================

//      =====================================CREATE USERS - START=======================================================


    @Override
    public List<RegistrationUserDTO> getAllUsers() { // Взять всех юзеров - начало
        log.info("Берем все юзеров");
        return userRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    } // Взять всех юзеров - конец

    private RegistrationUserDTO toDto(User user){ // Перевод юзера в дто - начало
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
                .marketologs(user.getMarketologs() == null ? new HashSet<>() : user.getMarketologs())
                .manager(new Manager())
                .coefficient(user.getCoefficient())
                .image(user.getImage())
                .build();
    } // Перевод юзера в дто - конец

//      =====================================CREATE USERS - START=======================================================

    public User save(RegistrationUserDTO userDto, MultipartFile file) throws IOException { // Создание нового пользователя "Клиент" - начало
        log.info("3. Заходим в создание нового юзера и проверяем совпадение паролей");
        if(!Objects.equals(userDto.getPassword(), userDto.getMatchingPassword())){
            throw new RuntimeException("Пароли не совпадают");
        }
        log.info("4. Создаем юзера");
        User user = User.builder()
                .username(userDto.getUsername())
                .password(passwordEncoder.encode(userDto.getPassword()))
                .fio((userDto.getFio()))
                .email(userDto.getEmail())
                .phoneNumber(changeNumberPhone(userDto.getPhoneNumber()))
                .roles((List.of(roleService.getUserRole())))
                .operators(userDto.getOperators())
                .managers(userDto.getManagers())
                .workers(userDto.getWorkers())
                .marketologs(userDto.getMarketologs())
                .active(true)
                .activateCode(UUID.randomUUID().toString())
                .image(toImageEntity(file))
                .coefficient(new BigDecimal("0.05"))
                .build();
        log.info("5. Юзер успешно создан");
        return userRepository.save(user);
    } // Создание нового пользователя "Клиент" - конец

//      =====================================CREATE USERS - START=======================================================

    // Вспомогательный метод для корректировки номера телефона
    public String changeNumberPhone(String phone){ // Вспомогательный метод для корректировки номера телефона
        String[] a = phone.split("9", 2);
        if (a.length > 1) {
            a[0] = "+79";
            return a[0] + a[1];
        } else {
            return phone;
        }
    } // Вспомогательный метод для корректировки номера телефона

    @Override
    public RegistrationUserDTO findById(Long id) { // Взять одного юзера по Id
        log.info("Начинается поиск пользователя по id - начало");
            User user = userRepository.findById(id).orElseThrow();
        log.info("Начинается поиск пользователя по id - конец");
        return toDto(user);
    } // Взять одного юзера по Id

    @Override
    public List<String> getAllUsersByFio(String roleName) { // Взять одного юзера по названию роли и фио
        return userRepository.findAllActiveFioByRole(roleName);
    } // Взять одного юзера по названию роли и фио

    @Override
    public Optional<User> findByFio(String operator) { // Взять одного юзера по  фио
        return userRepository.findByFio(operator);
    } // Взять одного юзера по  фио

    //      =====================================UPDATE USERS - START=======================================================
    @Override
    @Transactional // Обновление юзера
    public void updateProfile(RegistrationUserDTO userDTO, String role, OperatorDTO operatorDTO, ManagerDTO managerDTO, WorkerDTO workerDTO, MarketologDTO marketologDTO, MultipartFile imageFile) throws IOException {
        log.info("Вошли в обновление");
        String originalFilename = imageFile.getOriginalFilename();

        if (userDTO.getOperators() == null){
            userDTO.setOperators(new HashSet<>());
        }
        if (userDTO.getManagers() == null){
            userDTO.setManagers(new HashSet<>());
        }
        if (userDTO.getWorkers() == null){
            userDTO.setWorkers(new HashSet<>());
        }
        if (userDTO.getMarketologs() == null){
            userDTO.setMarketologs(new HashSet<>());
        }

        /*Ищем пользоваеля, если пользователь не найден, то выбрасываем сообщение с ошибкой*/
        User saveUser = findByUserName(userDTO.getUsername()).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", userDTO.getUsername())));
        log.info("Достали юзера по имени из дто");
        boolean isChanged = false;

        System.out.println(saveUser.getRoles().iterator().next().getName());
        System.out.println(role);

        System.out.println("change Role: " + !Objects.equals(saveUser.getRoles().iterator().next().getName(), role));
        System.out.println("change operators: " + !Objects.equals(userDTO.getOperators(), saveUser.getOperators()));
        System.out.println("change managers: " + !Objects.equals(userDTO.getManagers(), saveUser.getManagers()));
        System.out.println("change workers: " + !Objects.equals(userDTO.getWorkers(), saveUser.getWorkers()));
        System.out.println("change marketologs: " + !Objects.equals(userDTO.getMarketologs(), saveUser.getMarketologs()));
        System.out.println("coefficient: " + !Objects.equals(userDTO.getCoefficient(), saveUser.getCoefficient()));
        System.out.println("image: " + (!imageFile.isEmpty()));

        /*Проверяем не равна ли роль предыдущей, если нет, то меняем флаг на тру*/
        if (!Objects.equals(saveUser.getRoles().iterator().next().getName(), role)){
            log.info("Вошли в обновление роли");
            List<Role> roles = new ArrayList<>();
            roles.add(roleService.getUserRole(role));
            saveUser.setRoles(roles);
            isChanged = true;
            log.info("Обновили роль");
            if (role.equals("ROLE_OPERATOR")){
                log.info("Вошли в удаления из менеджера в операторе");
                managerService.deleteManager(saveUser);
                log.info("Вышли из удаления из менеджера");
                workerService.deleteWorker(saveUser);
                log.info("Вышли из удаления из работника");
                marketologService.deleteMarketolog(saveUser);
                log.info("Вышли из удаления маркетолога в операторе");
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
                marketologService.deleteMarketolog(saveUser);
                log.info("Вышли из удаления маркетолога в менеджере");
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
                marketologService.deleteMarketolog(saveUser);
                log.info("Вышли из удаления маркетолога в работнике");
                workerService.saveNewWorker(saveUser);
                log.info("Создали нового работника");
            }
            if (role.equals("ROLE_MARKETOLOG")){
                workerService.deleteWorker(saveUser);
                log.info("Вышли из удаления из работника");
                managerService.deleteManager(saveUser);
                log.info("Вышли из удаления из менеджера");
                operatorService.deleteOperator(saveUser);
                log.info("Вышли из удаления из оператора");
                marketologService.saveNewMarketolog(saveUser);
                log.info("Создали нового маркетолога");
            }
        }

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
        if (originalFilename != null && (originalFilename.endsWith(".jpg") || originalFilename.endsWith(".png") || originalFilename.endsWith(".jpeg") || originalFilename.endsWith(".webp"))){
            Image imageDelete = saveUser.getImage();
            if (imageDelete != null){
                imageRepository.delete(imageDelete);
            }
            saveUser.setImage(toImageEntity(imageFile));
            isChanged = true;
            log.info("Обновили изображение");
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

        /*Проверяем не равен ли маркетолога предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(userDTO.getMarketologs(), saveUser.getMarketologs()) || marketologDTO.getMarketologId() != 0){
            log.info("зашли в обновление маркетологов");
            System.out.println(userDTO.getMarketologs()==null);
            System.out.println(saveUser.getMarketologs()==null);
            System.out.println(!Objects.equals(userDTO.getMarketologs(), saveUser.getMarketologs()));
            System.out.println(marketologDTO.getMarketologId());
            System.out.println(marketologDTO.getMarketologId() != 0);
            System.out.println((userDTO.getMarketologs()==null && marketologDTO.getMarketologId() != 0));
            log.info("зашли в обновление маркетологов");
            System.out.println(marketologService.getMarketologById(marketologDTO.getMarketologId()));
            userDTO.getMarketologs().add(marketologService.getMarketologById(marketologDTO.getMarketologId()));
            saveUser.setMarketologs(userDTO.getMarketologs());
            isChanged = true;
            log.info("Обновили маркетологов");
        }

        /*Проверяем не равен ли менеджера предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(userDTO.getManagers(), saveUser.getManagers()) || managerDTO.getManagerId() != 0){
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
            userDTO.getWorkers().add(workerService.getWorkerById(workerDTO.getWorkerId()));
            saveUser.setWorkers(userDTO.getWorkers());
            isChanged = true;
            log.info("Обновили специалиста");
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
    } // Обновление юзера


    // Обновить профиль юзера - конец

//      =====================================UPDATE USERS - START=======================================================

    //      =====================================DELETE OPERATOR MANAGER WORKER =======================================================
    @Override
    public void deleteOperator(String username, Long operatorId) { // Удаление оператора
        log.info("1. Вошли в удаление оператора");
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользователь '%s' не найден", username)
        ));
        log.info("2. Нашли юзера");
        Set<Operator> operators = user.getOperators();
        Iterator<Operator> iterator = operators.iterator();
        while (iterator.hasNext()) {
            Operator operator = iterator.next();
            if (operator.getId().equals(operatorId)) {
                iterator.remove();
                break; // Если вы уверены, что operatorId уникален, то можно прервать цикл
            }
        }
        user.setOperators(operators);
        log.info("3. Обновили список операторов");
        userRepository.save(user);
        log.info("4. Сохранили юзера");
    } // Удаление оператора

    @Override
    public void deleteManager(String username, Long managerId) { // Удаление менеджера
        log.info("1. Вошли в удаление менеджера");
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользователь '%s' не найден", username)
        ));
        log.info("2. Нашли юзера");
        Set<Manager> managers = user.getManagers();
        Iterator<Manager> iterator = managers.iterator();
        while (iterator.hasNext()) {
            Manager manager = iterator.next();
            if (manager.getId().equals(managerId)) {
                iterator.remove();
                break; // Если вы уверены, что operatorId уникален, то можно прервать цикл
            }
        }
        user.setManagers(managers);
        log.info("3. Обновили список менеджеров");
        userRepository.save(user);
        log.info("4. Сохранили юзера");
    } // Удаление менеджера

    @Override
    public void deleteWorker(String username, Long workerId) { // Удаление работника
        log.info("1. Вошли в удаление работника");
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользователь '%s' не найден", username)
        ));
        log.info("2. Нашли юзера");
        Set<Worker> workers = user.getWorkers();
        Iterator<Worker> iterator = workers.iterator();
        while (iterator.hasNext()) {
            Worker worker = iterator.next();
            if (worker.getId().equals(workerId)) {
                iterator.remove();
                break; // Если вы уверены, что operatorId уникален, то можно прервать цикл
            }
        }
        user.setWorkers(workers);
        log.info("3. Обновили список работников");
        userRepository.save(user);
        log.info("4. Сохранили юзера");
    } // Удаление работника

    @Override
    public void deleteMarketolog(String username, Long marketologId) { // Удаление маркетолога
        log.info("1. Вошли в удаление маркетолога из списка юзера");
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользователь '%s' не найден", username)
        ));
        log.info("2. Нашли юзера");
        Set<Marketolog> marketologs = user.getMarketologs();
        Iterator<Marketolog> iterator = marketologs.iterator();
        while (iterator.hasNext()) {
            Marketolog marketolog = iterator.next();
            if (marketolog.getId().equals(marketologId)) {
                iterator.remove();
                break; // Если вы уверены, что operatorId уникален, то можно прервать цикл
            }
        }
        user.setMarketologs(marketologs);
        log.info("3. Обновили список работников");
        userRepository.save(user);
        log.info("4. Сохранили юзера");
    } // Удаление маркетолога

    private Image toImageEntity(MultipartFile file) throws IOException { // Перевод картинки в сущность
        System.out.println(file);
        Image image = new Image();
        image.setName(file.getName());
        image.setOriginalFileName(file.getOriginalFilename());
        image.setContentType(file.getContentType());
        image.setSize(file.getSize());
        image.setBytes(file.getBytes());
        imageRepository.save(image);
        return image;
    } // Перевод картинки в сущность


}
