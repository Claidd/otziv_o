package com.hunt.otziv.performers.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.performers.model.*;
import com.hunt.otziv.performers.repository.PerformerNotificationRepository;
import com.hunt.otziv.performers.repository.PerformerNotificationRepository.Intent;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerformerNotificationServiceTest {
    @Mock PerformerNotificationRepository repository;
    @Mock PerformerMutationLockService locks;
    @Mock PerformerTelegramNotificationService telegram;
    @Mock jakarta.persistence.EntityManager entityManager;
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 7, 12, 0);

    @Test
    void offerDeadlineStartsAtAcknowledgementAndIsNotExtendedByReplay() {
        var offer = ReviewPerformerOffer.builder().responseTtlMinutes(10).expiresAt(now.minusHours(1))
                .deliveryState("PENDING").build();
        assertThat(PerformerAssignmentService.deadlineApplies(offer)).isFalse();
        PerformerNotificationService.confirmOffer(offer, 42, now);
        assertThat(offer.getExpiresAt()).isEqualTo(now.plusMinutes(10));
        assertThat(offer.getDeliveredAt()).isEqualTo(now);
        PerformerNotificationService.confirmOffer(offer, 42, now.plusMinutes(7));
        assertThat(offer.getExpiresAt()).isEqualTo(now.plusMinutes(10));
    }

    @Test
    void unknownDeliveryDoesNotStartDeadline() {
        var offer = offer();
        var intent = intent("OFFER", "PROCESSING", 0);
        when(locks.offer(3L)).thenReturn(offer);
        when(repository.finish(intent, "UNKNOWN", null, "transport_outcome_unknown")).thenReturn(true);
        service().complete(intent, null);
        assertThat(offer.getDeliveryState()).isEqualTo("UNKNOWN");
        assertThat(offer.getDeliveredAt()).isNull();
        assertThat(PerformerAssignmentService.deadlineApplies(offer)).isFalse();
        verifyNoInteractions(telegram);
    }

    @Test
    void staleSenderCannotChangeOfferDeadline() {
        var offer = offer();
        when(locks.offer(3L)).thenReturn(offer);
        service().complete(intent("OFFER", "PROCESSING", 0), 42);
        assertThat(offer.getDeliveryState()).isEqualTo("PENDING");
        assertThat(offer.getDeliveredAt()).isNull();
    }

    @Test
    void oldPublicationGenerationIsCancelledWithoutSending() {
        var assignment = ReviewPerformerAssignment.builder().id(2L)
                .status(PerformerAssignmentStatus.WAITING_PUBLICATION).publicationGeneration(2).build();
        var intent = new Intent(1L, 2L, null, "READY", 1, "PROCESSING", "token", 1, null, null, now);
        when(locks.assignment(2L)).thenReturn(assignment);
        when(repository.owns(intent)).thenReturn(true);
        assertThat(service().prepare(intent)).isEmpty();
        verify(repository).finish(intent, "CANCELLED", null, "business_state_changed");
        verifyNoInteractions(telegram);
    }

    @Test
    void explicitResolutionRequiresEvidenceAndDoesNotInventSentForLegacy() {
        var intent = intent("OFFER", "LEGACY_UNKNOWN", 0);
        var offer = offer();
        when(repository.get(1L)).thenReturn(Optional.of(intent));
        when(locks.assignment(2L)).thenReturn(offer.getAssignment());
        when(locks.offer(3L)).thenReturn(offer);
        assertThatThrownBy(() -> service().resolve(1L, "CONFIRM_SENT", null, "checked", "admin"))
                .hasMessageContaining("messageId");
        verify(repository, never()).resolve(any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleReadyCanNeverBeRequeuedByOperator() {
        var intent = new Intent(1L, 2L, null, "READY", 0, "LEGACY_UNKNOWN", null, 0, null, null, now);
        when(repository.get(1L)).thenReturn(Optional.of(intent));
        when(locks.assignment(2L)).thenReturn(ReviewPerformerAssignment.builder().id(2L)
                .status(PerformerAssignmentStatus.PUBLISHED_CLAIMED).build());
        assertThatThrownBy(() -> service().resolve(1L, "CONFIRM_NOT_SENT_REQUEUE", null, "checked", "admin"))
                .hasMessageContaining("больше не требует");
        verify(repository, never()).resolve(any(), any(), any(), any(), any(), any());
    }

    @Test
    void readyFlushesManagedGenerationBeforeInsertingIntentAndMarker() {
        var assignment = ReviewPerformerAssignment.builder().id(2L).publicationGeneration(3)
                .publishAvailableAt(now).build();
        service().ready(assignment);
        var ordered = inOrder(entityManager, repository);
        ordered.verify(entityManager).flush();
        ordered.verify(repository).enqueue(2L, null, "READY", 3, now);
    }

    private PerformerNotificationService service() { return new PerformerNotificationService(repository, locks, telegram, entityManager); }
    private Intent intent(String type, String status, long generation) {
        return new Intent(1L, 2L, 3L, type, generation, status, "token", 1, null, null, now);
    }
    private ReviewPerformerOffer offer() {
        return ReviewPerformerOffer.builder().id(3L).status(PerformerOfferStatus.OFFERED).deliveryState("PENDING")
                .assignment(ReviewPerformerAssignment.builder().id(2L).status(PerformerAssignmentStatus.OFFERING).build())
                .expiresAt(now.minusHours(1)).build();
    }
}
