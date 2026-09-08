package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Manager/owner visibility and current order authorization shared by control workflows. */
@Service
@RequiredArgsConstructor
public class ManagerControlAccessPolicy {
    private static final String OWNER_CONTROL_ALL_MANAGERS = "ALL_MANAGERS";
    private final ManagerRepository managerRepository;
    private final UserService userService;
    private final ManagerAccessService managerAccessService;
    private final ManagerPermissionService managerPermissionService;

    void requireCurrentOrderAccess(Long orderId, Authentication authentication) {
        try {
            managerAccessService.requireOrderAccess(orderId, authentication);
        } catch (ResponseStatusException denied) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Заказ больше не доступен менеджеру этой карточки",
                    denied
            );
        }
    }

    List<Manager> visibleManagers(Principal principal, Authentication authentication) {
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            List<Manager> managers = managerRepository.findAllWithUserAndImage();
            return managers.isEmpty() ? List.of() : managerRepository.findAllManagersWorkers(managers);
        }

        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            User owner = currentUser(principal);
            if (OWNER_CONTROL_ALL_MANAGERS.equalsIgnoreCase(safe(owner == null ? null : owner.getOwnerControlViewMode()))) {
                List<Manager> managers = managerRepository.findAllWithUserAndImage();
                return managers.isEmpty() ? List.of() : managerRepository.findAllManagersWorkers(managers);
            }
            List<Manager> managers = userService.findManagersByUserName(principal.getName()).stream().toList();
            return managers.isEmpty() ? List.of() : managerRepository.findAllManagersWorkers(managers);
        }

        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            User user = currentUser(principal);
            if (user == null || user.getId() == null) {
                return List.of();
            }
            return managerRepository.findByUserId(user.getId())
                    .map(List::of)
                    .orElseGet(List::of);
        }

        return List.of();
    }

    void requireControlAccess(ManagerDailyControl control, Principal principal, Authentication authentication) {
        requireManagerAccess(control.getManager(), principal, authentication);
    }

    void requireCurrentCompanyAccess(Long companyId, Authentication authentication) {
        try {
            managerAccessService.requireCompanyAccess(companyId, authentication);
        } catch (ResponseStatusException denied) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Компания больше не доступна менеджеру этой карточки", denied);
        }
    }

    void requireClientMessageAccess(com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem item,
                                    ManagerDailyControl control, Principal principal, Authentication authentication) {
        requireManagerAccess(item.getManager() == null ? control.getManager() : item.getManager(), principal, authentication);
        if (item.getCompany() == null || item.getCompany().getId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Компания сообщения не определена");
        requireCurrentCompanyAccess(item.getCompany().getId(), authentication);
    }

    void requireManagerAccess(Manager manager, Principal principal, Authentication authentication) {
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return;
        }
        Long controlManagerId = manager == null ? null : manager.getId();

        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            User owner = currentUser(principal);
            if (OWNER_CONTROL_ALL_MANAGERS.equalsIgnoreCase(safe(owner == null ? null : owner.getOwnerControlViewMode()))) {
                return;
            }
            Set<Long> managerIds = userService.findManagersByUserName(principal.getName()).stream()
                    .map(Manager::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (controlManagerId != null && managerIds.contains(controlManagerId)) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер недоступен");
        }

        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            User user = currentUser(principal);
            Long ownManagerId = user == null || user.getId() == null
                    ? null
                    : managerRepository.findByUserId(user.getId()).map(Manager::getId).orElse(null);
            if (controlManagerId != null && controlManagerId.equals(ownManagerId)) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер недоступен");
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав");
    }

    Long actorUserId(Principal principal) {
        User user = currentUser(principal);
        return user == null ? null : user.getId();
    }

    User currentUser(Principal principal) {
        if (principal == null || principal.getName() == null) {
            return null;
        }
        return userService.findByUserName(principal.getName())
                .orElse(null);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
