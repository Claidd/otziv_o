package com.hunt.otziv.client_chat_control.dto;

import com.hunt.otziv.client_chat_control.service.ClientChatNoResponseAiReviewService.Review;
import java.time.LocalDateTime;

/** Server-only evidence: never accepted from an HTTP request or serialized in a response. */
public record PreparedNoResponseReview(
        Long itemId, Long messageId, String messageText, LocalDateTime messageAt, Long managerId, Review review
) {}
