package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.p_products.next_order.dto.NextOrderRequestedEvent;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NextOrderRequestRecoveryServiceTest {

    @Mock private NextOrderRequestRepository requestRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Test
    void republishesOnlyBoundedStaleDurableRequests() {
        LocalDateTime dueBefore = LocalDateTime.of(2026, 9, 1, 10, 0);
        Set<NextOrderRequestStatus> statuses = Set.of(
                NextOrderRequestStatus.PENDING,
                NextOrderRequestStatus.FAILED
        );
        when(requestRepository.findStaleRequestIds(
                org.mockito.ArgumentMatchers.eq(statuses),
                org.mockito.ArgumentMatchers.eq(dueBefore),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(List.of(11L, 12L));

        int recovered = new NextOrderRequestRecoveryService(requestRepository, eventPublisher)
                .republishStaleRequests(dueBefore, 50);

        assertEquals(2, recovered);
        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(requestRepository).findStaleRequestIds(
                org.mockito.ArgumentMatchers.eq(statuses),
                org.mockito.ArgumentMatchers.eq(dueBefore),
                pageCaptor.capture()
        );
        assertEquals(50, pageCaptor.getValue().getPageSize());
        verify(eventPublisher).publishEvent(new NextOrderRequestedEvent(11L));
        verify(eventPublisher).publishEvent(new NextOrderRequestedEvent(12L));
    }
}
