package com.hunt.otziv.p_products.worker_access.service;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.security.core.Authentication;

/** Shared schedule rule, checked again against the locked task before a worker mutation. */
public final class WorkerTaskSchedulePermission {
    public static final String DENIED_MESSAGE = "Плановую дату задачи может менять только менеджер, владелец или администратор";
    private WorkerTaskSchedulePermission() {}

    public static boolean canEdit(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream().anyMatch(authority ->
                "ROLE_ADMIN".equals(authority.getAuthority())
                        || "ROLE_OWNER".equals(authority.getAuthority())
                        || "ROLE_MANAGER".equals(authority.getAuthority()));
    }

    public static boolean allows(LocalDate requestedDate, LocalDate currentDate, Authentication authentication) {
        return canEdit(authentication) || requestedDate == null || Objects.equals(requestedDate, currentDate);
    }
}
