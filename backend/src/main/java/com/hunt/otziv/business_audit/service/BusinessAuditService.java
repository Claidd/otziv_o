package com.hunt.otziv.business_audit.service;

import com.hunt.otziv.business_audit.model.BusinessAuditEvent;
import com.hunt.otziv.business_audit.repository.BusinessAuditEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@Slf4j
public class BusinessAuditService {

    private static final int VALUE_LIMIT = 2_000;
    private static final int DETAILS_LIMIT = 1_000;

    private final BusinessAuditEventRepository repository;
    private final TransactionTemplate transactionTemplate;

    public BusinessAuditService(
            BusinessAuditEventRepository repository,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void recordSafely(
            String action,
            String entityType,
            Object entityId,
            Long orderId,
            Long reviewId,
            Object oldValue,
            Object newValue,
            String details
    ) {
        try {
            transactionTemplate.executeWithoutResult(status -> repository.save(event(
                    action,
                    entityType,
                    entityId,
                    orderId,
                    reviewId,
                    oldValue,
                    newValue,
                    details
            )));
        } catch (Exception e) {
            log.warn("Бизнес-аудит не записан action={}, entityType={}, entityId={}: {}",
                    action, entityType, entityId, e.getMessage());
            log.debug("Business audit write failed", e);
        }
    }

    private BusinessAuditEvent event(
            String action,
            String entityType,
            Object entityId,
            Long orderId,
            Long reviewId,
            Object oldValue,
            Object newValue,
            String details
    ) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setActor(currentActor());
        event.setSource(currentSource());
        event.setAction(limit(action, 80));
        event.setEntityType(limit(entityType, 40));
        event.setEntityId(entityId == null ? null : limit(String.valueOf(entityId), 80));
        event.setOrderId(orderId);
        event.setReviewId(reviewId);
        event.setOldValue(limit(valueToString(oldValue), VALUE_LIMIT));
        event.setNewValue(limit(valueToString(newValue), VALUE_LIMIT));
        event.setDetails(limit(details, DETAILS_LIMIT));
        return event;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && hasText(authentication.getName())) {
            return limit(authentication.getName(), 150);
        }
        return "system";
    }

    private String currentSource() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "cron_or_maintenance";
        }

        HttpServletRequest request = attributes.getRequest();
        String path = request.getRequestURI();
        if (path == null) {
            return "api";
        }
        if (path.startsWith("/api/worker")) {
            return "worker_board";
        }
        if (path.startsWith("/api/manager") || path.startsWith("/api/review-check")) {
            return "manager_board";
        }
        if (path.startsWith("/api/admin")) {
            return "admin_api";
        }
        return "api";
    }

    private String valueToString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String limit(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit - 20) + "... [len=" + value.length() + "]";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
