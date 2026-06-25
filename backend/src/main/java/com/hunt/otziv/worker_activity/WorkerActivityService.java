package com.hunt.otziv.worker_activity;

import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.model.WorkerActivityEvent;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class WorkerActivityService {

    private static final int TEXT_LIMIT = 1_000;

    private final WorkerActivityEventRepository eventRepository;
    private final UserService userService;
    private final WorkerRiskEvaluationService riskEvaluationService;
    private final TransactionTemplate transactionTemplate;

    public WorkerActivityService(
            WorkerActivityEventRepository eventRepository,
            UserService userService,
            WorkerRiskEvaluationService riskEvaluationService,
            PlatformTransactionManager transactionManager
    ) {
        this.eventRepository = eventRepository;
        this.userService = userService;
        this.riskEvaluationService = riskEvaluationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void recordSafely(
            Authentication authentication,
            WorkerActivityAction action,
            String entityType,
            Long entityId,
            Long orderId,
            Long reviewId,
            String section,
            String details
    ) {
        if (!isPlainWorker(authentication)) {
            return;
        }

        try {
            String username = authentication.getName();
            User workerUser = userService.findByUserNameWithAssignments(username).orElse(null);
            if (workerUser == null || workerUser.getId() == null) {
                log.warn("Активность специалиста не записана: пользователь {} не найден", username);
                return;
            }

            WorkerActivityEvent event = transactionTemplate.execute(status -> eventRepository.save(event(
                    workerUser,
                    action,
                    entityType,
                    entityId,
                    orderId,
                    reviewId,
                    section,
                    details
            )));
            if (event != null) {
                riskEvaluationService.evaluateSafely(event, workerUser);
            }
        } catch (RuntimeException e) {
            log.warn("Активность специалиста не записана action={}, entityType={}, entityId={}: {}",
                    action, entityType, entityId, e.getMessage());
            log.debug("Worker activity write failed", e);
        }
    }

    public void recordCurrentAuthenticationSafely(
            WorkerActivityAction action,
            String entityType,
            Long entityId,
            Long orderId,
            Long reviewId,
            String section,
            String details
    ) {
        recordSafely(
                SecurityContextHolder.getContext().getAuthentication(),
                action,
                entityType,
                entityId,
                orderId,
                reviewId,
                section,
                details
        );
    }

    private WorkerActivityEvent event(
            User workerUser,
            WorkerActivityAction action,
            String entityType,
            Long entityId,
            Long orderId,
            Long reviewId,
            String section,
            String details
    ) {
        WorkerActivityEvent event = new WorkerActivityEvent();
        event.setWorkerUserId(workerUser.getId());
        event.setWorkerUsername(limit(workerUser.getUsername(), 150));
        event.setWorkerName(limit(workerName(workerUser), 200));
        event.setAction(action);
        event.setEntityType(limit(entityType == null ? "unknown" : entityType, 60));
        event.setEntityId(entityId);
        event.setOrderId(orderId);
        event.setReviewId(reviewId);
        event.setSection(limit(section, 40));
        event.setDetails(limit(details, TEXT_LIMIT));
        return event;
    }

    public boolean isPlainWorker(Authentication authentication) {
        return hasRole(authentication, "WORKER")
                && !hasRole(authentication, "ADMIN")
                && !hasRole(authentication, "OWNER")
                && !hasRole(authentication, "MANAGER");
    }

    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private String workerName(User user) {
        String fio = clean(user == null ? null : user.getFio());
        return fio.isBlank() ? clean(user == null ? null : user.getUsername()) : fio;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }
}
