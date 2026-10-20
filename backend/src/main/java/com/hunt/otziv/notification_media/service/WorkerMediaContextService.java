package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.notification_media.api.StaffMediaSignal;
import com.hunt.otziv.u_users.api.DeferredUserAuthority;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Captures the current actor and card ownership in one read transaction. */
@Service
@RequiredArgsConstructor
public class WorkerMediaContextService {
    private final DeferredUserAuthority authority;
    private final ContextualMediaFacts facts;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public void record(Authentication authentication, String action, String entityType, Long entityId, String section) {
        var actor = authority.capture(authentication);
        if (actor == null || actor.userId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        boolean global = "SITE_ERROR".equals(action) || "NETWORK_BLOCKED".equals(action);
        if (!global && facts.ownedCard(actor.userId(), entityType, entityId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка не найдена");
        }
        events.publishEvent(new StaffMediaSignal(actor.userId(), action, entityType, entityId, null, section));
    }
}
