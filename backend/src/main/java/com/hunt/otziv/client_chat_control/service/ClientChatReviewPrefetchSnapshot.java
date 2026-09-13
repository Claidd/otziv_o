package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A short, scalar read; the transaction ends before any provider call. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
public class ClientChatReviewPrefetchSnapshot {
    private final ClientChatUnansweredItemRepository repository;

    public Optional<PreparedNoResponseReview> read(Long itemId) {
        return repository.findReviewSnapshot(itemId, ClientChatUnansweredStatus.OPEN);
    }

    public List<Long> recent(LocalDateTime from) {
        return repository.findRecentReviewCandidateIds(ClientChatUnansweredStatus.OPEN, from, PageRequest.of(0, 64));
    }
}
