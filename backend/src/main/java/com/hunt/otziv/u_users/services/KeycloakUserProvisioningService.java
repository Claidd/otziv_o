package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.dto.AdminUserResponse;
import com.hunt.otziv.u_users.dto.AssignmentOptionResponse;
import com.hunt.otziv.u_users.dto.AssignmentOptionsResponse;
import com.hunt.otziv.u_users.dto.ChangeKeycloakPasswordRequest;
import com.hunt.otziv.u_users.dto.CreateKeycloakUserRequest;
import com.hunt.otziv.u_users.dto.CreatedKeycloakUserResponse;
import com.hunt.otziv.u_users.dto.LegacyUserMigrationRequest;
import com.hunt.otziv.u_users.dto.RegisterClientRequest;
import com.hunt.otziv.u_users.dto.UpdateKeycloakUserRequest;
import com.hunt.otziv.u_users.dto.UpdateUserAssignmentsRequest;
import com.hunt.otziv.u_users.dto.UserAssignmentsResponse;
import com.hunt.otziv.u_users.keycloak.KeycloakAdminClient;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.RoleRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
@RequiredArgsConstructor
public class KeycloakUserProvisioningService {

    private static final BigDecimal DEFAULT_COEFFICIENT = new BigDecimal("0.05");
    private static final String KEYCLOAK_AUTH_PROVIDER = "KEYCLOAK";
    private static final String CLIENT_ROLE = "CLIENT";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final PasswordEncoder passwordEncoder;
    private final OperatorService operatorService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final MarketologService marketologService;

    @Transactional
    public CreatedKeycloakUserResponse createUser(CreateKeycloakUserRequest request) {
        validateLocalUniqueness(request);

        Set<String> keycloakRoles = normalizeKeycloakRoles(request.getRoles());
        List<Role> localRoles = findLocalRoles(keycloakRoles);

        String keycloakId = null;
        try {
            keycloakId = keycloakAdminClient.createUser(request);
            keycloakAdminClient.assignRealmRoles(keycloakId, keycloakRoles);

            User user = User.builder()
                    .username(request.getUsername())
                    .password(null)
                    .fio(request.getFio())
                    .email(request.getEmail())
                    .phoneNumber(request.getPhoneNumber())
                    .coefficient(request.getCoefficient() == null ? DEFAULT_COEFFICIENT : request.getCoefficient())
                    .keycloakId(keycloakId)
                    .authProvider(KEYCLOAK_AUTH_PROVIDER)
                    .roles(localRoles)
                    .active(request.isEnabled())
                    .build();

            User saved = userRepository.saveAndFlush(user);
            createRoleAssignments(saved, localRoles);
            userRepository.flush();

            return toResponse(saved, keycloakRoles);
        } catch (RuntimeException e) {
            keycloakAdminClient.deleteUser(keycloakId);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> getUsers() {
        return userRepository.findAll().stream()
                .map(this::toAdminResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AssignmentOptionsResponse getAssignmentOptions() {
        return new AssignmentOptionsResponse(
                managerService.getAllManagers().stream()
                        .map(manager -> toOption(manager.getId(), manager.getUser(), "MANAGER"))
                        .toList(),
                workerService.getAllWorkers().stream()
                        .map(worker -> toOption(worker.getId(), worker.getUser(), "WORKER"))
                        .toList(),
                operatorService.getAllOperators().stream()
                        .map(operator -> toOption(operator.getId(), operator.getUser(), "OPERATOR"))
                        .toList(),
                marketologService.getAllMarketologs().stream()
                        .map(marketolog -> toOption(marketolog.getId(), marketolog.getUser(), "MARKETOLOG"))
                        .toList()
        );
    }

    @Transactional(readOnly = true)
    public UserAssignmentsResponse getUserAssignments(Long userId) {
        User user = findUserWithAssignments(userId);
        return toAssignmentsResponse(user);
    }

    @Transactional
    public AdminUserResponse updateUser(Long userId, UpdateKeycloakUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Local user not found"));

        if (!hasText(user.getKeycloakId())) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "User is not linked to Keycloak yet. Run legacy migration first."
            );
        }

        Set<String> oldKeycloakRoles = toKeycloakRoles(user.getRoles());
        Set<String> newKeycloakRoles = normalizeKeycloakRoles(request.getRoles());
        List<Role> newLocalRoles = findLocalRoles(newKeycloakRoles);

        keycloakAdminClient.updateUser(user.getKeycloakId(), user.getUsername(), request);
        replaceKeycloakRealmRoles(user.getKeycloakId(), oldKeycloakRoles, newKeycloakRoles);

        Set<String> oldLocalRoleNames = user.getRoles() == null ? Set.of() : user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

        user.setEmail(trimToNull(request.getEmail()));
        user.setFio(trimToNull(request.getFio()));
        user.setPhoneNumber(trimToNull(request.getPhoneNumber()));
        user.setCoefficient(request.getCoefficient() == null ? DEFAULT_COEFFICIENT : request.getCoefficient());
        user.setActive(request.isEnabled());
        replaceLocalRoles(user, newLocalRoles);
        user.setAuthProvider(KEYCLOAK_AUTH_PROVIDER);

        updateRoleAssignments(user, oldLocalRoleNames, newLocalRoles);
        userRepository.flush();

        return toAdminResponse(user);
    }

    @Transactional(readOnly = true)
    public void changePassword(Long userId, ChangeKeycloakPasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Local user not found"));

        if (!hasText(user.getKeycloakId())) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "User is not linked to Keycloak yet. Run legacy migration first."
            );
        }

        keycloakAdminClient.resetPassword(
                user.getKeycloakId(),
                request.getPassword(),
                request.isTemporary()
        );
    }

    @Transactional
    public UserAssignmentsResponse updateUserAssignments(Long userId, UpdateUserAssignmentsRequest request) {
        User user = findUserWithAssignments(userId);

        Set<Manager> managers = findManagers(request.getManagerIds());
        Set<Worker> workers = findWorkers(request.getWorkerIds());
        Set<Operator> operators = findOperators(request.getOperatorIds());
        Set<Marketolog> marketologs = findMarketologs(request.getMarketologIds());

        replaceSet(user.getManagers(), managers, user::setManagers);
        replaceSet(user.getWorkers(), workers, user::setWorkers);
        replaceSet(user.getOperators(), operators, user::setOperators);
        replaceSet(user.getMarketologs(), marketologs, user::setMarketologs);

        if (hasLocalRole(user, "ROLE_MANAGER")) {
            Manager manager = managerService.getManagerByUserId(user.getId());
            if (manager != null) {
                syncManagerToSubordinates(manager, workers, operators, marketologs);
            }
        }

        if (hasLocalRole(user, "ROLE_WORKER")) {
            Worker worker = workerService.getWorkerByUserId(user.getId());
            if (worker != null) {
                syncProfileToManagers(worker, managers);
            }
        }

        if (hasLocalRole(user, "ROLE_OPERATOR")) {
            Operator operator = operatorService.getOperatorByUserId(user.getId());
            if (operator != null) {
                syncProfileToManagers(operator, managers);
            }
        }

        if (hasLocalRole(user, "ROLE_MARKETOLOG")) {
            Marketolog marketolog = marketologService.getMarketologByUserId(user.getId());
            if (marketolog != null) {
                syncProfileToManagers(marketolog, managers);
            }
        }

        userRepository.flush();
        return toAssignmentsResponse(user);
    }

    @Transactional
    public CreatedKeycloakUserResponse registerClient(RegisterClientRequest request) {
        if (!Objects.equals(request.getPassword(), request.getMatchingPassword())) {
            throw new ResponseStatusException(BAD_REQUEST, "Passwords do not match");
        }

        CreateKeycloakUserRequest createRequest = new CreateKeycloakUserRequest();
        createRequest.setUsername(request.getUsername().trim());
        createRequest.setEmail(request.getEmail().trim());
        createRequest.setFio(trimToNull(request.getFio()));
        createRequest.setPhoneNumber(trimToNull(request.getPhoneNumber()));
        createRequest.setPassword(request.getPassword());
        createRequest.setTemporaryPassword(false);
        createRequest.setEnabled(true);
        createRequest.setEmailVerified(false);
        createRequest.setCoefficient(DEFAULT_COEFFICIENT);
        createRequest.setRoles(new LinkedHashSet<>(Set.of(CLIENT_ROLE)));

        return createUser(createRequest);
    }

    @Transactional
    public CreatedKeycloakUserResponse migrateLegacyUser(LegacyUserMigrationRequest request) {
        String username = request.getUsername().trim();
        User user = userRepository.findByUsernameWithAssignments(username)
                .orElseThrow(this::invalidLegacyCredentials);

        if (!user.isActive()) {
            throw new ResponseStatusException(FORBIDDEN, "User is inactive");
        }
        if (!hasText(user.getPassword()) || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw invalidLegacyCredentials();
        }

        Set<String> keycloakRoles = toKeycloakRoles(user.getRoles());
        String keycloakId = user.getKeycloakId();

        if (!hasText(keycloakId)) {
            CreateKeycloakUserRequest createRequest = new CreateKeycloakUserRequest();
            createRequest.setUsername(user.getUsername());
            createRequest.setEmail(user.getEmail());
            createRequest.setFio(user.getFio());
            createRequest.setPhoneNumber(user.getPhoneNumber());
            createRequest.setPassword(request.getPassword());
            createRequest.setTemporaryPassword(false);
            createRequest.setEnabled(user.isActive());
            createRequest.setEmailVerified(false);
            createRequest.setCoefficient(user.getCoefficient());
            createRequest.setRoles(keycloakRoles);

            keycloakId = createOrFindKeycloakUser(createRequest);
        }

        keycloakAdminClient.assignRealmRoles(keycloakId, keycloakRoles);
        user.setKeycloakId(keycloakId);
        user.setAuthProvider(KEYCLOAK_AUTH_PROVIDER);
        user.setLastLoginAt(LocalDateTime.now());
        User saved = userRepository.saveAndFlush(user);

        return toResponse(saved, keycloakRoles);
    }

    private void validateLocalUniqueness(CreateKeycloakUserRequest request) {
        userRepository.findByUsername(request.getUsername())
                .ifPresent(user -> {
                    throw new ResponseStatusException(CONFLICT, "Local username already exists");
                });

        if (userRepository.findByEmail(request.getEmail()) != null) {
            throw new ResponseStatusException(CONFLICT, "Local email already exists");
        }
    }

    private Set<String> normalizeKeycloakRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "At least one role is required");
        }

        return roles.stream()
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(this::removeRolePrefix)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Role> findLocalRoles(Set<String> keycloakRoles) {
        return keycloakRoles.stream()
                .map(this::toLocalRoleName)
                .map(roleName -> roleRepository.findByName(roleName)
                        .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Unknown local role: " + roleName)))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void createRoleAssignments(User user, Collection<Role> roles) {
        Set<String> localRoleNames = roles.stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

        if (localRoleNames.contains("ROLE_OPERATOR")) {
            operatorService.saveNewOperator(user);
        }
        if (localRoleNames.contains("ROLE_MANAGER")) {
            managerService.saveNewManager(user);
        }
        if (localRoleNames.contains("ROLE_WORKER")) {
            workerService.saveNewWorker(user);
        }
        if (localRoleNames.contains("ROLE_MARKETOLOG")) {
            marketologService.saveNewMarketolog(user);
        }
    }

    private void updateRoleAssignments(User user, Set<String> oldLocalRoleNames, Collection<Role> newRoles) {
        Set<String> newLocalRoleNames = newRoles.stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

        if (oldLocalRoleNames.contains("ROLE_OPERATOR") && !newLocalRoleNames.contains("ROLE_OPERATOR")) {
            operatorService.deleteOperator(user);
        }
        if (oldLocalRoleNames.contains("ROLE_MANAGER") && !newLocalRoleNames.contains("ROLE_MANAGER")) {
            managerService.deleteManager(user);
        }
        if (oldLocalRoleNames.contains("ROLE_WORKER") && !newLocalRoleNames.contains("ROLE_WORKER")) {
            workerService.deleteWorker(user);
        }
        if (oldLocalRoleNames.contains("ROLE_MARKETOLOG") && !newLocalRoleNames.contains("ROLE_MARKETOLOG")) {
            marketologService.deleteMarketolog(user);
        }

        List<Role> rolesToCreate = newRoles.stream()
                .filter(role -> !oldLocalRoleNames.contains(role.getName()))
                .toList();
        createRoleAssignments(user, rolesToCreate);
    }

    private void replaceLocalRoles(User user, Collection<Role> newRoles) {
        if (user.getRoles() == null) {
            user.setRoles(new ArrayList<>(newRoles));
            return;
        }

        user.getRoles().clear();
        user.getRoles().addAll(newRoles);
    }

    private User findUserWithAssignments(Long userId) {
        return userRepository.findByIdWithAssignments(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Local user not found"));
    }

    private AssignmentOptionResponse toOption(Long id, User user, String role) {
        return new AssignmentOptionResponse(
                id,
                user == null ? null : user.getId(),
                user == null ? null : user.getUsername(),
                user == null ? null : user.getFio(),
                user == null ? null : user.getEmail(),
                role
        );
    }

    private UserAssignmentsResponse toAssignmentsResponse(User user) {
        return new UserAssignmentsResponse(
                user.getId(),
                ids(user.getManagers()),
                ids(user.getWorkers()),
                ids(user.getOperators()),
                ids(user.getMarketologs())
        );
    }

    private Set<Long> ids(Collection<?> values) {
        if (values == null) {
            return new LinkedHashSet<>();
        }

        return values.stream()
                .map(this::profileId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Long profileId(Object value) {
        if (value instanceof Manager manager) {
            return manager.getId();
        }
        if (value instanceof Worker worker) {
            return worker.getId();
        }
        if (value instanceof Operator operator) {
            return operator.getId();
        }
        if (value instanceof Marketolog marketolog) {
            return marketolog.getId();
        }
        return null;
    }

    private Set<Manager> findManagers(Collection<Long> ids) {
        return safeIds(ids).stream()
                .map(managerService::getManagerById)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Worker> findWorkers(Collection<Long> ids) {
        return safeIds(ids).stream()
                .map(workerService::getWorkerById)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Operator> findOperators(Collection<Long> ids) {
        return safeIds(ids).stream()
                .map(operatorService::getOperatorById)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Marketolog> findMarketologs(Collection<Long> ids) {
        return safeIds(ids).stream()
                .map(marketologService::getMarketologById)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Long> safeIds(Collection<Long> ids) {
        if (ids == null) {
            return new LinkedHashSet<>();
        }

        return ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private <T> void replaceSet(Set<T> current, Set<T> replacement, java.util.function.Consumer<Set<T>> setter) {
        if (current == null) {
            setter.accept(new HashSet<>(replacement));
            return;
        }

        current.clear();
        current.addAll(replacement);
    }

    private void syncManagerToSubordinates(
            Manager manager,
            Set<Worker> selectedWorkers,
            Set<Operator> selectedOperators,
            Set<Marketolog> selectedMarketologs
    ) {
        workerService.getAllWorkers()
                .forEach(worker -> syncManagerMembership(worker.getUser(), manager, containsProfile(selectedWorkers, worker.getId())));
        operatorService.getAllOperators()
                .forEach(operator -> syncManagerMembership(operator.getUser(), manager, containsProfile(selectedOperators, operator.getId())));
        marketologService.getAllMarketologs()
                .forEach(marketolog -> syncManagerMembership(marketolog.getUser(), manager, containsProfile(selectedMarketologs, marketolog.getId())));
    }

    private void syncProfileToManagers(Worker worker, Set<Manager> selectedManagers) {
        managerService.getAllManagers()
                .forEach(manager -> syncProfileMembership(manager.getUser(), worker, containsProfile(selectedManagers, manager.getId())));
    }

    private void syncProfileToManagers(Operator operator, Set<Manager> selectedManagers) {
        managerService.getAllManagers()
                .forEach(manager -> syncProfileMembership(manager.getUser(), operator, containsProfile(selectedManagers, manager.getId())));
    }

    private void syncProfileToManagers(Marketolog marketolog, Set<Manager> selectedManagers) {
        managerService.getAllManagers()
                .forEach(manager -> syncProfileMembership(manager.getUser(), marketolog, containsProfile(selectedManagers, manager.getId())));
    }

    private void syncManagerMembership(User user, Manager manager, boolean selected) {
        if (user == null || manager == null) {
            return;
        }

        if (user.getManagers() == null) {
            user.setManagers(new HashSet<>());
        }

        user.getManagers().removeIf(existing -> Objects.equals(existing.getId(), manager.getId()));
        if (selected) {
            user.getManagers().add(manager);
        }
    }

    private void syncProfileMembership(User managerUser, Worker worker, boolean selected) {
        if (managerUser == null || worker == null) {
            return;
        }

        if (managerUser.getWorkers() == null) {
            managerUser.setWorkers(new HashSet<>());
        }

        managerUser.getWorkers().removeIf(existing -> Objects.equals(existing.getId(), worker.getId()));
        if (selected) {
            managerUser.getWorkers().add(worker);
        }
    }

    private void syncProfileMembership(User managerUser, Operator operator, boolean selected) {
        if (managerUser == null || operator == null) {
            return;
        }

        if (managerUser.getOperators() == null) {
            managerUser.setOperators(new HashSet<>());
        }

        managerUser.getOperators().removeIf(existing -> Objects.equals(existing.getId(), operator.getId()));
        if (selected) {
            managerUser.getOperators().add(operator);
        }
    }

    private void syncProfileMembership(User managerUser, Marketolog marketolog, boolean selected) {
        if (managerUser == null || marketolog == null) {
            return;
        }

        if (managerUser.getMarketologs() == null) {
            managerUser.setMarketologs(new HashSet<>());
        }

        managerUser.getMarketologs().removeIf(existing -> Objects.equals(existing.getId(), marketolog.getId()));
        if (selected) {
            managerUser.getMarketologs().add(marketolog);
        }
    }

    private boolean containsProfile(Collection<?> profiles, Long id) {
        if (profiles == null || id == null) {
            return false;
        }

        return profiles.stream()
                .map(this::profileId)
                .anyMatch(profileId -> Objects.equals(profileId, id));
    }

    private boolean hasLocalRole(User user, String roleName) {
        if (user.getRoles() == null) {
            return false;
        }

        return user.getRoles().stream()
                .map(Role::getName)
                .anyMatch(roleName::equals);
    }

    private CreatedKeycloakUserResponse toResponse(User user, Set<String> keycloakRoles) {
        return CreatedKeycloakUserResponse.builder()
                .id(user.getId())
                .keycloakId(user.getKeycloakId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fio(user.getFio())
                .phoneNumber(user.getPhoneNumber())
                .coefficient(user.getCoefficient())
                .active(user.isActive())
                .roles(keycloakRoles)
                .build();
    }

    private AdminUserResponse toAdminResponse(User user) {
        Set<String> roles = toKeycloakRoles(user.getRoles());

        return AdminUserResponse.builder()
                .id(user.getId())
                .keycloakId(user.getKeycloakId())
                .keycloakLinked(hasText(user.getKeycloakId()))
                .authProvider(user.getAuthProvider())
                .username(user.getUsername())
                .email(user.getEmail())
                .fio(user.getFio())
                .phoneNumber(user.getPhoneNumber())
                .coefficient(user.getCoefficient())
                .active(user.isActive())
                .createTime(user.getCreateTime())
                .lastLoginAt(user.getLastLoginAt())
                .roles(roles)
                .build();
    }

    private String removeRolePrefix(String role) {
        return role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role;
    }

    private String toLocalRoleName(String keycloakRole) {
        return keycloakRole.startsWith("ROLE_") ? keycloakRole : "ROLE_" + keycloakRole;
    }

    private String createOrFindKeycloakUser(CreateKeycloakUserRequest request) {
        try {
            return keycloakAdminClient.createUser(request);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() != CONFLICT.value()) {
                throw e;
            }

            return keycloakAdminClient.findUserIdByUsername(request.getUsername())
                    .orElseThrow(() -> new ResponseStatusException(
                            BAD_GATEWAY,
                            "Keycloak reported existing user but it could not be found"
                    ));
        }
    }

    private void replaceKeycloakRealmRoles(
            String keycloakUserId,
            Set<String> oldKeycloakRoles,
            Set<String> newKeycloakRoles
    ) {
        Set<String> rolesToRemove = oldKeycloakRoles.stream()
                .filter(role -> !newKeycloakRoles.contains(role))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> rolesToAdd = newKeycloakRoles.stream()
                .filter(role -> !oldKeycloakRoles.contains(role))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        keycloakAdminClient.removeRealmRoles(keycloakUserId, rolesToRemove);
        keycloakAdminClient.assignRealmRoles(keycloakUserId, rolesToAdd);
    }

    private Set<String> toKeycloakRoles(Collection<Role> localRoles) {
        Set<String> roles = localRoles == null ? new LinkedHashSet<>() : localRoles.stream()
                .map(Role::getName)
                .filter(this::hasText)
                .map(this::removeRolePrefix)
                .map(role -> "USER".equals(role) ? CLIENT_ROLE : role)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (roles.isEmpty()) {
            roles.add(CLIENT_ROLE);
        }

        return roles;
    }

    private ResponseStatusException invalidLegacyCredentials() {
        return new ResponseStatusException(UNAUTHORIZED, "Invalid legacy username or password");
    }

    private String trimToNull(String value) {
        if (!hasText(value)) {
            return null;
        }

        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
