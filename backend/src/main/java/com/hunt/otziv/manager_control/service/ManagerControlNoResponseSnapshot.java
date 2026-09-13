package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview;
import com.hunt.otziv.client_chat_control.api.ClientChatNoResponseReviews;
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
public class ManagerControlNoResponseSnapshot {
    private final ManagerDailyControlConcreteItemRepository cards;
    private final ClientChatNoResponseReviews reviews;
    private final ManagerControlAccessPolicy access;

    @Transactional(readOnly = true)
    public PreparedNoResponseReview read(Long cardId, Principal principal, Authentication authentication) {
        if (cardId == null || cardId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        var card = cards.findById(cardId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        access.requireControlAccess(card.getControl(), principal, authentication);
        if (!"CLIENT_CHAT_UNANSWERED".equals(card.getEntityType()) || card.getEntityId() == null) {
            return null;
        }
        return reviews.snapshot(card.getEntityId(), card.getControl().managerId());
    }
}
