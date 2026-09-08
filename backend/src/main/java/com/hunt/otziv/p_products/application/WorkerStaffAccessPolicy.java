package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import java.security.Principal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** One authority for permitted managers, task assignment and available workers; shared by commands and board queries. */
@Service
@RequiredArgsConstructor
public class WorkerStaffAccessPolicy {

    private static final String OWNER_CONTROL_ALL_MANAGERS="ALL_MANAGERS";
    private final UserService userService;
    private final ManagerService managerService;
    private final WorkerService workerService;

    public Worker assignmentWorker(
            Long requestedWorkerId,
            Principal principal,
            Authentication authentication
    ) {
        if (requestedWorkerId == null || requestedWorkerId <= 0) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST, "Специалист не указан");
        }

        return workerFilterWorkers(principal, authentication).stream()
                .filter(worker -> Objects.equals(worker.getId(), requestedWorkerId))
                .findFirst()
                .orElseThrow(() -> new WorkerOrderCommandException(WorkerOrderCommandException.Kind.FORBIDDEN, "Этот специалист недоступен"));
    }

    public void enforceTaskAssignmentAccess(
            Order order,
            Manager recoveryManager,
            Principal principal,
            Authentication authentication
    ) {
        if (hasRole(authentication, "ADMIN")) {
            return;
        }

        Manager authoritativeManager = order == null ? recoveryManager : order.getManager();
        if (hasRole(authentication, "MANAGER")) {
            Manager currentManager = resolveManager(principal);
            if (sameManager(currentManager, authoritativeManager)) {
                return;
            }
        } else if (hasRole(authentication, "OWNER")) {
            boolean allowed = resolveOwnerManagers(principal).stream().anyMatch(manager ->
                    sameManager(manager, authoritativeManager)
            );
            if (allowed) {
                return;
            }
        }

        throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.FORBIDDEN, "Эта задача недоступна");
    }

    private boolean sameManager(Manager left, Manager right) {
        return left != null
                && right != null
                && left.getId() != null
                && Objects.equals(left.getId(), right.getId());
    }

    public List<Worker> workerFilterWorkers(Principal principal, Authentication authentication) {
        if (hasRole(authentication, "ADMIN")) {
            return sortWorkerOptions(workerService.getAllWorkers());
        }
        if (hasRole(authentication, "OWNER")) {
            List<Manager> managers = resolveOwnerManagers(principal).stream().toList();
            return managers.isEmpty()
                    ? List.of()
                    : sortWorkerOptions(workerService.getAllWorkersToManagerList(managers).stream().toList());
        }
        if (hasRole(authentication, "MANAGER")) {
            Manager manager = resolveManager(principal);
            return manager == null ? List.of() : sortWorkerOptions(workerService.getAllWorkersToManager(manager));
        }
        return List.of();
    }

    private List<Worker> sortWorkerOptions(List<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            return List.of();
        }

        return workers.stream()
                .filter(worker -> worker != null && worker.getId() != null)
                .distinct()
                .sorted(Comparator.comparing(this::workerOptionLabel, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private String workerOptionLabel(Worker worker) {
        User user = worker == null ? null : worker.getUser();
        if (user != null && !safe(user.getFio()).isBlank()) {
            return user.getFio().trim();
        }
        if (user != null && !safe(user.getUsername()).isBlank()) {
            return user.getUsername().trim();
        }
        return worker != null && worker.getId() != null ? "Специалист #" + worker.getId() : "Специалист";
    }

    public Manager resolveManager(Principal principal) {
        User user = userService.findByUserName(principal.getName())
                .orElseThrow(() -> new WorkerOrderCommandException(WorkerOrderCommandException.Kind.NOT_FOUND, "Пользователь не найден"));
        return managerService.getManagerByUserId(user.getId());
    }

    public Set<Manager> resolveOwnerManagers(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return Set.of();
        }
        User owner = userService.findByUserName(principal.getName()).orElse(null);
        if (owner != null
                && OWNER_CONTROL_ALL_MANAGERS.equalsIgnoreCase(safe(owner.getOwnerControlViewMode()).trim())) {
            List<Manager> managers = managerService.getAllManagers();
            return managers == null || managers.isEmpty()
                    ? Set.of()
                    : new java.util.LinkedHashSet<>(managers);
        }
        return userService.findManagersByUserName(principal.getName());
    }

    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null) {
            return false;
        }

        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }
}
