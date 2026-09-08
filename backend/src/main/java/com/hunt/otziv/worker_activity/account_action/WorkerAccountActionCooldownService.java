package com.hunt.otziv.worker_activity.account_action;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class WorkerAccountActionCooldownService {
    static final String ACCEPTED_REQUEST_ATTRIBUTE = WorkerAccountActionCooldownService.class.getName() + ".accepted";
    private final WorkerAccountActionCooldownRepository repository;

    /** Call only after access, existence, status and expected binding validation, before the first mutation. */
    public void admitCurrentAction() {
        admitAction(SecurityContextHolder.getContext().getAuthentication());
    }

    /** Uses the actor captured by the command boundary; never falls back to thread-local authentication. */
    public void admitAction(Authentication authentication) {
        if (!isPlainWorker(authentication)) {
            return;
        }
        ServletRequestAttributes request = requestAttributes();
        // Some manual actions call a fallback assignment in the same HTTP request.
        if (request != null) {
            Object accepted = request.getRequest().getAttribute(ACCEPTED_REQUEST_ATTRIBUTE);
            if (accepted instanceof AcceptedAction action && Objects.equals(action.username(), authentication.getName())) {
                return;
            }
            // A rejection for another actor must not inherit the previous actor's response headers.
            request.getRequest().removeAttribute(ACCEPTED_REQUEST_ATTRIBUTE);
        }
        WorkerAccountActionCooldownRepository.Admission admission = repository.admit(authentication.getName());
        writeHeaders(admission.state());
        if (!admission.accepted()) {
            throw new WorkerAccountActionCooldownException(admission.state());
        }
        if (request != null) {
            request.getRequest().setAttribute(ACCEPTED_REQUEST_ATTRIBUTE,
                    new AcceptedAction(authentication.getName(), admission.state()));
        }
    }

    public WorkerAccountActionCooldownState currentState() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isPlainWorker(authentication)) {
            return WorkerAccountActionCooldownState.of(0, 0, Instant.now().toEpochMilli());
        }
        return repository.currentState(authentication.getName());
    }

    static boolean isPlainWorker(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        boolean worker = false;
        for (var authority : authentication.getAuthorities()) {
            String role = authority.getAuthority() == null ? "" : authority.getAuthority().toUpperCase(Locale.ROOT);
            if ("ROLE_ADMIN".equals(role) || "ROLE_OWNER".equals(role) || "ROLE_MANAGER".equals(role)) {
                return false;
            }
            worker |= "ROLE_WORKER".equals(role);
        }
        return worker;
    }

    private void writeHeaders(WorkerAccountActionCooldownState state) {
        ServletRequestAttributes request = requestAttributes();
        if (request != null && request.getResponse() != null) {
            WorkerAccountActionCooldownHeaders.write(state, request.getResponse()::setHeader);
        }
    }

    private ServletRequestAttributes requestAttributes() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request ? request : null;
    }

    record AcceptedAction(String username, WorkerAccountActionCooldownState state) { }
}
