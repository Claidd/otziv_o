package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveControlRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveControlRepository.LiveControlProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferOfferServiceTest {

    @Mock private WorkloadTransferOfferRepository offerRepository;
    @Mock private WorkloadTransferWorkflowRepository workflowRepository;
    @Mock private WorkloadLiveSettingsService liveSettingsService;
    @Mock private WorkloadLiveControlRepository liveControlRepository;
    @Mock private LiveControlProjection liveControl;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;
    @Mock private WorkloadForcedSingleRecipientService forcedSingleRecipientService;

    private WorkloadTransferOfferService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadTransferOfferService(
                offerRepository,
                workflowRepository,
                liveSettingsService,
                shadowSettingsService,
                forcedSingleRecipientService,
                liveControlRepository
        );
        lenient().when(shadowSettingsService.current()).thenReturn(null);
        lenient().when(shadowSettingsService.zone(null))
                .thenReturn(ZoneId.of("Asia/Irkutsk"));
        lenient().when(liveControlRepository.lockState()).thenReturn(Optional.of(liveControl));
        lenient().when(liveControl.getSettingsRevision()).thenReturn(1L);
        lenient().when(liveControl.getMode()).thenReturn("LIVE");
        lenient().when(liveControl.getApplyEnabled()).thenReturn("true");
    }

    @Test
    void disabledContourDoesNotTouchTheOfferQueue() {
        WorkloadLiveSettingsResponse settings = settings("SHADOW", false);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(false);

        var result = service.stageNextOffers();

        assertThat(result.enabled()).isFalse();
        assertThat(result.staged()).isZero();
        verifyNoInteractions(offerRepository, workflowRepository);
    }

    @Test
    void stagesOnlyCandidatesWhoseManagerIsAllowed() {
        WorkloadLiveSettingsResponse settings = settings("CANARY", true);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(offerRepository.lockDueOfferIds(any()))
                .thenReturn(List.of(501L, 502L));

        when(offerRepository.insertEligibleOfferBatch(
                anyBoolean(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyInt()
        )).thenReturn(1);
        when(offerRepository.markReadyOfferBatchOffered(
                anyBoolean(),
                anyString(),
                anyString(),
                any()
        )).thenReturn(2);

        var result = service.stageNextOffers();

        assertThat(result.enabled()).isTrue();
        assertThat(result.staged()).isEqualTo(1);
        assertThat(result.expired()).isEqualTo(2);
        verify(offerRepository).expireDueOffers(any());
        ArgumentCaptor<String> stagingBatchToken =
                ArgumentCaptor.forClass(String.class);
        verify(offerRepository).insertEligibleOfferBatch(
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("[21]"),
                stagingBatchToken.capture(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(25)
        );
        verify(offerRepository).markReadyOfferBatchOffered(
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("[21]"),
                stagingBatchToken.capture(),
                any()
        );
        assertThat(stagingBatchToken.getAllValues()).hasSize(2);
        assertThat(stagingBatchToken.getAllValues().get(0))
                .isEqualTo(stagingBatchToken.getAllValues().get(1));
        assertThatCodeIsUuid(stagingBatchToken.getAllValues().get(0));
        verify(workflowRepository, org.mockito.Mockito.times(2))
                .markExhaustedWorkflows(any());
    }

    @Test
    void staleHeadCandidateIsSkippedAndNextCandidateIsStagedInSameRun() {
        WorkloadLiveSettingsResponse settings = settings("CANARY", true);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(offerRepository.skipUnavailableWaitingCandidates(any()))
                .thenReturn(1);
        when(offerRepository.insertEligibleOfferBatch(
                anyBoolean(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyInt()
        )).thenReturn(1);
        when(offerRepository.markReadyOfferBatchOffered(
                anyBoolean(),
                anyString(),
                anyString(),
                any()
        ))
                .thenReturn(2);

        var result = service.stageNextOffers();

        assertThat(result.staged()).isEqualTo(1);
        verify(offerRepository).skipUnavailableWaitingCandidates(any());
        verify(offerRepository).insertEligibleOfferBatch(
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("[21]"),
                anyString(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(25)
        );
        verify(workflowRepository, org.mockito.Mockito.times(2))
                .markExhaustedWorkflows(any());
    }

    @Test
    void concurrentDuplicateIsABenignZeroRowBatch() {
        WorkloadLiveSettingsResponse settings = settings("LIVE", true);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(offerRepository.insertEligibleOfferBatch(
                anyBoolean(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyInt()
        )).thenReturn(0);

        var result = service.stageNextOffers();

        assertThat(result.staged()).isZero();
        verify(offerRepository, org.mockito.Mockito.never())
                .markReadyOfferBatchOffered(
                        anyBoolean(),
                        anyString(),
                        anyString(),
                        any()
                );
    }

    @Test
    void partialBatchBindingFailsAndRollsTheTransactionalStageBack() {
        WorkloadLiveSettingsResponse settings = settings("LIVE", true);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(offerRepository.insertEligibleOfferBatch(
                anyBoolean(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyInt()
        )).thenReturn(1);
        when(offerRepository.markReadyOfferBatchOffered(
                anyBoolean(),
                anyString(),
                anyString(),
                any()
        )).thenReturn(1);

        assertThatThrownBy(service::stageNextOffers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inserted=1")
                .hasMessageContaining("marked=1")
                .hasMessageContaining("expected=2");
    }

    @Test
    void zeroBatchBindingFailsAndRollsTheTransactionalStageBack() {
        WorkloadLiveSettingsResponse settings = settings("LIVE", true);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(offerRepository.insertEligibleOfferBatch(
                anyBoolean(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyInt()
        )).thenReturn(1);
        when(offerRepository.markReadyOfferBatchOffered(
                anyBoolean(),
                anyString(),
                anyString(),
                any()
        )).thenReturn(0);

        assertThatThrownBy(service::stageNextOffers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inserted=1")
                .hasMessageContaining("marked=0")
                .hasMessageContaining("expected=2");
    }

    @Test
    void canaryClaimUsesOnlyCurrentlyAllowedManagers() {
        WorkloadLiveSettingsResponse settings = settings("CANARY", true);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(offerRepository.claimDueOffers(
                anyString(),
                anyBoolean(),
                anyString(),
                any(),
                any(),
                anyInt()
        )).thenReturn(2);
        var first = mock(WorkloadTransferOfferRepository.DeliveryProjection.class);
        var second = mock(WorkloadTransferOfferRepository.DeliveryProjection.class);
        when(offerRepository.findClaimedOffers(anyString()))
                .thenReturn(List.of(first, second));

        var claimed = service.claimDueOffers();

        assertThat(claimed.processingToken()).isNotBlank();
        assertThat(claimed.offers()).containsExactly(first, second);
        assertThat(claimed.responseTimeoutMinutes()).isEqualTo(30);
        verify(offerRepository).claimDueOffers(
                org.mockito.ArgumentMatchers.eq(claimed.processingToken()),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("[21]"),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(25)
        );
    }

    @Test
    void claimReleasesUnavailableUndeliveredOfferAndStagesNextCandidate() {
        WorkloadLiveSettingsResponse settings = settings("LIVE", true);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(offerRepository.releaseUnavailableUndeliveredOffers(any()))
                .thenReturn(3);
        when(offerRepository.insertEligibleOfferBatch(
                anyBoolean(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyInt()
        )).thenReturn(1);
        when(offerRepository.markReadyOfferBatchOffered(
                anyBoolean(),
                anyString(),
                anyString(),
                any()
        ))
                .thenReturn(2);
        when(offerRepository.claimDueOffers(
                anyString(),
                anyBoolean(),
                anyString(),
                any(),
                any(),
                anyInt()
        )).thenReturn(1);
        var delivery =
                mock(WorkloadTransferOfferRepository.DeliveryProjection.class);
        when(offerRepository.findClaimedOffers(anyString()))
                .thenReturn(List.of(delivery));

        var claimed = service.claimDueOffers();

        assertThat(claimed.offers()).containsExactly(delivery);
        verify(offerRepository).insertEligibleOfferBatch(
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("[21]"),
                anyString(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(25)
        );
        InOrder order = inOrder(offerRepository);
        order.verify(offerRepository)
                .releaseUnavailableUndeliveredOffers(any());
        order.verify(offerRepository).claimDueOffers(
                org.mockito.ArgumentMatchers.eq(claimed.processingToken()),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("[21]"),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(25)
        );
    }

    @Test
    void responseDeadlineStartsWhenTelegramDeliverySucceeds() {
        when(offerRepository.markDelivered(
                anyLong(),
                anyString(),
                anyInt(),
                any(),
                any()
        )).thenReturn(1);

        service.markDelivered(51L, "lease-token", 601, 17);

        ArgumentCaptor<LocalDateTime> deliveredAt =
                ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> expiresAt =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(offerRepository).markDelivered(
                org.mockito.ArgumentMatchers.eq(51L),
                org.mockito.ArgumentMatchers.eq("lease-token"),
                org.mockito.ArgumentMatchers.eq(601),
                deliveredAt.capture(),
                expiresAt.capture()
        );
        assertThat(Duration.between(
                deliveredAt.getValue(),
                expiresAt.getValue()
        )).isEqualTo(Duration.ofMinutes(17));
    }

    private void assertThatCodeIsUuid(String value) {
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }

    private WorkloadLiveSettingsResponse settings(String mode, boolean applyEnabled) {
        return new WorkloadLiveSettingsResponse(
                mode,
                applyEnabled,
                "2026-08-01",
                14,
                168,
                1,
                List.of(21L),
                30,
                "00:00",
                "23:59",
                1,
                3,
                30,
                5,
                false,
                1
        );
    }
}
