package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.api.ClientChatNoResponseReviews;
import com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ClientChatNoResponseReviewsService implements ClientChatNoResponseReviews {
    private final ClientChatUnansweredItemRepository unanswered;
    private final ClientChatNoResponseAiReviewService reviews;

    @Override
    @Transactional(readOnly = true)
    public PreparedNoResponseReview snapshot(Long itemId, Long authorizedManagerId) {
        var item = unanswered.findById(itemId).orElse(null);
        if (item == null || item.getStatus() != ClientChatUnansweredStatus.OPEN) {
            return null;
        }
        Long managerId = item.managerId();
        if (authorizedManagerId == null || !Objects.equals(managerId, authorizedManagerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Сообщение передано другому менеджеру. Обновите контроль перед проверкой");
        }
        return new PreparedNoResponseReview(item.getId(),
                item.getLastClientMessage() == null ? null : item.getLastClientMessage().getId(),
                item.getLastMessageText(), item.getLastClientMessageAt(), managerId, null);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PreparedNoResponseReview review(PreparedNoResponseReview source) {
        var decision = reviews.review(source.messageText());
        return new PreparedNoResponseReview(source.itemId(), source.messageId(), source.messageText(),
                source.messageAt(), source.managerId(), decision);
    }
}
