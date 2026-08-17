package com.hunt.otziv.contractor_payments.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;

class ContractorPaymentReconciliationDispatcherTest {

    private final ContractorPaymentAllocationRepository repository =
            mock(ContractorPaymentAllocationRepository.class);
    private final ContractorPaymentReconcileClaimService claimService =
            mock(ContractorPaymentReconcileClaimService.class);
    private final ContractorPaymentShadowService shadowService =
            mock(ContractorPaymentShadowService.class);
    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private final ContractorPaymentReconciliationDispatcher dispatcher =
            new ContractorPaymentReconciliationDispatcher(
                    repository, claimService, shadowService, appSettingService
            );

    @BeforeEach
    void useDatabaseClock() {
        when(repository.currentDatabaseTime())
                .thenReturn(LocalDateTime.of(2026, 8, 7, 12, 0));
    }

    @Test
    void poisonAllocationIsQuarantinedWithoutBlockingNextClaim() {
        ContractorPaymentAllocation poison = allocation(1L);
        ContractorPaymentAllocation healthy = allocation(2L);
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        when(repository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.LIVE), anyCollection(), anyCollection(),
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(List.of(poison, healthy));
        when(repository.findCommonInvoicesForReconciliation(
                eq(ContractorAllocationMode.LIVE), anyCollection(), anyCollection(),
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(List.of());
        when(claimService.tryClaim(eq(1L), any(LocalDateTime.class))).thenReturn(Optional.of("claim-1"));
        when(claimService.tryClaim(eq(2L), any(LocalDateTime.class))).thenReturn(Optional.of("claim-2"));
        doThrow(new ContractorReconciliationRequiredException("missing amount"))
                .when(shadowService).reconcileAllocationId(1L);

        dispatcher.reconcile();

        verify(claimService).failed(
                eq(1L), eq("claim-1"), any(ContractorReconciliationRequiredException.class),
                any(LocalDateTime.class)
        );
        verify(claimService).succeeded(2L, "claim-2");
        InOrder order = inOrder(shadowService);
        order.verify(shadowService).reconcileAllocationId(1L);
        order.verify(shadowService).reconcileAllocationId(2L);
    }

    @Test
    void allocationWithActiveLeaseIsSkippedWhenAtomicClaimLoses() {
        ContractorPaymentAllocation candidate = allocation(3L);
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        when(repository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.LIVE), anyCollection(), anyCollection(),
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(List.of(candidate));
        when(repository.findCommonInvoicesForReconciliation(
                eq(ContractorAllocationMode.LIVE), anyCollection(), anyCollection(),
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(List.of());
        when(claimService.tryClaim(eq(3L), any(LocalDateTime.class))).thenReturn(Optional.empty());

        dispatcher.reconcile();

        verify(claimService).tryClaim(eq(3L), any(LocalDateTime.class));
        org.mockito.Mockito.verifyNoInteractions(shadowService);
    }

    @Test
    void disabledCreationToggleStillDispatchesExistingShadowAllocation() {
        ContractorPaymentAllocation shadow = allocation(4L);
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        when(repository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.SHADOW), anyCollection(), anyCollection(),
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(List.of(shadow));
        when(repository.findCommonInvoicesForReconciliation(
                eq(ContractorAllocationMode.SHADOW), anyCollection(), anyCollection(),
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(List.of());
        when(repository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.LIVE), anyCollection(), anyCollection(),
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(List.of());
        when(repository.findCommonInvoicesForReconciliation(
                eq(ContractorAllocationMode.LIVE), anyCollection(), anyCollection(),
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(List.of());
        when(claimService.tryClaim(eq(4L), any(LocalDateTime.class)))
                .thenReturn(Optional.of("claim-4"));

        dispatcher.reconcile();

        verify(shadowService).reconcileAllocationId(4L);
        verify(claimService).succeeded(4L, "claim-4");
    }

    private ContractorPaymentAllocation allocation(Long id) {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(id);
        return allocation;
    }
}
