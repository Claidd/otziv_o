package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.contractor_payments.service.ContractorShadowBackfillClaimService;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ContractorShadowRouteBackfillServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private CommonInvoiceRepository commonInvoiceRepository;
    @Mock
    private ContractorPaymentShadowService contractorPaymentShadowService;
    @Mock
    private ContractorShadowBackfillClaimService claimService;
    @Mock
    private AppSettingService appSettingService;

    private ContractorShadowRouteBackfillService service;

    @BeforeEach
    void setUp() {
        service = new ContractorShadowRouteBackfillService(
                paymentLinkRepository,
                commonInvoiceRepository,
                contractorPaymentShadowService,
                claimService,
                appSettingService
        );
        lenient().when(appSettingService.getString(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_BACKFILL_STARTED_AT,
                ""
        )).thenReturn("2026-08-07T00:00:00");
        lenient().when(appSettingService.getString(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_PREPARATION_STARTED_AT,
                ""
        )).thenReturn("2026-08-07T00:05:00");
    }

    @Test
    void capsBatchAndStillScansExistingShadowEvidenceWhenCreationIsDisabled() {
        when(appSettingService.getBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED,
                true
        )).thenReturn(false);
        when(appSettingService.getInt("contractor-payments.shadow-backfill-batch-size", 100))
                .thenReturn(50_000);
        when(paymentLinkRepository.findUnrecordedContractorManualCardEvidence(
                any(LocalDateTime.class),
                eq(true),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(List.of());

        service.backfillMissingShadowRoutes();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentLinkRepository).findUnrecordedContractorManualCardEvidence(
                any(LocalDateTime.class),
                eq(true),
                any(LocalDateTime.class),
                page.capture()
        );
        assertEquals(1_000, page.getValue().getPageSize());
        verify(paymentLinkRepository, never()).findMissingContractorShadowRouteIds(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(Pageable.class)
        );
        verify(commonInvoiceRepository, never()).findMissingContractorShadowRouteIds(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(Pageable.class)
        );
    }

    @Test
    void retriesManualEvidenceIdempotentlyAfterRouteBackfill() {
        when(appSettingService.getBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED,
                true
        )).thenReturn(true);
        when(appSettingService.getInt("contractor-payments.shadow-backfill-batch-size", 100))
                .thenReturn(100);
        when(paymentLinkRepository.findMissingContractorShadowRouteIds(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(List.of());
        when(commonInvoiceRepository.findMissingContractorShadowRouteIds(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(List.of());
        PaymentLinkRepository.ManualCardShadowEvidenceView evidence =
                mock(PaymentLinkRepository.ManualCardShadowEvidenceView.class);
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 7, 12, 30);
        when(evidence.getOriginalLinkId()).thenReturn(10L);
        when(evidence.getEvidenceLinkId()).thenReturn(11L);
        when(evidence.getPaidAt()).thenReturn(paidAt);
        when(paymentLinkRepository.findUnrecordedContractorManualCardEvidence(
                any(LocalDateTime.class),
                eq(true),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(List.of(evidence));
        when(claimService.tryClaim(eq("MANUAL_EVIDENCE"), eq(11L), any(LocalDateTime.class)))
                .thenReturn(Optional.of("claim"));
        when(contractorPaymentShadowService.recordManualCardPaymentEvidence(10L, 11L, paidAt))
                .thenReturn(true);

        service.backfillMissingShadowRoutes();

        verify(contractorPaymentShadowService).recordManualCardPaymentEvidence(10L, 11L, paidAt);
        verify(claimService).succeeded(eq("MANUAL_EVIDENCE"), eq(11L), eq("claim"), any());
    }

    @Test
    void malformedRolloutBoundaryFailsClosedWithoutScanningPayments() {
        when(appSettingService.getBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED,
                true
        )).thenReturn(true);
        when(appSettingService.getString(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_BACKFILL_STARTED_AT,
                ""
        )).thenReturn("not-a-date");

        service.backfillMissingShadowRoutes();

        verify(paymentLinkRepository, never()).findMissingContractorShadowRouteIds(any(), any(), any(), any());
        verify(paymentLinkRepository, never()).findUnrecordedContractorManualCardEvidence(
                any(), anyBoolean(), any(), any()
        );
        verify(commonInvoiceRepository, never()).findMissingContractorShadowRouteIds(any(), any(), any(), any());
        verify(appSettingService, never()).getInt(anyString(), anyInt());
    }

    @Test
    void malformedPreparationBoundaryFailsClosedWithoutScanningPayments() {
        when(appSettingService.getBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED,
                true
        )).thenReturn(true);
        when(appSettingService.getString(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_PREPARATION_STARTED_AT,
                ""
        )).thenReturn("not-a-date");

        service.backfillMissingShadowRoutes();

        verify(paymentLinkRepository, never()).findMissingContractorShadowRouteIds(
                any(), any(), any(), any()
        );
        verify(paymentLinkRepository, never()).findUnrecordedContractorManualCardEvidence(
                any(), anyBoolean(), any(), any()
        );
        verify(commonInvoiceRepository, never()).findMissingContractorShadowRouteIds(
                any(), any(), any(), any()
        );
        verify(appSettingService, never()).getInt(anyString(), anyInt());
    }

    @Test
    void poisonMissingRouteIsBackedOffWithoutBlockingNextSource() {
        when(appSettingService.getBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true
        )).thenReturn(true);
        when(appSettingService.getInt("contractor-payments.shadow-backfill-batch-size", 100))
                .thenReturn(100);
        when(paymentLinkRepository.findMissingContractorShadowRouteIds(any(), any(), any(), any()))
                .thenReturn(List.of(21L, 22L));
        when(commonInvoiceRepository.findMissingContractorShadowRouteIds(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(paymentLinkRepository.findUnrecordedContractorManualCardEvidence(any(), eq(true), any(), any()))
                .thenReturn(List.of());
        when(claimService.tryClaim(eq("PAYMENT_LINK"), eq(21L), any()))
                .thenReturn(Optional.of("claim-21"));
        when(claimService.tryClaim(eq("PAYMENT_LINK"), eq(22L), any()))
                .thenReturn(Optional.of("claim-22"));
        when(contractorPaymentShadowService.reserveForPaymentLinkIdOutcome(21L))
                .thenReturn(new ContractorPaymentShadowService.ShadowReservationResult(
                        ContractorPaymentShadowService.ShadowReservationOutcome.NOT_PREPARED_OR_INCONSISTENT,
                        null
                ));
        when(contractorPaymentShadowService.reserveForPaymentLinkIdOutcome(22L))
                .thenReturn(new ContractorPaymentShadowService.ShadowReservationResult(
                        ContractorPaymentShadowService.ShadowReservationOutcome.CREATED,
                        new ContractorPaymentAllocation()
                ));

        service.backfillMissingShadowRoutes();

        verify(claimService).failed(
                eq("PAYMENT_LINK"), eq(21L), eq("claim-21"), any(IllegalStateException.class), any()
        );
        verify(claimService).succeeded(eq("PAYMENT_LINK"), eq(22L), eq("claim-22"), any());
        verify(contractorPaymentShadowService).reserveForPaymentLinkIdOutcome(22L);
    }

    @Test
    void missingPreparationSnapshotIsNotMarkedCompletedAndCanRetryLater() {
        when(appSettingService.getBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true
        )).thenReturn(true);
        when(appSettingService.getInt("contractor-payments.shadow-backfill-batch-size", 100))
                .thenReturn(100);
        when(paymentLinkRepository.findMissingContractorShadowRouteIds(any(), any(), any(), any()))
                .thenReturn(List.of(31L));
        when(commonInvoiceRepository.findMissingContractorShadowRouteIds(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(paymentLinkRepository.findUnrecordedContractorManualCardEvidence(any(), eq(true), any(), any()))
                .thenReturn(List.of());
        when(claimService.tryClaim(eq("PAYMENT_LINK"), eq(31L), any()))
                .thenReturn(Optional.of("claim-31"), Optional.of("claim-31-retry"));
        when(contractorPaymentShadowService.reserveForPaymentLinkIdOutcome(31L))
                .thenReturn(new ContractorPaymentShadowService.ShadowReservationResult(
                        ContractorPaymentShadowService.ShadowReservationOutcome.NOT_PREPARED_OR_INCONSISTENT,
                        null
                ), new ContractorPaymentShadowService.ShadowReservationResult(
                        ContractorPaymentShadowService.ShadowReservationOutcome.CREATED,
                        new ContractorPaymentAllocation()
                ));

        service.backfillMissingShadowRoutes();
        service.backfillMissingShadowRoutes();

        verify(claimService).failed(
                eq("PAYMENT_LINK"), eq(31L), eq("claim-31"), any(IllegalStateException.class), any()
        );
        verify(claimService).succeeded(
                eq("PAYMENT_LINK"), eq(31L), eq("claim-31-retry"), any()
        );
    }
}
