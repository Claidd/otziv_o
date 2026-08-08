package com.hunt.otziv.archive.service;

import com.hunt.otziv.archive.dto.ArchiveCandidateCounts;
import com.hunt.otziv.archive.dto.ArchiveCandidatesPreview;
import com.hunt.otziv.archive.dto.ArchiveDryRunResult;
import com.hunt.otziv.archive.dto.ArchiveOrdersSettingsRequest;
import com.hunt.otziv.archive.dto.ArchiveOrdersSettingsResponse;
import com.hunt.otziv.archive.dto.ArchiveRunResult;
import com.hunt.otziv.archive.repository.OrderArchiveDryRunRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardLedgerService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.payments.service.PaymentLinkArchiveService;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class OrderArchiveDryRunServiceTest {

    @Mock
    private OrderArchiveDryRunRepository repository;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private PaymentLinkArchiveService paymentLinkArchiveService;

    @Mock
    private ZpRepository zpRepository;

    @Mock
    private ContractorRewardLedgerService contractorRewardLedgerService;

    @Mock
    private ContractorPaymentShadowService contractorPaymentShadowService;

    @Test
    void dryRunCountsCandidatesAndWritesBatchLog() {
        ZoneId zone = ZoneId.of("Asia/Irkutsk");
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), zone);
        OrderArchiveDryRunService service = new OrderArchiveDryRunService(repository, clock);
        service.setDefaultRetentionDays(90);
        service.setDefaultBatchLimit(500);
        service.setMaxBatchLimit(1000);

        LocalDate cutoffDate = LocalDate.of(2026, 2, 9);
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(10, 12, 18, 2, 1, 20, 9);
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(true);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(42L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1))).thenReturn(1L);
        when(repository.insertDryRunBatch(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("manual-check"),
                eq(90),
                eq(42L),
                eq(counts),
                contains("missingClosedAnalyticsMonths=1")
        )).thenReturn(77L);

        ArchiveDryRunResult result = service.runDryRun(null, 200, " manual-check ");

        assertEquals(77L, result.batchId());
        assertTrue(result.dryRun());
        assertEquals(cutoffDate, result.cutoffDate());
        assertEquals(90, result.retentionDays());
        assertEquals(200, result.batchLimit());
        assertEquals(42L, result.eligibleOrders());
        assertEquals(counts, result.selected());
        assertEquals(1L, result.missingClosedAnalyticsMonths());
        assertTrue(result.message().contains("eligibleOrders=42"));
    }

    @Test
    void dryRunLimitsBatchSizeByConfiguredMaximum() {
        ZoneId zone = ZoneId.of("Asia/Irkutsk");
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), zone);
        OrderArchiveDryRunService service = new OrderArchiveDryRunService(repository, clock);
        service.setDefaultRetentionDays(45);
        service.setDefaultBatchLimit(500);
        service.setMaxBatchLimit(300);

        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(0, 0, 0, 0, 0, 0, 0);
        LocalDate cutoffDate = LocalDate.of(2026, 3, 26);
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(true);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(0L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1))).thenReturn(0L);
        when(repository.insertDryRunBatch(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(),
                eq(45),
                eq(0L),
                eq(counts),
                any()
        )).thenReturn(1L);

        ArchiveDryRunResult result = service.runDryRun(null, 10_000, null);

        assertEquals(300, result.batchLimit());
        assertEquals("orders-retention-dry-run", captureArchiveReason());
    }

    @Test
    void dryRunRejectsParallelExecution() {
        OrderArchiveDryRunService service = serviceWithFixedClock();
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(false);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.runDryRun(null, null, null)
        );

        assertEquals("Order archive is already running", exception.getMessage());
    }

    @Test
    void previewCandidatesDoesNotWriteBatchLog() {
        OrderArchiveDryRunService service = serviceWithFixedClock();
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(4, 5, 6, 0, 0, 2, 1);
        LocalDate cutoffDate = LocalDate.of(2026, 2, 9);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(12L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1))).thenReturn(0L);
        when(repository.findPreparedCandidateOrders(8)).thenReturn(List.of());

        ArchiveCandidatesPreview preview = service.previewCandidates(null, 100, null);

        assertEquals(cutoffDate, preview.cutoffDate());
        assertEquals(90, preview.retentionDays());
        assertEquals(100, preview.batchLimit());
        assertEquals(12L, preview.eligibleOrders());
        assertEquals(counts, preview.selected());
        verify(repository).findPreparedCandidateOrders(8);
    }

    @Test
    void liveArchiveRequiresExplicitConfirmation() {
        OrderArchiveDryRunService service = serviceWithFixedClock();
        service.setApplyEnabled(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.runArchive(null, null, null, false)
        );

        assertEquals("Archive run requires confirm=true", exception.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void liveArchiveRequiresFeatureFlag() {
        OrderArchiveDryRunService service = serviceWithFixedClock();
        service.setApplyEnabled(false);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.runArchive(null, null, null, true)
        );

        assertEquals("Order archive apply is disabled", exception.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void liveArchiveCopiesVerifiesDeletesAndCompletesBatch() {
        OrderArchiveDryRunService service = serviceWithFixedClock();
        service.setApplyEnabled(true);
        service.setDefaultRetentionDays(90);
        service.setDefaultBatchLimit(500);
        service.setMaxBatchLimit(1000);

        LocalDate cutoffDate = LocalDate.of(2026, 2, 9);
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(3, 4, 5, 1, 1, 6, 2);
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(true);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(9L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1))).thenReturn(0L);
        when(repository.lockPreparedCandidateOrders()).thenReturn(3);
        when(repository.insertStartedArchiveBatch(any(LocalDateTime.class), eq("small-batch"), eq(90), eq(counts), any()))
                .thenReturn(11L);
        when(repository.countArchivedPreparedCandidates()).thenReturn(counts);
        when(repository.deletePreparedCandidatesFromLive()).thenReturn(counts);

        ArchiveRunResult result = service.runArchive(null, 50, "small-batch", true);

        assertEquals(11L, result.batchId());
        assertEquals(false, result.dryRun());
        assertEquals(counts, result.selected());
        assertEquals(counts, result.archived());
        assertEquals(counts, result.deleted());
        assertEquals(9L, result.eligibleOrders());
        verify(repository).prepareCandidateOrders(cutoffDate, 50);
        verify(repository).lockPreparedCandidateOrders();
        verify(repository).countPreparedCandidateCommonInvoices();
        verify(repository).lockPreparedCandidateCommonInvoices();
        verify(repository).hasPreparedCandidateEligibilityDrift(cutoffDate);
        verify(repository).copyPreparedCandidatesToArchive(eq(11L), any(LocalDateTime.class), eq("small-batch"));
        verify(paymentLinkArchiveService).archiveForPreparedOrderArchiveCandidates(11L);
        verify(repository).completeArchiveBatch(eq(11L), any(LocalDateTime.class), eq(counts), eq(counts), contains("archive completed"));
    }

    @Test
    void unsynchronizedContractorRewardIsPersistedToLedgerBeforeArchiveCopyAndDelete() {
        ZoneId zone = ZoneId.of("Asia/Irkutsk");
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), zone);
        OrderArchiveDryRunService service = new OrderArchiveDryRunService(
                repository,
                null,
                paymentLinkArchiveService,
                zpRepository,
                contractorRewardLedgerService,
                null,
                clock
        );
        service.setApplyEnabled(true);
        service.setDefaultRetentionDays(90);
        service.setDefaultBatchLimit(500);
        service.setMaxBatchLimit(1000);
        LocalDate cutoffDate = LocalDate.of(2026, 2, 9);
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(1, 0, 0, 0, 0, 1, 0);
        Zp reward = new Zp();
        reward.setId(71L);
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(true);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(1L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1)))
                .thenReturn(0L);
        when(repository.lockPreparedCandidateOrders()).thenReturn(1);
        when(repository.findPreparedCandidateContractorRewardIds()).thenReturn(List.of(71L));
        when(zpRepository.findAllById(List.of(71L))).thenReturn(List.of(reward));
        when(repository.insertStartedArchiveBatch(any(), any(), anyInt(), eq(counts), any())).thenReturn(12L);
        when(repository.countArchivedPreparedCandidates()).thenReturn(counts);
        when(repository.deletePreparedCandidatesFromLive()).thenReturn(counts);

        service.runArchive(null, 50, "guard-test", true);

        var archiveOrder = inOrder(contractorRewardLedgerService, repository);
        archiveOrder.verify(contractorRewardLedgerService).synchronizeSources(List.of(reward));
        archiveOrder.verify(repository).copyPreparedCandidatesToArchive(eq(12L), any(), eq("guard-test"));
        archiveOrder.verify(repository).deletePreparedCandidatesFromLive();
    }

    @Test
    void liveArchiveStopsBeforeCopyWhenSelectedOrdersCannotAllBeLocked() {
        OrderArchiveDryRunService service = serviceWithFixedClock();
        service.setApplyEnabled(true);

        LocalDate cutoffDate = LocalDate.of(2026, 2, 9);
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(2, 2, 0, 0, 0, 0, 0);
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(true);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(2L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1)))
                .thenReturn(0L);
        when(repository.lockPreparedCandidateOrders()).thenReturn(1);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.runArchive(null, null, null, true)
        );

        assertEquals("Order archive lock verification failed: selected=2, locked=1", exception.getMessage());
        verify(repository, never()).copyPreparedCandidatesToArchive(anyLong(), any(), any());
        verifyNoInteractions(paymentLinkArchiveService);
    }

    @Test
    void contractorAllocationIsStrictlyReconciledBeforeArchiveCopyAndDelete() {
        ZoneId zone = ZoneId.of("Asia/Irkutsk");
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), zone);
        OrderArchiveDryRunService service = new OrderArchiveDryRunService(
                repository,
                null,
                paymentLinkArchiveService,
                zpRepository,
                contractorRewardLedgerService,
                contractorPaymentShadowService,
                clock
        );
        service.setApplyEnabled(true);
        service.setDefaultRetentionDays(90);
        service.setDefaultBatchLimit(500);
        service.setMaxBatchLimit(1000);
        LocalDate cutoffDate = LocalDate.of(2026, 2, 9);
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(1, 0, 0, 0, 0, 0, 0);
        ContractorPaymentAllocation reconciled = new ContractorPaymentAllocation();
        reconciled.setId(81L);
        reconciled.setStatus(ContractorAllocationStatus.CONFIRMED);
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(true);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(1L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1)))
                .thenReturn(0L);
        when(repository.lockPreparedCandidateOrders()).thenReturn(1);
        when(repository.findPreparedCandidateContractorAllocationIds()).thenReturn(List.of(81L));
        when(contractorPaymentShadowService.reconcileAllocationId(81L)).thenReturn(reconciled);
        when(contractorPaymentShadowService.reconcileAllocationForArchive(81L)).thenReturn(reconciled);
        when(repository.findPreparedCandidateContractorRewardIds()).thenReturn(List.of());
        when(repository.insertStartedArchiveBatch(any(), any(), anyInt(), eq(counts), any())).thenReturn(13L);
        when(repository.countArchivedPreparedCandidates()).thenReturn(counts);
        when(repository.deletePreparedCandidatesFromLive()).thenReturn(counts);

        service.runArchive(null, 50, "allocation-guard", true);

        var archiveOrder = inOrder(contractorPaymentShadowService, repository);
        archiveOrder.verify(contractorPaymentShadowService).reconcileAllocationId(81L);
        archiveOrder.verify(repository).lockPreparedCandidateOrders();
        archiveOrder.verify(contractorPaymentShadowService).reconcileAllocationForArchive(81L);
        archiveOrder.verify(repository).copyPreparedCandidatesToArchive(eq(13L), any(), eq("allocation-guard"));
        archiveOrder.verify(repository).deletePreparedCandidatesFromLive();
    }

    @Test
    void archiveStopsWhenStrictReconciliationStillHasUnresolvedReturnFlag() {
        ZoneId zone = ZoneId.of("Asia/Irkutsk");
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), zone);
        OrderArchiveDryRunService service = new OrderArchiveDryRunService(
                repository,
                null,
                paymentLinkArchiveService,
                zpRepository,
                contractorRewardLedgerService,
                contractorPaymentShadowService,
                clock
        );
        service.setApplyEnabled(true);
        service.setDefaultRetentionDays(90);
        service.setDefaultBatchLimit(500);
        service.setMaxBatchLimit(1000);
        LocalDate cutoffDate = LocalDate.of(2026, 2, 9);
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(1, 0, 0, 0, 0, 0, 0);
        ContractorPaymentAllocation unresolved = new ContractorPaymentAllocation();
        unresolved.setId(82L);
        // The boolean is independently durable. Even if a stale/legacy status
        // looks terminal, archive must not discard the source needed to
        // resolve an unknown return amount.
        unresolved.setStatus(ContractorAllocationStatus.CONFIRMED);
        unresolved.setNeedsReturnAmount(true);
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(true);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(1L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1)))
                .thenReturn(0L);
        when(repository.lockPreparedCandidateOrders()).thenReturn(1);
        when(repository.findPreparedCandidateContractorAllocationIds()).thenReturn(List.of(82L));
        when(contractorPaymentShadowService.reconcileAllocationId(82L)).thenReturn(unresolved);
        when(contractorPaymentShadowService.reconcileAllocationForArchive(82L)).thenReturn(unresolved);

        assertThrows(
                IllegalStateException.class,
                () -> service.runArchive(null, 50, "allocation-block", true)
        );

        verify(repository, never()).copyPreparedCandidatesToArchive(any(), any(), any());
        verify(repository, never()).deletePreparedCandidatesFromLive();
    }

    @Test
    void archiveStopsBeforeParentLocksWhenAccountingPreflightFails() {
        ZoneId zone = ZoneId.of("Asia/Irkutsk");
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), zone);
        OrderArchiveDryRunService service = new OrderArchiveDryRunService(
                repository,
                null,
                paymentLinkArchiveService,
                zpRepository,
                contractorRewardLedgerService,
                contractorPaymentShadowService,
                clock
        );
        service.setApplyEnabled(true);
        service.setDefaultRetentionDays(90);
        service.setDefaultBatchLimit(500);
        service.setMaxBatchLimit(1000);
        LocalDate cutoffDate = LocalDate.of(2026, 2, 9);
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(1, 0, 0, 0, 0, 0, 0);
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(true);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(1L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1)))
                .thenReturn(0L);
        when(repository.findPreparedCandidateContractorAllocationIds()).thenReturn(List.of(83L));
        when(contractorPaymentShadowService.reconcileAllocationId(83L))
                .thenThrow(new IllegalStateException("poison source"));

        assertThrows(
                IllegalStateException.class,
                () -> service.runArchive(null, 50, "preflight-failure", true)
        );

        verify(repository, never()).lockPreparedCandidateOrders();
        verify(repository, never()).copyPreparedCandidatesToArchive(any(), any(), any());
        verify(repository, never()).deletePreparedCandidatesFromLive();
    }

    @Test
    void liveArchiveStopsWhenEligibilityChangesWhileWaitingForParentLocks() {
        OrderArchiveDryRunService service = serviceWithFixedClock();
        service.setApplyEnabled(true);

        LocalDate cutoffDate = LocalDate.of(2026, 2, 9);
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(1, 1, 0, 0, 0, 0, 0);
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(true);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(1L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1)))
                .thenReturn(0L);
        when(repository.lockPreparedCandidateOrders()).thenReturn(1);
        when(repository.hasPreparedCandidateEligibilityDrift(cutoffDate)).thenReturn(true);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.runArchive(null, null, null, true)
        );

        assertEquals(
                "Order archive candidates changed while locks were acquired; retry with a fresh selection",
                exception.getMessage()
        );
        verify(repository, never()).copyPreparedCandidatesToArchive(anyLong(), any(), any());
        verifyNoInteractions(paymentLinkArchiveService);
    }

    @Test
    void liveArchiveStopsWhenClosedAnalyticsMonthsAreMissing() {
        OrderArchiveDryRunService service = serviceWithFixedClock();
        service.setApplyEnabled(true);

        LocalDate cutoffDate = LocalDate.of(2026, 2, 9);
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(1, 1, 1, 0, 0, 1, 1);
        when(repository.tryAcquireArchiveLock("otziv.order-archive", 0)).thenReturn(true);
        when(repository.countEligibleOrders(cutoffDate)).thenReturn(1L);
        when(repository.countPreparedCandidates()).thenReturn(counts);
        when(repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(LocalDate.of(2026, 5, 1))).thenReturn(1L);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.runArchive(null, null, null, true)
        );

        assertEquals("Closed analytics months are missing for selected archive candidates", exception.getMessage());
        verify(repository).prepareCandidateOrders(cutoffDate, 500);
    }

    @Test
    void settingsReadRuntimeArchivePreferences() {
        OrderArchiveDryRunService service = serviceWithSettings();
        service.setDefaultRetentionDays(90);
        service.setDefaultBatchLimit(500);
        service.setMaxBatchLimit(1000);
        when(appSettingService.getInt(AppSettingService.ARCHIVE_ORDERS_RETENTION_DAYS, 90)).thenReturn(75);
        when(appSettingService.getInt(AppSettingService.ARCHIVE_ORDERS_BATCH_SIZE, 500)).thenReturn(300);
        when(appSettingService.getBoolean(AppSettingService.ARCHIVE_ORDERS_APPLY_ENABLED, false)).thenReturn(true);
        when(appSettingService.getBoolean(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_WORKER_ENABLED, false)).thenReturn(true);
        when(appSettingService.getBoolean(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_ENABLED, false)).thenReturn(true);
        when(appSettingService.getString(AppSettingService.ARCHIVE_ORDERS_RUN_MODE, "dry-run")).thenReturn("dry-run");
        when(appSettingService.getString(AppSettingService.ARCHIVE_ORDERS_REASON, "scheduled-orders-retention-dry-run")).thenReturn("nightly-check");
        when(appSettingService.getString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_CRON, "0 15 4 * * *"))
                .thenReturn("0 54 1 * * *");
        when(appSettingService.getString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_ZONE, "Asia/Irkutsk"))
                .thenReturn("Asia/Irkutsk");

        ArchiveOrdersSettingsResponse settings = service.settings();

        assertEquals(90, settings.boardLiveSliceRetentionDays());
        assertEquals(75, settings.archiveRetentionDays());
        assertEquals(300, settings.batchSize());
        assertEquals(1000, settings.maxBatchSize());
        assertTrue(settings.applyEnabled());
        assertTrue(settings.scheduleWorkerEnabled());
        assertTrue(settings.scheduleEnabled());
        assertEquals("dry-run", settings.runMode());
        assertEquals("nightly-check", settings.reason());
        assertEquals("01:54", settings.scheduleTime());
        assertEquals("0 54 1 * * *", settings.scheduleCron());
        assertEquals("Asia/Irkutsk", settings.scheduleZone());
    }

    @Test
    void updateSettingsRejectsLiveModeWhenApplyIsDisabledInRequest() {
        OrderArchiveDryRunService service = serviceWithSettings();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateSettings(new ArchiveOrdersSettingsRequest(
                        60,
                        500,
                        false,
                        true,
                        true,
                        "live",
                        "nightly-live",
                        "01:54",
                        null,
                        "Asia/Irkutsk"
                ))
        );

        assertEquals("Live archive mode requires archive apply enabled", exception.getMessage());
    }

    @Test
    void updateSettingsPersistsSafeRuntimePreferences() {
        OrderArchiveDryRunService service = serviceWithSettings();
        service.setApplyEnabled(true);
        service.setDefaultRetentionDays(90);
        service.setDefaultBatchLimit(500);
        service.setMaxBatchLimit(1000);

        service.updateSettings(new ArchiveOrdersSettingsRequest(
                70,
                10_000,
                true,
                true,
                true,
                "live",
                " nightly-live ",
                "01:54",
                null,
                "Asia/Irkutsk"
        ));

        verify(appSettingService).setInt(AppSettingService.ARCHIVE_ORDERS_RETENTION_DAYS, 70);
        verify(appSettingService).setInt(AppSettingService.ARCHIVE_ORDERS_BATCH_SIZE, 1000);
        verify(appSettingService).setBoolean(AppSettingService.ARCHIVE_ORDERS_APPLY_ENABLED, true);
        verify(appSettingService).setBoolean(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_WORKER_ENABLED, true);
        verify(appSettingService).setBoolean(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_ENABLED, true);
        verify(appSettingService).setString(AppSettingService.ARCHIVE_ORDERS_RUN_MODE, "live");
        verify(appSettingService).setString(AppSettingService.ARCHIVE_ORDERS_REASON, "nightly-live");
        verify(appSettingService).setString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_CRON, "0 54 1 * * *");
        verify(appSettingService).setString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_ZONE, "Asia/Irkutsk");
    }

    @Test
    void claimScheduledArchiveRunPersistsNewRunKey() {
        OrderArchiveDryRunService service = serviceWithSettings();
        String runKey = "2026-05-11 04:15 Asia/Irkutsk";
        when(appSettingService.getString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_LAST_RUN_KEY, ""))
                .thenReturn("2026-05-10 04:15 Asia/Irkutsk");

        boolean claimed = service.claimScheduledArchiveRun(" " + runKey + " ");

        assertTrue(claimed);
        verify(appSettingService).setString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_LAST_RUN_KEY, runKey);
    }

    @Test
    void claimScheduledArchiveRunSkipsAlreadyClaimedRunKey() {
        OrderArchiveDryRunService service = serviceWithSettings();
        String runKey = "2026-05-11 04:15 Asia/Irkutsk";
        when(appSettingService.getString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_LAST_RUN_KEY, ""))
                .thenReturn(runKey);

        boolean claimed = service.claimScheduledArchiveRun(runKey);

        assertFalse(claimed);
        verify(appSettingService, never()).setString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_LAST_RUN_KEY, runKey);
    }

    private String captureArchiveReason() {
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).insertDryRunBatch(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                reasonCaptor.capture(),
                anyInt(),
                anyLong(),
                any(ArchiveCandidateCounts.class),
                any(String.class)
        );
        return reasonCaptor.getValue();
    }

    private OrderArchiveDryRunService serviceWithFixedClock() {
        ZoneId zone = ZoneId.of("Asia/Irkutsk");
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), zone);
        OrderArchiveDryRunService service = new OrderArchiveDryRunService(
                repository,
                null,
                paymentLinkArchiveService,
                clock
        );
        service.setDefaultRetentionDays(90);
        service.setDefaultBatchLimit(500);
        service.setMaxBatchLimit(1000);
        return service;
    }

    private OrderArchiveDryRunService serviceWithSettings() {
        ZoneId zone = ZoneId.of("Asia/Irkutsk");
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), zone);
        return new OrderArchiveDryRunService(repository, appSettingService, paymentLinkArchiveService, clock);
    }
}
