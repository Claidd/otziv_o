package com.hunt.otziv.client_chat_control.api;

import com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview;

/** Server-only review preparation. Caller must authorize the control card and its manager first. */
public interface ClientChatNoResponseReviews {
    PreparedNoResponseReview snapshot(Long itemId, Long authorizedManagerId);
    PreparedNoResponseReview review(PreparedNoResponseReview source);
}
