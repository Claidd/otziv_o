package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.api.DeliveryOperation;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ManagerClientDeliveryStatus {
    private final ManagerDailyControlConcreteItemRepository cards;
    private final ManagerControlAccessPolicy access;
    private final ManagerClientMessageQueue queue;

    @Transactional(readOnly = true)
    public DeliveryOperation get(long cardId, String operationId, Principal principal, Authentication authentication) {
        var card = cards.findById(cardId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.requireControlAccess(card.getControl(), principal, authentication);
        var result = queue.status(cardId, operationId);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return result;
    }
}
