package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.p_products.next_order.dto.NextOrderRequestedEvent;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NextOrderRequestRecoveryService {

    private static final Set<NextOrderRequestStatus> RECOVERABLE_STATUSES = Set.of(
            NextOrderRequestStatus.PENDING,
            NextOrderRequestStatus.FAILED
    );

    private final NextOrderRequestRepository requestRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public int republishStaleRequests(LocalDateTime dueBefore, int batchSize) {
        if (dueBefore == null || batchSize <= 0) {
            return 0;
        }

        var requestIds = requestRepository.findStaleRequestIds(
                RECOVERABLE_STATUSES,
                dueBefore,
                PageRequest.of(0, Math.min(batchSize, 200))
        );
        requestIds.forEach(requestId -> eventPublisher.publishEvent(new NextOrderRequestedEvent(requestId)));
        return requestIds.size();
    }
}
