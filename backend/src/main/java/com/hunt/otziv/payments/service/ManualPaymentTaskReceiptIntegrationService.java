package com.hunt.otziv.payments.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.service.ManualPaymentTaskContractorReservationService;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReleaseCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReserveCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSettlementCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Source-bound bridge between payment routing, actual-recipient accounting and
 * the append-only task ledger. All methods participate in the caller's locked
 * transaction; no after-commit financial mutation is allowed here.
 */
@Service
@RequiredArgsConstructor
public class ManualPaymentTaskReceiptIntegrationService {

    private static final String SYSTEM_ACTOR = "system:payment-routing";
    private static final Comparator<ManualPaymentTaskSourceRef> SOURCE_ORDER = Comparator
            .comparing(ManualPaymentTaskSourceRef::sourceKind)
            .thenComparing(ManualPaymentTaskSourceRef::sourceId)
            .thenComparing(ManualPaymentTaskSourceRef::sourceGeneration);
    private static final Set<PaymentLinkStatus> LEGACY_PENDING_LINK_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED
    );
    private static final Set<CommonInvoiceStatus> LEGACY_PENDING_COMMON_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID,
            CommonInvoiceStatus.NEEDS_ATTENTION
    );

    private final ManualPaymentTaskLedgerService ledgerService;
    private final ManualPaymentTaskContractorReservationService contractorReservationService;
    private final PaymentLinkRepository paymentLinkRepository;
    private final CommonInvoiceRepository commonInvoiceRepository;

    public record LegacySourceLocks(
            List<ManualPaymentTaskSourceRef> sources,
            ContractorAllocationMode accountingMode
    ) {
        public LegacySourceLocks {
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            Objects.requireNonNull(accountingMode, "accountingMode");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ManualPaymentTaskRouteSnapshot> reserveForPaymentLink(
            PaymentLink link,
            Long managerId,
            Long paymentProfileId
    ) {
        if (link == null || link.getId() == null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (normalize(link.getManualTaskSourceGeneration()).isBlank()) {
            link.setManualTaskSourceGeneration(UUID.randomUUID().toString());
        }
        ContractorAllocationMode mode = contractorReservationService.lockAccountingMode();
        ManualPaymentTaskSourceRef source = paymentLinkSource(link);
        Optional<ManualPaymentTaskRouteSnapshot> result = requireResolved(ledgerService.reserveFirst(new ManualPaymentTaskReserveCommand(
                managerId,
                paymentProfileId,
                source,
                link.getAmountKopecks(),
                operation("RESERVE", source),
                SYSTEM_ACTOR
        )));
        result.ifPresent(snapshot -> {
            link.setManualTaskGeneration(snapshot.taskGeneration());
            // Persist the issued phase even for OWNER/EXTERNAL tasks, which
            // have no contractor allocation from which to reconstruct it.
            link.setManualActualAccountingMode(mode);
            link.setContractorAllocationId(contractorReservationService.reserve(link, snapshot, mode));
        });
        return result;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ManualPaymentTaskRouteSnapshot> reserveForCommonInvoice(
            CommonInvoice invoice,
            Long managerId,
            Long paymentProfileId,
            long amountKopecks
    ) {
        if (invoice == null || invoice.getId() == null || amountKopecks <= 0) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (normalize(invoice.getPaymentRouteManualTaskSourceGeneration()).isBlank()) {
            invoice.setPaymentRouteManualTaskSourceGeneration(UUID.randomUUID().toString());
        }
        ContractorAllocationMode mode = contractorReservationService.lockAccountingMode();
        ManualPaymentTaskSourceRef source = commonInvoiceSource(invoice);
        Optional<ManualPaymentTaskRouteSnapshot> result = requireResolved(ledgerService.reserveFirst(new ManualPaymentTaskReserveCommand(
                managerId,
                paymentProfileId,
                source,
                amountKopecks,
                operation("RESERVE", source),
                SYSTEM_ACTOR
        )));
        result.ifPresent(snapshot -> {
            invoice.setPaymentRouteManualTaskGeneration(snapshot.taskGeneration());
            invoice.setPaymentRouteManualTaskAccountingMode(mode);
            invoice.setContractorAllocationId(contractorReservationService.reserve(invoice, snapshot, mode));
        });
        return result;
    }

    /** Locks exact legacy sources before phase and task, matching payment finalization. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LegacySourceLocks lockLegacySourcesThenAccountingMode(Long taskId) {
        List<ManualPaymentTaskSourceRef> sources = ledgerService
                .pendingUnresolvedSources(taskId).stream()
                .sorted(SOURCE_ORDER)
                .toList();
        for (ManualPaymentTaskSourceRef source : sources) {
            lockLegacySource(taskId, source);
        }
        ContractorAllocationMode mode = contractorReservationService.lockAccountingMode();
        return new LegacySourceLocks(sources, mode);
    }

    private void lockLegacySource(Long taskId, ManualPaymentTaskSourceRef source) {
        if (source == null || source.sourceKind() == null || source.sourceId() == null
                || !Objects.equals(source.sourceGeneration(), "LEGACY-" + source.sourceId())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (source.sourceKind() == ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK) {
            PaymentLink link = paymentLinkRepository.findByIdForUpdate(source.sourceId())
                    .orElseThrow(ManualPaymentTaskRouteErrors::stale);
            if (link.getManualSource() != ManualPaymentSource.MANUAL_TASK
                    || link.getManualPaymentTask() == null
                    || !Objects.equals(link.getManualPaymentTask().getId(), taskId)
                    || !Objects.equals(link.getManualTaskSourceGeneration(),
                            source.sourceGeneration())) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            return;
        }
        if (source.sourceKind() == ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE) {
            CommonInvoice invoice = commonInvoiceRepository.findByIdForUpdate(source.sourceId())
                    .orElseThrow(ManualPaymentTaskRouteErrors::stale);
            if (invoice.getPaymentRouteManualSource() != ManualPaymentSource.MANUAL_TASK
                    || !Objects.equals(invoice.getPaymentRouteManualTaskId(), taskId)
                    || !Objects.equals(invoice.getPaymentRouteManualTaskSourceGeneration(),
                            source.sourceGeneration())) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            return;
        }
        throw ManualPaymentTaskRouteErrors.stale();
    }

    /**
     * Completes the V251 quarantine remediation while the caller holds exact
     * source rows, phase and task in that order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bindPendingLegacyReservations(
            ManualPaymentTask task,
            String actor,
            LegacySourceLocks locks
    ) {
        if (task == null || task.getId() == null || locks == null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        List<ManualPaymentTaskRouteSnapshot> sources =
                ledgerService.bindPendingLegacyReservations(task, actor);
        List<ManualPaymentTaskSourceRef> boundSources = sources.stream()
                .map(ManualPaymentTaskRouteSnapshot::source)
                .sorted(SOURCE_ORDER)
                .toList();
        if (!boundSources.equals(locks.sources())) {
            // A source was finalized/created between discovery and the task
            // lock. Roll back and let the administrator retry from fresh data.
            throw ManualPaymentTaskRouteErrors.stale();
        }
        for (ManualPaymentTaskRouteSnapshot source : sources) {
            if (source.source() == null
                    || !source.source().sourceGeneration().equals(
                            "LEGACY-" + source.source().sourceId())) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            if (source.source().sourceKind() == ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK) {
                bindLegacyPaymentLink(task, source, locks.accountingMode());
            } else if (source.source().sourceKind()
                    == ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE) {
                bindLegacyCommonInvoice(task, source, locks.accountingMode());
            } else {
                throw ManualPaymentTaskRouteErrors.stale();
            }
        }
    }

    private void bindLegacyPaymentLink(
            ManualPaymentTask task,
            ManualPaymentTaskRouteSnapshot snapshot,
            ContractorAllocationMode mode
    ) {
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(snapshot.source().sourceId())
                .orElseThrow(ManualPaymentTaskRouteErrors::stale);
        long sourceAmount = effectiveManualAmount(link);
        if (link.getManualSource() != ManualPaymentSource.MANUAL_TASK
                || link.getManualPaymentTask() == null
                || !Objects.equals(link.getManualPaymentTask().getId(), task.getId())
                || !Objects.equals(link.getManualTaskSourceGeneration(),
                        snapshot.source().sourceGeneration())
                || link.getManualTaskGeneration() == null
                || !LEGACY_PENDING_LINK_STATUSES.contains(link.getStatus())
                || link.getExpiresAt() == null
                || !link.getExpiresAt().isAfter(LocalDateTime.now())
                || sourceAmount != snapshot.reservedAmountKopecks()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        link.setManualTaskGeneration(snapshot.taskGeneration());
        link.setManualActualAccountingMode(mode);
        link.setContractorAllocationId(
                contractorReservationService.remediateLegacy(link, snapshot, mode));
        paymentLinkRepository.save(link);
    }

    private void bindLegacyCommonInvoice(
            ManualPaymentTask task,
            ManualPaymentTaskRouteSnapshot snapshot,
            ContractorAllocationMode mode
    ) {
        CommonInvoice invoice = commonInvoiceRepository.findByIdForUpdate(snapshot.source().sourceId())
                .orElseThrow(ManualPaymentTaskRouteErrors::stale);
        long routeAmount = invoice.getPaymentRouteAmountKopecks() == null
                ? 0 : invoice.getPaymentRouteAmountKopecks();
        long invoiceAmount = invoice.getAmountKopecks();
        long paidAmount = invoice.getPaidKopecks();
        long paidBaseline = routeAmount > invoiceAmount
                ? -1L : Math.max(0L, invoiceAmount - routeAmount);
        if (invoice.getPaymentRouteManualSource() != ManualPaymentSource.MANUAL_TASK
                || !Objects.equals(invoice.getPaymentRouteManualTaskId(), task.getId())
                || !Objects.equals(invoice.getPaymentRouteManualTaskSourceGeneration(),
                        snapshot.source().sourceGeneration())
                || invoice.getPaymentRouteManualTaskGeneration() == null
                || invoice.getPaymentRouteSelectedAt() == null
                || invoice.getStatus() == null
                || !LEGACY_PENDING_COMMON_STATUSES.contains(invoice.getStatus())
                || routeAmount <= 0
                || routeAmount > invoiceAmount
                || paidAmount < paidBaseline
                || paidAmount >= invoiceAmount
                || routeAmount != snapshot.reservedAmountKopecks()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (paidAmount > paidBaseline) {
            // Do not reinterpret pre-existing/other paid evidence as task
            // confirmation. Keep the full frozen exposure and make the
            // anomaly explicit until an administrator reconciles it.
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            task.setStatus(com.hunt.otziv.payments.model.ManualPaymentTaskStatus.NEEDS_ATTENTION);
            task.setNeedsReconciliation(true);
        }
        invoice.setPaymentRouteManualTaskGeneration(snapshot.taskGeneration());
        invoice.setPaymentRouteManualTaskAccountingMode(mode);
        invoice.setContractorAllocationId(
                contractorReservationService.remediateLegacy(invoice, snapshot, mode));
        commonInvoiceRepository.save(invoice);
    }

    private long effectiveManualAmount(PaymentLink link) {
        if (link.getConfirmedAmountKopecks() != null) {
            return link.getConfirmedAmountKopecks();
        }
        if (link.getReservedAmountKopecks() != null) {
            return link.getReservedAmountKopecks();
        }
        return link.getAmountKopecks();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ManualPaymentTaskRouteSnapshot> candidate(PaymentLink link) {
        if (link == null || link.getManualSource() != ManualPaymentSource.MANUAL_TASK) {
            return Optional.empty();
        }
        if (link.getManualPaymentTask() == null
                || link.getManualPaymentTask().getId() == null
                || link.getManualTaskGeneration() == null
                || normalize(link.getManualTaskSourceGeneration()).isBlank()) {
            throw ManualPaymentTaskRouteErrors.unresolved();
        }
        ManualPaymentTaskRouteSnapshot snapshot = requireResolved(
                ledgerService.candidateForSource(paymentLinkSource(link))
        ).orElseThrow(ManualPaymentTaskRouteErrors::stale);
        requireLegacyAwareBinding(
                snapshot,
                link.getManualPaymentTask().getId(),
                link.getManualTaskGeneration(),
                link.getAmountKopecks()
        );
        return Optional.of(sourceFacing(snapshot, link));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ManualPaymentTaskRouteSnapshot> candidate(CommonInvoice invoice) {
        if (invoice == null || invoice.getPaymentRouteManualSource() != ManualPaymentSource.MANUAL_TASK) {
            return Optional.empty();
        }
        if (invoice.getPaymentRouteManualTaskId() == null
                || invoice.getPaymentRouteManualTaskGeneration() == null
                || normalize(invoice.getPaymentRouteManualTaskSourceGeneration()).isBlank()) {
            throw ManualPaymentTaskRouteErrors.unresolved();
        }
        ManualPaymentTaskRouteSnapshot snapshot = requireResolved(
                ledgerService.candidateForSource(commonInvoiceSource(invoice))
        ).orElseThrow(ManualPaymentTaskRouteErrors::stale);
        requireLegacyAwareBinding(
                snapshot,
                invoice.getPaymentRouteManualTaskId(),
                invoice.getPaymentRouteManualTaskGeneration(),
                invoice.getPaymentRouteAmountKopecks() == null
                        ? snapshot.reservedAmountKopecks()
                        : invoice.getPaymentRouteAmountKopecks()
        );
        return Optional.of(sourceFacing(snapshot, invoice));
    }

    /** Phase is locked by the caller before this canonical task lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTaskForFinalAttribution(PaymentLink link) {
        if (link == null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        Long originalTaskId = link.getManualActualOriginalCashDestinationKind()
                == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                ? link.getManualActualOriginalTaskId() : null;
        Long actualTaskId = link.getManualActualCashDestinationKind()
                == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                ? link.getManualActualTaskId() : null;
        if (originalTaskId == null && actualTaskId == null) {
            return;
        }
        if (originalTaskId != null && actualTaskId != null
                && !Objects.equals(originalTaskId, actualTaskId)) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        Long taskId = actualTaskId == null ? originalTaskId : actualTaskId;
        if (link.getManualSource() != ManualPaymentSource.MANUAL_TASK
                || link.getManualPaymentTask() == null
                || !Objects.equals(link.getManualPaymentTask().getId(), taskId)
                || link.getManualTaskGeneration() == null
                || normalize(link.getManualTaskSourceGeneration()).isBlank()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        ManualPaymentTaskRouteSnapshot locked = ledgerService.lockSourceTask(
                taskId, paymentLinkSource(link));
        boolean legacy = locked.source().sourceGeneration().equals(
                "LEGACY-" + locked.source().sourceId());
        if (!Objects.equals(locked.taskId(), taskId)
                || (!legacy && locked.taskGeneration() != link.getManualTaskGeneration())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(
            PaymentLink link,
            String selectedRecipientKey,
            long taskAttributedAmountKopecks,
            String operationKey,
            String actor,
            String reason
    ) {
        if (link == null || link.getManualSource() != ManualPaymentSource.MANUAL_TASK) {
            return;
        }
        settle(routeBinding(link), selectedRecipientKey, taskAttributedAmountKopecks, operationKey, actor, reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(
            CommonInvoice invoice,
            String selectedRecipientKey,
            long taskAttributedAmountKopecks,
            String operationKey,
            String actor,
            String reason
    ) {
        if (invoice == null || invoice.getPaymentRouteManualSource() != ManualPaymentSource.MANUAL_TASK) {
            return;
        }
        settle(routeBinding(invoice), selectedRecipientKey, taskAttributedAmountKopecks, operationKey, actor, reason);
    }

    private void settle(
            ManualPaymentTaskRouteSnapshot snapshot,
            String selectedRecipientKey,
            long taskAttributedAmountKopecks,
            String operationKey,
            String actor,
            String reason
    ) {
        if (taskAttributedAmountKopecks < 0
                || taskAttributedAmountKopecks > snapshot.reservedAmountKopecks()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (taskAttributedAmountKopecks > 0
                && !Objects.equals(snapshot.candidateKey(), normalize(selectedRecipientKey))) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        ledgerService.settle(new ManualPaymentTaskSettlementCommand(
                snapshot.taskId(),
                snapshot.taskGeneration(),
                snapshot.source(),
                taskAttributedAmountKopecks > 0 ? snapshot.candidateKey() : normalize(selectedRecipientKey),
                snapshot.reservedAmountKopecks(),
                taskAttributedAmountKopecks,
                normalize(operationKey),
                safeActor(actor),
                normalize(reason)
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(PaymentLink link, String reason) {
        if (link == null || link.getManualSource() != ManualPaymentSource.MANUAL_TASK) return;
        // The caller owns the source row. Phase remains the global financial
        // mutex, but the persisted allocation mode decides what is released.
        contractorReservationService.lockAccountingMode();
        ManualPaymentTaskRouteSnapshot snapshot = routeBinding(link);
        // Complete canonical lock order before the first financial write:
        // source (caller) -> phase -> task -> profile -> allocation.
        ledgerService.lockSourceTask(snapshot.taskId(), snapshot.source());
        contractorReservationService.preflightRelease(
                link.getContractorAllocationId(), ContractorAllocationSourceType.PAYMENT_LINK,
                link.getId(), snapshot.source().sourceGeneration(), snapshot.reservedAmountKopecks());
        release(snapshot, reason);
        contractorReservationService.releaseLocked(
                link.getContractorAllocationId(), ContractorAllocationSourceType.PAYMENT_LINK,
                link.getId(), snapshot.source().sourceGeneration(), releaseStatus(reason), reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(CommonInvoice invoice, String reason) {
        if (invoice == null || invoice.getPaymentRouteManualSource() != ManualPaymentSource.MANUAL_TASK) return;
        contractorReservationService.lockAccountingMode();
        ManualPaymentTaskRouteSnapshot snapshot = routeBinding(invoice);
        ledgerService.lockSourceTask(snapshot.taskId(), snapshot.source());
        contractorReservationService.preflightRelease(
                invoice.getContractorAllocationId(), ContractorAllocationSourceType.COMMON_INVOICE,
                invoice.getId(), snapshot.source().sourceGeneration(), snapshot.reservedAmountKopecks());
        release(snapshot, reason);
        contractorReservationService.releaseLocked(
                invoice.getContractorAllocationId(), ContractorAllocationSourceType.COMMON_INVOICE,
                invoice.getId(), snapshot.source().sourceGeneration(), releaseStatus(reason), reason);
    }

    private void release(ManualPaymentTaskRouteSnapshot snapshot, String reason) {
        ledgerService.release(new ManualPaymentTaskReleaseCommand(
                snapshot.taskId(),
                snapshot.source(),
                snapshot.reservedAmountKopecks(),
                operation("RELEASE", snapshot.source()),
                SYSTEM_ACTOR,
                normalize(reason)
        ));
    }

    private ManualPaymentTaskRouteSnapshot routeBinding(PaymentLink link) {
        if (link == null || link.getId() == null || link.getManualPaymentTask() == null
                || link.getManualPaymentTask().getId() == null || link.getManualTaskGeneration() == null
                || normalize(link.getManualTaskSourceGeneration()).isBlank()) {
            throw ManualPaymentTaskRouteErrors.unresolved();
        }
        ManualPaymentTaskRouteSnapshot snapshot = ledgerService.candidateForSource(paymentLinkSource(link))
                .map(value -> sourceFacing(value, link)).orElseGet(() -> syntheticBinding(
                        link.getManualPaymentTask().getId(), link.getManualTaskGeneration(),
                        paymentLinkSource(link), link.getAmountKopecks()));
        requireLegacyAwareBinding(snapshot, link.getManualPaymentTask().getId(),
                link.getManualTaskGeneration(), link.getAmountKopecks());
        return snapshot;
    }

    private ManualPaymentTaskRouteSnapshot routeBinding(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null || invoice.getPaymentRouteManualTaskId() == null
                || invoice.getPaymentRouteManualTaskGeneration() == null
                || normalize(invoice.getPaymentRouteManualTaskSourceGeneration()).isBlank()) {
            throw ManualPaymentTaskRouteErrors.unresolved();
        }
        long amount = invoice.getPaymentRouteAmountKopecks() == null
                ? 0L : invoice.getPaymentRouteAmountKopecks();
        ManualPaymentTaskRouteSnapshot snapshot = ledgerService.candidateForSource(commonInvoiceSource(invoice))
                .map(value -> sourceFacing(value, invoice)).orElseGet(() -> syntheticBinding(
                        invoice.getPaymentRouteManualTaskId(), invoice.getPaymentRouteManualTaskGeneration(),
                        commonInvoiceSource(invoice), amount));
        requireLegacyAwareBinding(snapshot, invoice.getPaymentRouteManualTaskId(),
                invoice.getPaymentRouteManualTaskGeneration(), amount);
        return snapshot;
    }

    private ManualPaymentTaskRouteSnapshot syntheticBinding(
            Long taskId, long generation, ManualPaymentTaskSourceRef source, long amount
    ) {
        return new ManualPaymentTaskRouteSnapshot(taskId, generation, source,
                ManualPaymentTaskLedgerService.candidateKey(taskId, generation),
                ManualPaymentTaskAccountingTargetKind.UNRESOLVED, null, "", null,
                "", "", "", "", amount, null, "");
    }

    private void requireLegacyAwareBinding(
            ManualPaymentTaskRouteSnapshot snapshot, Long taskId, long sourceGeneration, long amount
    ) {
        boolean legacyBoundGeneration = snapshot.source().sourceGeneration()
                .equals("LEGACY-" + snapshot.source().sourceId());
        if (!Objects.equals(snapshot.taskId(), taskId)
                || (!legacyBoundGeneration && snapshot.taskGeneration() != sourceGeneration)
                || snapshot.reservedAmountKopecks() != amount) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    private ManualPaymentTaskRouteSnapshot sourceFacing(
            ManualPaymentTaskRouteSnapshot snapshot, PaymentLink link
    ) {
        return new ManualPaymentTaskRouteSnapshot(
                snapshot.taskId(), snapshot.taskGeneration(), snapshot.source(), snapshot.candidateKey(),
                snapshot.accountingTargetKind(), snapshot.accountingTargetProfileId(),
                snapshot.accountingTargetLabel(), snapshot.manualPaymentType() == null
                        ? link.getManualPaymentType() : snapshot.manualPaymentType(),
                fallback(snapshot.manualPhone(), link.getManualPhone()),
                fallback(snapshot.bankRecipientName(), link.getManualRecipientName()),
                fallback(snapshot.manualPaymentUrl(), link.getManualPaymentUrl()),
                fallback(snapshot.manualPaymentButtonLabel(), link.getManualPaymentButtonLabel()),
                snapshot.reservedAmountKopecks(), snapshot.targetOverrunAcknowledgedAt(),
                snapshot.targetOverrunAcknowledgedBy());
    }

    private ManualPaymentTaskRouteSnapshot sourceFacing(
            ManualPaymentTaskRouteSnapshot snapshot, CommonInvoice invoice
    ) {
        return new ManualPaymentTaskRouteSnapshot(
                snapshot.taskId(), snapshot.taskGeneration(), snapshot.source(), snapshot.candidateKey(),
                snapshot.accountingTargetKind(), snapshot.accountingTargetProfileId(),
                snapshot.accountingTargetLabel(), snapshot.manualPaymentType() == null
                        ? invoice.getPaymentRouteManualType() : snapshot.manualPaymentType(),
                fallback(snapshot.manualPhone(), invoice.getPaymentRouteManualPhone()),
                fallback(snapshot.bankRecipientName(), invoice.getPaymentRouteManualRecipient()),
                fallback(snapshot.manualPaymentUrl(), invoice.getPaymentRouteManualUrl()),
                fallback(snapshot.manualPaymentButtonLabel(), invoice.getPaymentRouteManualButton()),
                snapshot.reservedAmountKopecks(), snapshot.targetOverrunAcknowledgedAt(),
                snapshot.targetOverrunAcknowledgedBy());
    }

    private String fallback(String ledgerValue, String sourceValue) {
        return normalize(ledgerValue).isBlank() ? normalize(sourceValue) : normalize(ledgerValue);
    }

    private ContractorAllocationStatus releaseStatus(String reason) {
        String value = normalize(reason).toLowerCase(java.util.Locale.ROOT);
        if (value.contains("истек") || value.contains("истёк")) return ContractorAllocationStatus.EXPIRED;
        if (value.contains("не поступ") || value.contains("не оплач")) return ContractorAllocationStatus.RELEASED_UNPAID;
        return ContractorAllocationStatus.CANCELED;
    }

    public Destination destination(ManualPaymentTaskRouteSnapshot snapshot) {
        if (snapshot == null || snapshot.accountingTargetKind() == null
                || snapshot.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
            throw ManualPaymentTaskRouteErrors.unresolved();
        }
        ContractorRecipientType recipientType = switch (snapshot.accountingTargetKind()) {
            case OWNER -> ContractorRecipientType.OWNER;
            case SPECIALIST -> ContractorRecipientType.SPECIALIST;
            case MANAGER -> ContractorRecipientType.MANAGER;
            case EXTERNAL_TASK -> null;
            case UNRESOLVED -> throw ManualPaymentTaskRouteErrors.unresolved();
        };
        Long profileId = recipientType == ContractorRecipientType.SPECIALIST
                || recipientType == ContractorRecipientType.MANAGER
                ? snapshot.accountingTargetProfileId() : null;
        if ((recipientType == ContractorRecipientType.SPECIALIST
                || recipientType == ContractorRecipientType.MANAGER)
                && (profileId == null || profileId <= 0)) {
            throw ManualPaymentTaskRouteErrors.unresolved();
        }
        return new Destination(
                snapshot.candidateKey(),
                ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                recipientType,
                profileId,
                snapshot.taskId(),
                snapshot.taskGeneration(),
                snapshot.accountingTargetKind(),
                normalize(snapshot.bankRecipientName()),
                normalize(snapshot.accountingTargetLabel())
        );
    }

    private Optional<ManualPaymentTaskRouteSnapshot> requireResolved(
            Optional<ManualPaymentTaskRouteSnapshot> candidate
    ) {
        candidate.ifPresent(snapshot -> {
            if (snapshot.accountingTargetKind() == null
                    || snapshot.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
                throw ManualPaymentTaskRouteErrors.unresolved();
            }
        });
        return candidate;
    }

    private void requireBinding(
            ManualPaymentTaskRouteSnapshot snapshot,
            Long taskId,
            long taskGeneration,
            long amountKopecks
    ) {
        if (!Objects.equals(snapshot.taskId(), taskId)
                || snapshot.taskGeneration() != taskGeneration
                || snapshot.reservedAmountKopecks() != amountKopecks) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    private ManualPaymentTaskSourceRef paymentLinkSource(PaymentLink link) {
        return new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                link.getId(),
                normalize(link.getManualTaskSourceGeneration())
        );
    }

    private ManualPaymentTaskSourceRef commonInvoiceSource(CommonInvoice invoice) {
        return new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                invoice.getId(),
                normalize(invoice.getPaymentRouteManualTaskSourceGeneration())
        );
    }

    private String operation(String action, ManualPaymentTaskSourceRef source) {
        return "TASK:" + action + ":" + source.sourceKind().name() + ":"
                + source.sourceId() + ":" + source.sourceGeneration();
    }

    private String safeActor(String value) {
        String actor = normalize(value);
        return actor.isBlank() ? SYSTEM_ACTOR : actor;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record Destination(
            String candidateKey,
            ContractorCashDestinationKind cashDestinationKind,
            ContractorRecipientType recipientType,
            Long recipientProfileId,
            Long taskId,
            long taskGeneration,
            ManualPaymentTaskAccountingTargetKind taskTargetKind,
            String bankRecipientName,
            String accountingTargetLabel
    ) {
    }
}
