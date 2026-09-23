package com.hunt.otziv.notification_media.controller;

import com.hunt.otziv.notification_media.service.ContextualMediaFacts;
import com.hunt.otziv.notification_media.api.StaffMediaSignal;
import com.hunt.otziv.u_users.api.DeferredUserAuthority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Client-only observations; completion events can never be submitted here. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/worker/notification-media")
@PreAuthorize("hasRole('WORKER') and !hasAnyRole('ADMIN','OWNER','MANAGER')")
public class ApiWorkerMediaContextController {
    private static final Set<String> ACTIONS = Set.of("BOARD", "UNSAVED_CHANGES", "EMPTY_TEXT", "SITE_ERROR", "NETWORK_BLOCKED");
    private final DeferredUserAuthority authority;
    private final ContextualMediaFacts facts;
    private final ApplicationEventPublisher events;

    @PostMapping("/context")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void context(@Valid @RequestBody ContextRequest request, Authentication authentication) {
        if (request == null || request.action() == null || !ACTIONS.contains(request.action())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный контекст");
        }
        var actor = authority.capture(authentication);
        if (actor == null || actor.userId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        boolean global = "SITE_ERROR".equals(request.action()) || "NETWORK_BLOCKED".equals(request.action());
        if (!global && facts.ownedCard(actor.userId(), request.entityType(), request.entityId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка не найдена");
        }
        events.publishEvent(new StaffMediaSignal(actor.userId(), request.action(), request.entityType(),
                request.entityId(), null, request.section()));
    }

    public record ContextRequest(@Size(max=40) String action, @Size(max=40) String entityType,
                                 Long entityId, @Size(max=40) String section) {}
}
