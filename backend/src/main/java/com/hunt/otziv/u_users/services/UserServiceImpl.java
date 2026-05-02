package com.hunt.otziv.u_users.services;


import com.hunt.otziv.u_users.dto.*;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.repository.ImageRepository;
import com.hunt.otziv.u_users.repository.RoleRepository;
import com.hunt.otziv.u_users.repository.UserRepository;

import com.hunt.otziv.u_users.services.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleService roleService;
    private final OperatorService operatorService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final MarketologService marketologService;
    private final PasswordEncoder passwordEncoder;
    private final ImageRepository imageRepository;

    // ===================================== SECURITY =====================================

    @Override
    public Optional<User> findByUserName(String username) {
        return userRepository.findByUsername(username);
    }

    private User findByUserNameWithAssignments(String username) {
        return userRepository.findByUsernameWithAssignments(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Пользователь '%s' не найден", username)
                ));
    }

    @Override
    public List<User> getAllOwners(String roleName) {
        return userRepository.findAllOwners(roleName);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользователь '%s' не найден", username)
        ));

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                user.getRoles().stream()
                        .map(role -> new SimpleGrantedAuthority(role.getName()))
                        .collect(Collectors.toList())
        );
    }

    /**
     * Легкий сбор всех userId, относящихся к owner:
     * - userId менеджеров владельца
     * - userId работников этих менеджеров
     * - userId операторов этих менеджеров
     * - userId маркетологов этих менеджеров
     *
     * Без загрузки полных entity-графов workers/operators/marketologs.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<Long> findAllRelevantUserIdsForOwner(Set<Manager> managers) {
        if (managers == null || managers.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Long> managerIds = managers.stream()
                .filter(Objects::nonNull)
                .map(Manager::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (managerIds.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Long> result = new HashSet<>();

        List<Long> managerUserIds = managerService.findUserIdsByManagerIds(managerIds);
        if (managerUserIds != null) {
            result.addAll(managerUserIds);
        }

        List<Long> workerUserIds = workerService.findUserIdsByManagerIds(managerIds);
        if (workerUserIds != null) {
            result.addAll(workerUserIds);
        }

        List<Long> operatorUserIds = operatorService.findUserIdsByManagerIds(managerIds);
        if (operatorUserIds != null) {
            result.addAll(operatorUserIds);
        }

        List<Long> marketologUserIds = marketologService.findUserIdsByManagerIds(managerIds);
        if (marketologUserIds != null) {
            result.addAll(marketologUserIds);
        }

        return result;
    }


    @Override
    @Transactional(readOnly = true)
    public List<Long> findManagerIdsByUserId(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return userRepository.findManagerIdsByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findAllRelevantUserIdsForManagerIds(List<Long> managerIds) {
        if (managerIds == null || managerIds.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<Long> result = new LinkedHashSet<>();

        result.addAll(userRepository.findManagerUserIdsByManagerIds(managerIds));
        result.addAll(userRepository.findWorkerUserIdsByManagerIds(managerIds));
        result.addAll(userRepository.findOperatorUserIdsByManagerIds(managerIds));
        result.addAll(userRepository.findMarketologUserIdsByManagerIds(managerIds));

        return new ArrayList<>(result);
    }
    // ===================================== CREATE USERS =====================================

    @Override
    public List<RegistrationUserDTO> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private RegistrationUserDTO toDto(User user) {
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
    }

    @Override
    public User save(RegistrationUserDTO userDto, MultipartFile file) throws IOException {
        log.info("3. Заходим в создание нового юзера и проверяем совпадение паролей");

        if (!Objects.equals(userDto.getPassword(), userDto.getMatchingPassword())) {
            throw new RuntimeException("Пароли не совпадают");
        }

        log.info("4. Создаем юзера");

        User user = User.builder()
                .username(userDto.getUsername())
                .password(passwordEncoder.encode(userDto.getPassword()))
                .fio(userDto.getFio())
                .email(userDto.getEmail())
                .phoneNumber(changeNumberPhone(userDto.getPhoneNumber()))
                .roles(new ArrayList<>(List.of(roleService.getUserRole())))
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
    }

    public String changeNumberPhone(String phone) {
        String[] a = phone.split("9", 2);
        if (a.length > 1) {
            a[0] = "+79";
            return a[0] + a[1];
        } else {
            return phone;
        }
    }

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

    // ===================================== UPDATE USERS =====================================

    @Override
    @Transactional
    public void updateProfile(
            RegistrationUserDTO userDTO,
            String role,
            OperatorDTO operatorDTO,
            ManagerDTO managerDTO,
            WorkerDTO workerDTO,
            MarketologDTO marketologDTO,
            MultipartFile imageFile
    ) throws IOException {
        log.info("Вошли в обновление");

        String originalFilename = imageFile != null ? imageFile.getOriginalFilename() : null;

        if (userDTO.getOperators() == null) {
            userDTO.setOperators(new HashSet<>());
        }
        if (userDTO.getManagers() == null) {
            userDTO.setManagers(new HashSet<>());
        }
        if (userDTO.getWorkers() == null) {
            userDTO.setWorkers(new HashSet<>());
        }
        if (userDTO.getMarketologs() == null) {
            userDTO.setMarketologs(new HashSet<>());
        }

        User saveUser = findByUserNameWithAssignments(userDTO.getUsername());
        log.info("Достали юзера по имени из дто");

        boolean isChanged = false;

        String currentRole = saveUser.getRoles().iterator().next().getName();

        if (!Objects.equals(currentRole, role)) {
            log.info("Вошли в обновление роли");

            List<Role> roles = new ArrayList<>();
            roles.add(roleService.getUserRole(role));
            saveUser.setRoles(roles);
            isChanged = true;

            log.info("Обновили роль");

            if (role.equals("ROLE_OPERATOR")) {
                managerService.deleteManager(saveUser);
                workerService.deleteWorker(saveUser);
                marketologService.deleteMarketolog(saveUser);
                operatorService.saveNewOperator(saveUser);
            }

            if (role.equals("ROLE_MANAGER")) {
                workerService.deleteWorker(saveUser);
                operatorService.deleteOperator(saveUser);
                marketologService.deleteMarketolog(saveUser);
                managerService.saveNewManager(saveUser);
            }

            if (role.equals("ROLE_WORKER")) {
                managerService.deleteManager(saveUser);
                operatorService.deleteOperator(saveUser);
                marketologService.deleteMarketolog(saveUser);
                workerService.saveNewWorker(saveUser);
            }

            if (role.equals("ROLE_MARKETOLOG")) {
                workerService.deleteWorker(saveUser);
                managerService.deleteManager(saveUser);
                operatorService.deleteOperator(saveUser);
                marketologService.saveNewMarketolog(saveUser);
            }
        }

        if (!Objects.equals(userDTO.getEmail(), saveUser.getEmail())) {
            saveUser.setEmail(userDTO.getEmail());
            isChanged = true;
            log.info("Обновили мейл");
        }

        if (!Objects.equals(userDTO.getPhoneNumber(), saveUser.getPhoneNumber())) {
            saveUser.setPhoneNumber(userDTO.getPhoneNumber());
            isChanged = true;
            log.info("Обновили телефон");
        }

        if (!Objects.equals(userDTO.getUsername(), saveUser.getUsername())) {
            saveUser.setUsername(userDTO.getUsername());
            isChanged = true;
            log.info("Обновили имя");
        }

        if (!Objects.equals(userDTO.isActive(), saveUser.isActive())) {
            saveUser.setActive(userDTO.isActive());
            isChanged = true;
            log.info("Обновили активность");
        }

        if (originalFilename != null
                && (originalFilename.endsWith(".jpg")
                || originalFilename.endsWith(".png")
                || originalFilename.endsWith(".jpeg")
                || originalFilename.endsWith(".webp"))) {

            Image imageDelete = saveUser.getImage();
            if (imageDelete != null) {
                imageRepository.delete(imageDelete);
            }

            saveUser.setImage(toImageEntity(imageFile));
            isChanged = true;
            log.info("Обновили изображение");
        }

        if (!Objects.equals(userDTO.getCoefficient(), saveUser.getCoefficient())) {
            if (userDTO.getCoefficient().compareTo(new BigDecimal("0.30")) <= 0) {
                saveUser.setCoefficient(userDTO.getCoefficient());
                isChanged = true;
                log.info("Обновили коэффициент");
            }
        }

        if (!Objects.equals(userDTO.getOperators(), saveUser.getOperators()) || operatorDTO.getOperatorId() != 0) {
            log.info("Зашли в обновление операторов");

            Set<Operator> updatedOperators = new HashSet<>(userDTO.getOperators());
            if (operatorDTO.getOperatorId() != 0) {
                updatedOperators.add(operatorService.getOperatorById(operatorDTO.getOperatorId()));
            }

            saveUser.setOperators(updatedOperators);
            isChanged = true;
            log.info("Обновили операторов");
        }

        if (!Objects.equals(userDTO.getMarketologs(), saveUser.getMarketologs()) || marketologDTO.getMarketologId() != 0) {
            log.info("Зашли в обновление маркетологов");

            Set<Marketolog> updatedMarketologs = new HashSet<>(userDTO.getMarketologs());
            if (marketologDTO.getMarketologId() != 0) {
                updatedMarketologs.add(marketologService.getMarketologById(marketologDTO.getMarketologId()));
            }

            saveUser.setMarketologs(updatedMarketologs);
            isChanged = true;
            log.info("Обновили маркетологов");
        }

        if (!Objects.equals(userDTO.getManagers(), saveUser.getManagers()) || managerDTO.getManagerId() != 0) {
            log.info("Зашли в обновление менеджеров");

            Set<Manager> existingManagers = saveUser.getManagers();
            if (existingManagers == null) {
                existingManagers = new HashSet<>();
            }

            if (managerDTO.getManagerId() != 0) {
                existingManagers.add(managerService.getManagerById(managerDTO.getManagerId()));
            } else if (userDTO.getManagers().size() == 1 && userDTO.getManager() != null && userDTO.getManager().getId() != null) {
                Set<Manager> newManagerList = new HashSet<>();
                newManagerList.add(managerService.getManagerById(userDTO.getManager().getId()));
                existingManagers = newManagerList;
            } else {
                existingManagers.addAll(userDTO.getManagers());
            }

            saveUser.setManagers(existingManagers);
            isChanged = true;
            log.info("Обновили менеджеров");
        }

        if (!Objects.equals(userDTO.getWorkers(), saveUser.getWorkers()) || workerDTO.getWorkerId() != 0) {
            log.info("Зашли в обновление работников");

            Set<Worker> updatedWorkers = new HashSet<>(userDTO.getWorkers());
            if (workerDTO.getWorkerId() != 0) {
                updatedWorkers.add(workerService.getWorkerById(workerDTO.getWorkerId()));
            }

            saveUser.setWorkers(updatedWorkers);
            isChanged = true;
            log.info("Обновили работников");
        }

        if (isChanged) {
            log.info("Начали сохранять обновленного юзера в БД");
            userRepository.save(saveUser);
            log.info("Сохранили обновленного юзера в БД");
        } else {
            log.info("Изменений не было, сущность в БД не изменена");
        }
    }

    // ===================================== DELETE LINKS =====================================

    @Override
    @Transactional
    public void deleteOperator(String username, Long operatorId) {
        log.info("1. Вошли в удаление оператора");

        User user = findByUserNameWithAssignments(username);
        log.info("2. Нашли юзера");

        Set<Operator> operators = user.getOperators();
        Iterator<Operator> iterator = operators.iterator();

        while (iterator.hasNext()) {
            Operator operator = iterator.next();
            if (operator.getId().equals(operatorId)) {
                iterator.remove();
                break;
            }
        }

        user.setOperators(operators);
        log.info("3. Обновили список операторов");

        userRepository.save(user);
        log.info("4. Сохранили юзера");
    }

    @Override
    @Transactional
    public void deleteManager(String username, Long managerId) {
        log.info("1. Вошли в удаление менеджера");

        User user = findByUserNameWithAssignments(username);
        log.info("2. Нашли юзера");

        Set<Manager> managers = user.getManagers();
        Iterator<Manager> iterator = managers.iterator();

        while (iterator.hasNext()) {
            Manager manager = iterator.next();
            if (manager.getId().equals(managerId)) {
                iterator.remove();
                break;
            }
        }

        user.setManagers(managers);
        log.info("3. Обновили список менеджеров");

        userRepository.save(user);
        log.info("4. Сохранили юзера");
    }

    @Override
    @Transactional
    public void deleteWorker(String username, Long workerId) {
        log.info("1. Вошли в удаление работника");

        User user = findByUserNameWithAssignments(username);
        log.info("2. Нашли юзера");

        Set<Worker> workers = user.getWorkers();
        Iterator<Worker> iterator = workers.iterator();

        while (iterator.hasNext()) {
            Worker worker = iterator.next();
            if (worker.getId().equals(workerId)) {
                iterator.remove();
                break;
            }
        }

        user.setWorkers(workers);
        log.info("3. Обновили список работников");

        userRepository.save(user);
        log.info("4. Сохранили юзера");
    }

    @Override
    @Transactional
    public void deleteMarketolog(String username, Long marketologId) {
        log.info("1. Вошли в удаление маркетолога из списка юзера");

        User user = findByUserNameWithAssignments(username);
        log.info("2. Нашли юзера");

        Set<Marketolog> marketologs = user.getMarketologs();
        Iterator<Marketolog> iterator = marketologs.iterator();

        while (iterator.hasNext()) {
            Marketolog marketolog = iterator.next();
            if (marketolog.getId().equals(marketologId)) {
                iterator.remove();
                break;
            }
        }

        user.setMarketologs(marketologs);
        log.info("3. Обновили список маркетологов");

        userRepository.save(user);
        log.info("4. Сохранили юзера");
    }

    @Override
    public void save(User user) {
        userRepository.save(user);
    }

    @Override
    public Optional<User> findByChatId(long chatId) {
        return userRepository.findByTelegramChatId(chatId);
    }

    @Override
    public User findByIdToUserInfo(Long staticFor) {
        return userRepository.findById(staticFor).orElseThrow();
    }

    @Override
    public Map<String, Long> getAllWorkers() {
        List<Object[]> result = userRepository.getAllWorkersByRole();

        return result.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]
                ));
    }

    private Image toImageEntity(MultipartFile file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Thumbnails.of(file.getInputStream())
                .size(512, 512)
                .crop(Positions.CENTER)
                .outputFormat("jpg")
                .outputQuality(0.7)
                .toOutputStream(baos);

        Image image = new Image();
        image.setName(file.getName());
        image.setOriginalFileName(file.getOriginalFilename());
        image.setContentType(file.getContentType());
        image.setSize((long) baos.size());
        image.setBytes(baos.toByteArray());

        imageRepository.save(image);
        return image;
    }
}
