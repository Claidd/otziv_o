package com.hunt.otziv.manager.service;

import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ManagerAccessService {

    private static final String OWNER_CONTROL_ALL_MANAGERS = "ALL_MANAGERS";

    private final ManagerPermissionService managerPermissionService;
    private final UserService userService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final OrderRepository orderRepository;
    private final CompanyRepository companyRepository;

    @Transactional(readOnly = true)
    public void requireOrderAccess(Long orderId, Authentication authentication) {
        if (!canAccessOrder(orderId, authentication)) {
            throw notFound("Заказ не найден");
        }
    }

    @Transactional(readOnly = true)
    public void requireCompanyAccess(Long companyId, Authentication authentication) {
        if (!canAccessCompany(companyId, authentication)) {
            throw notFound("Компания не найдена");
        }
    }

    @Transactional(readOnly = true)
    public boolean canAccessOrder(Long orderId, Authentication authentication) {
        if (orderId == null || authentication == null) {
            return false;
        }
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return orderRepository.existsById(orderId);
        }
        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            if (ownerCanAccessAllManagers(authentication)) {
                return orderRepository.existsById(orderId);
            }
            Set<Long> managerIds = ownerManagerIds(authentication);
            return !managerIds.isEmpty() && orderRepository.existsByIdAndManager_IdIn(orderId, managerIds);
        }
        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            Manager manager = currentManager(authentication);
            return manager != null && orderRepository.existsByIdAndManager_IdIn(orderId, Set.of(manager.getId()));
        }
        if (managerPermissionService.hasRole(authentication, "WORKER")) {
            Worker worker = currentWorker(authentication);
            return worker != null && orderRepository.existsByIdAndWorker_Id(orderId, worker.getId());
        }
        return false;
    }

    @Transactional(readOnly = true)
    public boolean canAccessCompany(Long companyId, Authentication authentication) {
        if (companyId == null || authentication == null) {
            return false;
        }
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return companyRepository.existsById(companyId);
        }
        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            if (ownerCanAccessAllManagers(authentication)) {
                return companyRepository.existsById(companyId);
            }
            Set<Long> managerIds = ownerManagerIds(authentication);
            return !managerIds.isEmpty() && companyRepository.existsByIdAndManager_IdIn(companyId, managerIds);
        }
        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            Manager manager = currentManager(authentication);
            return manager != null && companyRepository.existsByIdAndManager_IdIn(companyId, Set.of(manager.getId()));
        }
        return false;
    }

    /** Checks a canonical manager assignment, including the owner's manager scope. */
    @Transactional(readOnly = true)
    public boolean canAccessManager(Long managerId, Authentication authentication) {
        if (managerId == null || authentication == null) {
            return false;
        }
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return true;
        }
        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            if (ownerCanAccessAllManagers(authentication)) {
                return managerService.getManagerById(managerId) != null;
            }
            return ownerManagerIds(authentication).contains(managerId);
        }
        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            Manager manager = currentManager(authentication);
            return manager != null && managerId.equals(manager.getId());
        }
        return false;
    }

    /**
     * Applies the same assignment scope to an archived order whose live row no
     * longer exists. The assignment ids must come from the archive row itself,
     * never from a client-controlled request.
     */
    @Transactional(readOnly = true)
    public boolean canAccessArchivedOrder(
            Long archivedManagerId,
            Long archivedWorkerId,
            Authentication authentication
    ) {
        if (authentication == null) {
            return false;
        }
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return true;
        }
        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            if (ownerCanAccessAllManagers(authentication)) {
                return archivedManagerId != null;
            }
            return archivedManagerId != null && ownerManagerIds(authentication).contains(archivedManagerId);
        }
        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            Manager manager = currentManager(authentication);
            return manager != null && archivedManagerId != null && archivedManagerId.equals(manager.getId());
        }
        if (managerPermissionService.hasRole(authentication, "WORKER")) {
            Worker worker = currentWorker(authentication);
            return worker != null && archivedWorkerId != null && archivedWorkerId.equals(worker.getId());
        }
        return false;
    }

    private Set<Long> ownerManagerIds(Authentication authentication) {
        return userService.findManagersByUserName(authentication.getName()).stream()
                .map(Manager::getId)
                .collect(Collectors.toSet());
    }

    private boolean ownerCanAccessAllManagers(Authentication authentication) {
        User owner = currentUser(authentication);
        String mode = owner == null ? null : owner.getOwnerControlViewMode();
        return mode != null && OWNER_CONTROL_ALL_MANAGERS.equalsIgnoreCase(mode.trim());
    }

    private Manager currentManager(Authentication authentication) {
        User user = currentUser(authentication);
        return user == null ? null : managerService.getManagerByUserId(user.getId());
    }

    private Worker currentWorker(Authentication authentication) {
        User user = currentUser(authentication);
        return user == null ? null : workerService.getWorkerByUserId(user.getId());
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return null;
        }
        return userService.findByUserName(authentication.getName()).orElse(null);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
