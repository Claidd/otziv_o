package com.hunt.otziv.workload_shadow.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowEventRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository.ForcedSingleRecipientProjection;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadForcedSingleRecipientServiceTest {

    @Mock private WorkloadTransferOfferRepository offerRepository;
    @Mock private WorkloadShadowEventRepository eventRepository;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;
    @Mock private WorkloadShadowSettingsResponse shadowSettings;

    private WorkloadForcedSingleRecipientService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadForcedSingleRecipientService(
                offerRepository,
                eventRepository,
                shadowSettingsService
        );
    }

    @Test
    void noForcedWorkflowDoesNotCreateOwnerEvent() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 12, 0);
        when(offerRepository.lockSingleRecipientForcedTransfers(isNull()))
                .thenReturn(List.of());

        service.acceptExhaustedQueues(now);

        verifyNoInteractions(shadowSettingsService, eventRepository);
    }

    @Test
    void forcedWorkflowCreatesAdminOwnerEvent() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 12, 0);
        when(offerRepository.lockSingleRecipientForcedTransfers(eq(501L)))
                .thenReturn(List.of(target(501L, 601L, 701L, 1)));
        when(offerRepository.forceSingleRecipientAcceptedAfterNoResponse(
                eq(501L),
                eq(601L),
                eq(701L),
                eq(now),
                eq(WorkloadForcedSingleRecipientService.FORCE_REASON)
        )).thenReturn(3);
        when(shadowSettingsService.current()).thenReturn(shadowSettings);
        when(shadowSettings.groupNotificationsEnabled()).thenReturn(true);
        when(shadowSettings.notificationGroupChatId()).thenReturn(-100500L);

        service.acceptExhaustedWorkflow(501L, now);

        verify(eventRepository).upsertSingleRecipientForcedTransferEvents(
                eq(now),
                eq(WorkloadForcedSingleRecipientService.FORCE_REASON),
                eq(true),
                eq(-100500L),
                any(LocalDateTime.class)
        );
    }

    @Test
    void exhaustedMultiCandidateWorkflowUsesOneLotteryWinnerAndCreatesLotteryEvent() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 12, 0);
        when(offerRepository.lockSingleRecipientForcedTransfers(eq(501L)))
                .thenReturn(List.of(
                        target(501L, 602L, 702L, 2),
                        target(501L, 603L, 703L, 2)
                ));
        when(offerRepository.forceSingleRecipientAcceptedAfterNoResponse(
                eq(501L),
                eq(602L),
                eq(702L),
                eq(now),
                eq(WorkloadForcedSingleRecipientService.LOTTERY_FORCE_REASON)
        )).thenReturn(3);
        when(shadowSettingsService.current()).thenReturn(shadowSettings);
        when(shadowSettings.groupNotificationsEnabled()).thenReturn(true);
        when(shadowSettings.notificationGroupChatId()).thenReturn(-100500L);

        service.acceptExhaustedWorkflow(501L, now);

        verify(offerRepository).forceSingleRecipientAcceptedAfterNoResponse(
                eq(501L),
                eq(602L),
                eq(702L),
                eq(now),
                eq(WorkloadForcedSingleRecipientService.LOTTERY_FORCE_REASON)
        );
        verify(offerRepository, never()).forceSingleRecipientAcceptedAfterNoResponse(
                eq(501L),
                eq(603L),
                eq(703L),
                eq(now),
                eq(WorkloadForcedSingleRecipientService.LOTTERY_FORCE_REASON)
        );
        verify(eventRepository).upsertExhaustedQueueForcedTransferEvents(
                eq(now),
                eq(WorkloadForcedSingleRecipientService.LOTTERY_FORCE_REASON),
                eq(true),
                eq(-100500L),
                any(LocalDateTime.class)
        );
    }

    private ForcedSingleRecipientProjection target(
            Long workflowId,
            Long candidateId,
            Long offerId,
            Integer candidateCount
    ) {
        return new ForcedSingleRecipientProjection() {
            @Override
            public Long getWorkflowId() {
                return workflowId;
            }

            @Override
            public Long getCandidateId() {
                return candidateId;
            }

            @Override
            public Long getOfferId() {
                return offerId;
            }

            @Override
            public Integer getCandidateCount() {
                return candidateCount;
            }
        };
    }
}