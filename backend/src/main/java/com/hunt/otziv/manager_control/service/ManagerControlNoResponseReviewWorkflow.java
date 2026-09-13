package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview;
import com.hunt.otziv.client_chat_control.api.ClientChatNoResponseReviews;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Authorize and read briefly, then call the provider with no transaction/connection held. */
@Service
@RequiredArgsConstructor
public class ManagerControlNoResponseReviewWorkflow {
    private final ManagerControlNoResponseSnapshot snapshot;
    private final ClientChatNoResponseReviews reviews;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PreparedNoResponseReview prepare(Long cardId, ManagerControlItemActionRequest request,
                                           Principal principal, Authentication authentication) {
        if (request == null || !"ACKNOWLEDGED".equalsIgnoreCase(
                request.actionType() == null ? "" : request.actionType().trim())) {
            return null;
        }
        var source = snapshot.read(cardId, principal, authentication);
        if (source == null) {
            return null;
        }
        return com.hunt.otziv.config.metrics.PerformanceMetrics.segment(
                "manager-control.action", "no-response-review", () -> reviews.review(source));
    }
}
