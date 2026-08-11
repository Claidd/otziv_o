package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.MarketologService;
import com.hunt.otziv.u_users.service.OperatorService;
import com.hunt.otziv.u_users.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Object-level authorization for lead-board and operator-board resources. */
@Service
@RequiredArgsConstructor
public class LeadAccessService {

    private static final String OWNER_CONTROL_ALL_MANAGERS = "ALL_MANAGERS";

    private final LeadsRepository leadsRepository;
    private final TelephoneRepository telephoneRepository;
    private final UserService userService;
    private final ManagerService managerService;
    private final OperatorService operatorService;
    private final MarketologService marketologService;
    private final ManagerPermissionService permissionService;

    @Transactional(readOnly = true)
    public Lead requireLeadAccess(Long leadId, Authentication authentication) {
        Lead lead = leadsRepository.findById(leadId).orElseThrow(LeadAccessService::leadNotFound);
        if (!canAccessLead(lead, authentication)) {
            throw leadNotFound();
        }
        return lead;
    }

    @Transactional(readOnly = true)
    public boolean canAccessTelephone(Long telephoneId, Authentication authentication) {
        if (telephoneId == null || authentication == null) {
            return false;
        }
        Telephone telephone = telephoneRepository.findByIdWithOperator(telephoneId).orElse(null);
        if (telephone == null) {
            return false;
        }
        if (permissionService.hasRole(authentication, "ADMIN")) {
            return true;
        }

        Operator assignedOperator = telephone.getTelephoneOperator();
        if (assignedOperator == null) {
            return false;
        }
        if (permissionService.hasRole(authentication, "OPERATOR")) {
            return isCurrentUser(assignedOperator.getUser(), authentication);
        }
        if (permissionService.hasRole(authentication, "OWNER")) {
            return ownerCanAccessAllManagers(authentication)
                    || intersectsManagerScope(assignedOperator.getUser(), ownerManagerIds(authentication));
        }
        return false;
    }

    @Transactional(readOnly = true)
    public void requireTelephoneAccess(Long telephoneId, Authentication authentication) {
        if (!canAccessTelephone(telephoneId, authentication)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Телефон не найден");
        }
    }

    @Transactional(readOnly = true)
    public boolean canAccessOperator(Long operatorId, Authentication authentication) {
        if (operatorId == null || authentication == null) {
            return false;
        }
        Operator operator;
        try {
            operator = operatorService.getOperatorById(operatorId);
        } catch (EntityNotFoundException exception) {
            return false;
        }
        if (operator == null) {
            return false;
        }
        if (permissionService.hasRole(authentication, "ADMIN")) {
            return true;
        }
        return permissionService.hasRole(authentication, "OWNER")
                && (ownerCanAccessAllManagers(authentication)
                || intersectsManagerScope(operator.getUser(), ownerManagerIds(authentication)));
    }

    @Transactional(readOnly = true)
    public void requireLeadAssignmentsAllowed(
            Long managerId,
            Long operatorId,
            Long marketologId,
            Authentication authentication
    ) {
        if (managerId != null && !canAccessManager(managerId, authentication)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден");
        }
        if (managerId == null) {
            if (operatorId != null || marketologId != null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Оператор и маркетолог не могут быть назначены без менеджера"
                );
            }
            if (permissionService.hasRole(authentication, "MANAGER")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер не может снять лида со своей команды");
            }
            return;
        }

        if (operatorId != null) {
            Operator operator = requireOperator(operatorId);
            if (!belongsToManager(operator.getUser(), managerId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Оператор не закреплен за выбранным менеджером");
            }
        }
        if (marketologId != null) {
            Marketolog marketolog = requireMarketolog(marketologId);
            if (!belongsToManager(marketolog.getUser(), managerId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Маркетолог не закреплен за выбранным менеджером");
            }
        }
    }

    @Transactional(readOnly = true)
    public boolean canAccessManager(Long managerId, Authentication authentication) {
        if (managerId == null || authentication == null) {
            return false;
        }
        if (permissionService.hasRole(authentication, "ADMIN")) {
            return managerService.getManagerById(managerId) != null;
        }
        if (permissionService.hasRole(authentication, "OWNER")) {
            return ownerCanAccessAllManagers(authentication)
                    ? managerService.getManagerById(managerId) != null
                    : ownerManagerIds(authentication).contains(managerId);
        }
        if (permissionService.hasRole(authentication, "MANAGER")) {
            User user = currentUser(authentication);
            Manager manager = user == null ? null : managerService.getManagerByUserId(user.getId());
            return manager != null && Objects.equals(managerId, manager.getId());
        }
        return false;
    }

    /**
     * Resolves the canonical read scope used by every owner lead-board query.
     * ALL_MANAGERS deliberately includes unassigned leads, while OWN_MANAGERS
     * is restricted to the owner's explicit manager assignments.
     */
    @Transactional(readOnly = true)
    public OwnerReadScope ownerReadScope(Authentication authentication) {
        if (authentication == null || !permissionService.hasRole(authentication, "OWNER")) {
            return OwnerReadScope.restricted(List.of());
        }
        if (ownerCanAccessAllManagers(authentication)) {
            return OwnerReadScope.unrestricted();
        }
        List<Manager> managers = userService.findManagersByUserName(authentication.getName()).stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Manager::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        return OwnerReadScope.restricted(managers);
    }

    /** Returns only assignment choices inside the actor's manager scope. */
    @Transactional(readOnly = true)
    public LeadAssignmentOptions assignmentOptions(Authentication authentication) {
        if (authentication == null) {
            return LeadAssignmentOptions.empty();
        }
        if (permissionService.hasRole(authentication, "ADMIN")
                || (permissionService.hasRole(authentication, "OWNER") && ownerCanAccessAllManagers(authentication))) {
            return new LeadAssignmentOptions(
                    managerService.getAllManagers(),
                    operatorService.getAllOperators(),
                    marketologService.getAllMarketologs()
            );
        }

        List<Manager> managers;
        if (permissionService.hasRole(authentication, "MANAGER")) {
            User user = currentUser(authentication);
            Manager manager = user == null ? null : managerService.getManagerByUserId(user.getId());
            managers = manager == null ? List.of() : List.of(manager);
        } else if (permissionService.hasRole(authentication, "OWNER")
                || permissionService.hasRole(authentication, "MARKETOLOG")) {
            managers = new ArrayList<>(userService.findManagersByUserName(authentication.getName()));
        } else {
            return LeadAssignmentOptions.empty();
        }

        managers = managers.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Manager::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        if (managers.isEmpty()) {
            return LeadAssignmentOptions.empty();
        }
        List<Operator> operators = operatorService.getAllOperatorsToManagerList(managers).stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Operator::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        List<Marketolog> marketologs = marketologService.getAllMarketologsToOwner(managers).stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Marketolog::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        return new LeadAssignmentOptions(managers, operators, marketologs);
    }

    private boolean canAccessLead(Lead lead, Authentication authentication) {
        if (lead == null || authentication == null) {
            return false;
        }
        if (permissionService.hasRole(authentication, "ADMIN")) {
            return true;
        }
        if (permissionService.hasRole(authentication, "OWNER")) {
            return ownerCanAccessAllManagers(authentication)
                    || (lead.getManager() != null && ownerManagerIds(authentication).contains(lead.getManager().getId()));
        }
        User current = currentUser(authentication);
        if (current == null) {
            return false;
        }
        if (permissionService.hasRole(authentication, "MANAGER")) {
            return lead.getManager() != null && isCurrentUser(lead.getManager().getUser(), authentication);
        }
        if (permissionService.hasRole(authentication, "MARKETOLOG")) {
            return lead.getMarketolog() != null && isCurrentUser(lead.getMarketolog().getUser(), authentication);
        }
        if (permissionService.hasRole(authentication, "OPERATOR")) {
            return (lead.getOperator() != null && isCurrentUser(lead.getOperator().getUser(), authentication))
                    || (lead.getTelephone() != null
                    && lead.getTelephone().getTelephoneOperator() != null
                    && isCurrentUser(lead.getTelephone().getTelephoneOperator().getUser(), authentication));
        }
        return false;
    }

    private boolean belongsToManager(User user, Long managerId) {
        if (user == null || user.getManagers() == null) {
            return false;
        }
        return user.getManagers().stream()
                .filter(Objects::nonNull)
                .anyMatch(manager -> Objects.equals(managerId, manager.getId()));
    }

    private boolean intersectsManagerScope(User user, Set<Long> managerIds) {
        if (user == null || user.getManagers() == null || managerIds.isEmpty()) {
            return false;
        }
        return user.getManagers().stream()
                .filter(Objects::nonNull)
                .map(Manager::getId)
                .anyMatch(managerIds::contains);
    }

    private boolean isCurrentUser(User user, Authentication authentication) {
        return user != null
                && authentication != null
                && authentication.getName() != null
                && authentication.getName().equalsIgnoreCase(user.getUsername());
    }

    private Set<Long> ownerManagerIds(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return Collections.emptySet();
        }
        return userService.findManagersByUserName(authentication.getName()).stream()
                .filter(Objects::nonNull)
                .map(Manager::getId)
                .collect(Collectors.toSet());
    }

    private boolean ownerCanAccessAllManagers(Authentication authentication) {
        User owner = currentUser(authentication);
        return owner != null
                && owner.getOwnerControlViewMode() != null
                && OWNER_CONTROL_ALL_MANAGERS.equalsIgnoreCase(owner.getOwnerControlViewMode().trim());
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return null;
        }
        return userService.findByUserName(authentication.getName()).orElse(null);
    }

    private Operator requireOperator(Long operatorId) {
        try {
            Operator operator = operatorService.getOperatorById(operatorId);
            if (operator != null) {
                return operator;
            }
        } catch (EntityNotFoundException ignored) {
            // Expose neither persistence details nor a 500 response for a client-controlled id.
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Оператор не найден");
    }

    private Marketolog requireMarketolog(Long marketologId) {
        try {
            Marketolog marketolog = marketologService.getMarketologById(marketologId);
            if (marketolog != null) {
                return marketolog;
            }
        } catch (EntityNotFoundException ignored) {
            // Expose neither persistence details nor a 500 response for a client-controlled id.
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Маркетолог не найден");
    }

    private static ResponseStatusException leadNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Лид не найден");
    }

    public record LeadAssignmentOptions(
            List<Manager> managers,
            List<Operator> operators,
            List<Marketolog> marketologs
    ) {
        private static LeadAssignmentOptions empty() {
            return new LeadAssignmentOptions(List.of(), List.of(), List.of());
        }
    }

    public record OwnerReadScope(boolean canReadAllManagers, List<Manager> managers) {
        public OwnerReadScope {
            managers = managers == null ? List.of() : List.copyOf(managers);
        }

        private static OwnerReadScope unrestricted() {
            return new OwnerReadScope(true, List.of());
        }

        private static OwnerReadScope restricted(List<Manager> managers) {
            return new OwnerReadScope(false, managers);
        }
    }
}
