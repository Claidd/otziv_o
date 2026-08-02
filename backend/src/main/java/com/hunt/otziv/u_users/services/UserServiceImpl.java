package com.hunt.otziv.u_users.services;


import com.hunt.otziv.u_users.dto.*;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.repository.ImageRepository;
import com.hunt.otziv.u_users.repository.RoleRepository;
import com.hunt.otziv.u_users.repository.UserRepository;

import com.hunt.otziv.u_users.services.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String DUMMY_LEGACY_PASSWORD_HASH =
            "$2a$10$pAtWIeKHPxl4coXbwqB0pebfkpcgJ3QhKGXItwmaBYQiKbvSWII0y";

    private final UserRepository userRepository;
    private final RoleService roleService;
    private final OperatorService operatorService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final MarketologService marketologService;
    private final PasswordEncoder passwordEncoder;
    private final ImageRepository imageRepository;
    private final ImageService imageService;
    private final UserAuthEpochService authEpochService;
    private final KeycloakAdminClient keycloakAdminClient;

    // ===================================== SECURITY =====================================

    @Override
    public Optional<User> findByUserName(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByUserNameWithAssignments(String username) {
        return userRepository.findByUsernameWithAssignments(username);
    }

    private User requireUserWithAssignments(String username) {
        return userRepository.findByUsernameWithAssignments(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Пользователь '%s' не найден", username)
                ));
    }

    private User requireLockedUserWithAssignments(String username) {
        userRepository.lockByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Пользователь '%s' не найден", username)
                ));
        return requireUserWithAssignments(username);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Manager> findManagersByUserName(String username) {
        return userRepository.findManagersWithTeamByUsername(username);
    }

    @Override
    @Transactional(readOnly = true)
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
                user.getPassword() == null || user.getPassword().isBlank()
                        ? DUMMY_LEGACY_PASSWORD_HASH
                        : user.getPassword(),
                user.isActive(),
                true,
                true,
                true,
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
    @Transactional(readOnly = true)
    public List<RegistrationUserDTO> getAllUsers() {
        return userRepository.findAllForAdminList().stream()
                .map(user -> toDto(user, false))
                .collect(Collectors.toList());
    }

    private RegistrationUserDTO toDto(User user, boolean includeImage) {
        Collection<Role> roles = user.getRoles() == null ? List.of() : user.getRoles();
        roles.forEach(Role::getName);

        Set<Operator> operators = initializedOperators(user.getOperators());
        Set<Manager> managers = initializedManagers(user.getManagers());
        Set<Worker> workers = initializedWorkers(user.getWorkers());
        Set<Marketolog> marketologs = initializedMarketologs(user.getMarketologs());

        return RegistrationUserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .password("")
                .fio(user.getFio())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .roles(roles)
                .active(user.isActive())
                .createTime(user.getCreateTime())
                .operators(operators)
                .managers(managers)
                .workers(workers)
                .marketologs(marketologs)
                .manager(new Manager())
                .coefficient(user.getCoefficient())
                .image(includeImage ? user.getImage() : null)
                .build();
    }

    private Set<Operator> initializedOperators(Set<Operator> operators) {
        if (operators == null) {
            return new HashSet<>();
        }
        operators.forEach(operator -> touchUser(operator.getUser()));
        return new HashSet<>(operators);
    }

    private Set<Manager> initializedManagers(Set<Manager> managers) {
        if (managers == null) {
            return new HashSet<>();
        }
        managers.forEach(manager -> {
            touchUser(manager.getUser());
            if (manager.getUser() != null && manager.getUser().getWorkers() != null) {
                manager.getUser().getWorkers().forEach(worker -> touchUser(worker.getUser()));
            }
        });
        return new HashSet<>(managers);
    }

    private Set<Worker> initializedWorkers(Set<Worker> workers) {
        if (workers == null) {
            return new HashSet<>();
        }
        workers.forEach(worker -> touchUser(worker.getUser()));
        return new HashSet<>(workers);
    }

    private Set<Marketolog> initializedMarketologs(Set<Marketolog> marketologs) {
        if (marketologs == null) {
            return new HashSet<>();
        }
        marketologs.forEach(marketolog -> touchUser(marketolog.getUser()));
        return new HashSet<>(marketologs);
    }

    private void touchUser(User user) {
        if (user == null) {
            return;
        }
        user.getId();
        user.getUsername();
        user.getFio();
        user.getImageId();
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
    @Transactional(readOnly = true)
    public RegistrationUserDTO findById(Long id) {
        log.info("Начинается поиск пользователя по id - начало");
        User user = userRepository.findByIdWithAssignments(id).orElseThrow();
        log.info("Начинается поиск пользователя по id - конец");
        return toDto(user, true);
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

        User saveUser = requireLockedUserWithAssignments(userDTO.getUsername());
        log.info("Достали юзера по имени из дто");

        String requestedRole = canonicalLocalRole(role);
        requireAdminForLegacyAdminMutation(saveUser, requestedRole);

        boolean isChanged = false;
        boolean securityRoleChanged = false;
        boolean activeChanged = false;

        boolean requestedRoleAlreadyAssigned = saveUser.getRoles() != null
                && saveUser.getRoles().stream()
                .map(Role::getName)
                .filter(Objects::nonNull)
                .anyMatch(requestedRole::equalsIgnoreCase);

        if (!requestedRoleAlreadyAssigned) {
            log.info("Вошли в обновление роли");

            List<Role> roles = new ArrayList<>();
            roles.add(roleService.getUserRole(requestedRole));
            saveUser.setRoles(roles);
            isChanged = true;
            securityRoleChanged = true;

            log.info("Обновили роль");

            if (requestedRole.equals("ROLE_OPERATOR")) {
                managerService.deleteManager(saveUser);
                workerService.deleteWorker(saveUser);
                marketologService.deleteMarketolog(saveUser);
                operatorService.saveNewOperator(saveUser);
            }

            if (requestedRole.equals("ROLE_MANAGER")) {
                workerService.deleteWorker(saveUser);
                operatorService.deleteOperator(saveUser);
                marketologService.deleteMarketolog(saveUser);
                managerService.saveNewManager(saveUser);
            }

            if (requestedRole.equals("ROLE_WORKER")) {
                managerService.deleteManager(saveUser);
                operatorService.deleteOperator(saveUser);
                marketologService.deleteMarketolog(saveUser);
                workerService.saveNewWorker(saveUser);
            }

            if (requestedRole.equals("ROLE_MARKETOLOG")) {
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
            activeChanged = true;
            log.info("Обновили активность");
        }

        if (imageFile != null && !imageFile.isEmpty()) {
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
            if (activeChanged) {
                if (saveUser.isActive()) {
                    authEpochService.reactivated(saveUser);
                } else {
                    authEpochService.deactivated(saveUser);
                }
            } else if (securityRoleChanged) {
                authEpochService.securityRolesChanged(saveUser);
            }
            log.info("Начали сохранять обновленного юзера в БД");
            userRepository.save(saveUser);
            log.info("Сохранили обновленного юзера в БД");
            if ((activeChanged || securityRoleChanged)
                    && saveUser.getKeycloakId() != null
                    && !saveUser.getKeycloakId().isBlank()) {
                logoutUserSessionsBestEffort(saveUser.getKeycloakId());
            }
        } else {
            log.info("Изменений не было, сущность в БД не изменена");
        }
    }

    private String canonicalLocalRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized : "ROLE_" + normalized;
    }

    private void requireAdminForLegacyAdminMutation(User target, String requestedRole) {
        boolean existingAdmin = target.getRoles() != null && target.getRoles().stream()
                .map(Role::getName)
                .filter(Objects::nonNull)
                .anyMatch("ROLE_ADMIN"::equalsIgnoreCase);
        if (!existingAdmin && !"ROLE_ADMIN".equals(requestedRole)) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = authentication != null
                && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(authority -> "ROLE_ADMIN".equalsIgnoreCase(authority)
                        || "ADMIN".equalsIgnoreCase(authority));
        if (!admin) {
            throw new ResponseStatusException(FORBIDDEN, "Only an admin can manage admin users or roles.");
        }
    }

    private void logoutUserSessionsBestEffort(String keycloakUserId) {
        try {
            keycloakAdminClient.logoutUserSessions(keycloakUserId);
        } catch (RuntimeException ignored) {
            log.warn("Keycloak session logout failed after a legacy security-state change; continuing without rollback");
        }
    }

    // ===================================== DELETE LINKS =====================================

    @Override
    @Transactional
    public void deleteOperator(String username, Long operatorId) {
        log.info("1. Вошли в удаление оператора");

        User user = requireUserWithAssignments(username);
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

        User user = requireUserWithAssignments(username);
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

        User user = requireUserWithAssignments(username);
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

        User user = requireUserWithAssignments(username);
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

    @Override
    public Map<String, Long> getAllWorkerTelegramGroups() {
        return userRepository.getAllWorkerTelegramGroups().stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1],
                        (left, right) -> left
                ));
    }

    private Image toImageEntity(MultipartFile file) throws IOException {
        return imageService.saveCompressedProfileImage(file);
    }
}
