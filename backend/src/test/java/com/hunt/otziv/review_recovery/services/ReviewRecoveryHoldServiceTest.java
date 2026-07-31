package com.hunt.otziv.review_recovery.services;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewRecoveryHoldServiceTest {

    @Mock
    private ReviewRecoveryBatchRepository batchRepository;
    @Mock
    private ReviewRecoveryGateService recoveryGateService;
    @InjectMocks
    private ReviewRecoveryHoldService service;

    @Test
    void releaseRecordsHoldDurationWithoutChangingOrderStatusCycle() {
        LocalDateTime statusChangedAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        LocalDateTime waitingChangedAt = LocalDateTime.of(2026, 7, 20, 11, 0);
        Order order = new Order();
        order.setId(77L);
        order.setStatusChangedAt(statusChangedAt);
        order.setWaitingForClient(true);
        order.setWaitingForClientChangedAt(waitingChangedAt);
        ReviewRecoveryBatch batch = ReviewRecoveryBatch.builder()
                .id(9L)
                .order(order)
                .holdStartedAt(Instant.parse("2026-07-20T02:00:00Z"))
                .clientNotifiedAt(Instant.parse("2026-07-20T05:00:00Z"))
                .build();

        when(batchRepository.findById(9L)).thenReturn(Optional.of(batch));

        service.releaseDeadlineHold(batch);

        assertEquals(10_800, batch.getDeadlineShiftSeconds());
        assertNotNull(batch.getHoldReleasedAt());
        assertNotNull(batch.getDeadlineShiftAppliedAt());
        assertEquals(statusChangedAt, order.getStatusChangedAt());
        assertEquals(waitingChangedAt, order.getWaitingForClientChangedAt());
        verify(batchRepository).save(batch);
    }
}
