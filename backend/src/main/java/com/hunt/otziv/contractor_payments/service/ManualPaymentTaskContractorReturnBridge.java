package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReturnCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.ManualPaymentTaskArchivedSourceRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskLedgerService;
import com.hunt.otziv.payments.service.ManualPaymentTaskRouteErrors;
import com.hunt.otziv.payments.service.PaymentReturnRecoveryState;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Source-bound bridge from contractor returns to the manual-task ledger.
 *
 * <p>The bridge only follows an immutable final attribution whose actual cash
 * destination is {@code MANUAL_PAYMENT_TASK} and whose contractor allocation
 * can be proven exactly. OWNER and EXTERNAL_TASK destinations intentionally
 * have no contractor allocation. They are bridged only from an authoritative
 * PaymentLink refund; an unknown partial amount is never synthesized.
 * The caller must obtain the binding before locking a contractor profile or
 * allocation; this preserves the source -&gt; task -&gt; profile -&gt; allocation
 * lock order used by receipt settlement.</p>
 */
@Service
@RequiredArgsConstructor
public class ManualPaymentTaskContractorReturnBridge {

    private static final String ACTOR = "system:contractor-return";
    private static final String REASON = "Возврат от фактического получателя платёжного задания";

    private final ContractorActualPaymentAttributionRepository attributionRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final ManualPaymentTaskLedgerService taskLedgerService;
    private final ManualPaymentTaskArchivedSourceRepository archivedSourceRepository;

    /** The payment link is already locked by the reconciliation caller. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Binding lockPaymentLinkBinding(
            ContractorPaymentAllocation allocation,
            PaymentLink lockedLink
    ) {
        if (allocation == null || lockedLink == null
                || allocation.getSourceType() != ContractorAllocationSourceType.PAYMENT_LINK
                || !Objects.equals(allocation.getSourceId(), lockedLink.getId())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        return bindSourceAllocation(
                allocation,
                ContractorActualPaymentSourceKind.PAYMENT_LINK,
                lockedLink.getId(),
                () -> paymentLinkSource(lockedLink),
                lockedLink.getManualPaymentTask() == null
                        ? null : lockedLink.getManualPaymentTask().getId(),
                lockedLink.getManualTaskGeneration(),
                missingGenerationBinding(lockedLink)
        );
    }

    /** The common invoice is already locked by the reconciliation caller. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Binding lockCommonInvoiceBinding(
            ContractorPaymentAllocation allocation,
            CommonInvoice lockedInvoice
    ) {
        if (allocation == null || lockedInvoice == null
                || allocation.getSourceType() != ContractorAllocationSourceType.COMMON_INVOICE
                || !Objects.equals(allocation.getSourceId(), lockedInvoice.getId())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        return bindSourceAllocation(
                allocation,
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                lockedInvoice.getId(),
                () -> commonInvoiceSource(lockedInvoice),
                lockedInvoice.getPaymentRouteManualTaskId(),
                lockedInvoice.getPaymentRouteManualTaskGeneration(),
                missingGenerationBinding(lockedInvoice)
        );
    }

    /**
     * Resolves an allocation created for an immutable actual-payment row. The
     * underlying payment source is locked before the task and before the caller
     * locks the contractor profile/allocation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Binding lockActualPaymentBinding(ContractorPaymentAllocation allocation) {
        if (allocation == null || allocation.getId() == null
                || allocation.getSourceType() != ContractorAllocationSourceType.ACTUAL_PAYMENT
                || allocation.getSourceId() == null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        ContractorActualPaymentAttribution row = attributionRepository.findById(allocation.getSourceId())
                .orElseThrow(ManualPaymentTaskRouteErrors::stale);
        requireActualAllocation(row, allocation);
        if (row.getSourceKind() == ContractorActualPaymentSourceKind.PAYMENT_LINK) {
            PaymentLink link = paymentLinkRepository.findByIdForUpdate(row.getSourceId())
                    .orElse(null);
            if (link == null) {
                return lockArchivedActualPaymentBinding(row, allocation);
            }
            requireSourceRow(row, ContractorActualPaymentSourceKind.PAYMENT_LINK, link.getId());
            if (!taskDestination(row)) {
                return Binding.none(allocation);
            }
            if (missingGenerationBinding(link)) {
                validateDurableTaskIdentity(
                        row,
                        link.getManualPaymentTask() == null
                                ? null : link.getManualPaymentTask().getId()
                );
                return bindArchived(row, allocation);
            }
            ManualPaymentTaskSourceRef source = paymentLinkSource(link);
            validateTaskSource(
                    row, source,
                    link.getManualPaymentTask() == null ? null : link.getManualPaymentTask().getId(),
                    link.getManualTaskGeneration()
            );
            return bind(row, allocation, source);
        }
        if (row.getSourceKind() == ContractorActualPaymentSourceKind.COMMON_INVOICE) {
            CommonInvoice invoice = commonInvoiceRepository.findByIdForUpdate(row.getSourceId())
                    .orElse(null);
            if (invoice == null) {
                return lockArchivedActualPaymentBinding(row, allocation);
            }
            requireSourceRow(row, ContractorActualPaymentSourceKind.COMMON_INVOICE, invoice.getId());
            if (!taskDestination(row)) {
                return Binding.none(allocation);
            }
            if (missingGenerationBinding(invoice)) {
                validateDurableTaskIdentity(row, invoice.getPaymentRouteManualTaskId());
                return bindArchived(row, allocation);
            }
            ManualPaymentTaskSourceRef source = commonInvoiceSource(invoice);
            validateTaskSource(
                    row, source,
                    invoice.getPaymentRouteManualTaskId(),
                    invoice.getPaymentRouteManualTaskGeneration()
            );
            return bind(row, allocation, source);
        }
        throw ManualPaymentTaskRouteErrors.stale();
    }

    /**
     * Locks a deleted live source through its immutable archive row, then
     * reconstructs the exact task generation from attribution + task ledger.
     * This is the only fallback accepted by the manual return API.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Binding lockArchivedSourceBinding(ContractorPaymentAllocation allocation) {
        if (allocation == null || allocation.getId() == null
                || allocation.getSourceId() == null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        ContractorActualPaymentSourceKind sourceKind;
        if (allocation.getSourceType() == ContractorAllocationSourceType.PAYMENT_LINK) {
            sourceKind = ContractorActualPaymentSourceKind.PAYMENT_LINK;
        } else if (allocation.getSourceType() == ContractorAllocationSourceType.COMMON_INVOICE) {
            sourceKind = ContractorActualPaymentSourceKind.COMMON_INVOICE;
        } else {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        lockArchivedSource(sourceKind, allocation.getSourceId());
        List<ContractorActualPaymentAttribution> rows = attributionRepository
                .findAllBySourceForUpdate(sourceKind, allocation.getSourceId());
        if (rows.isEmpty()) {
            return Binding.none(allocation);
        }
        boolean allocationWasReused = rows.stream().allMatch(row ->
                Objects.equals(row.getOriginalAllocationId(), allocation.getId())
                        && sameContractor(row, allocation));
        if (!allocationWasReused) {
            return Binding.none(allocation);
        }
        List<ContractorActualPaymentAttribution> taskRows = rows.stream()
                .filter(this::taskDestination)
                .toList();
        if (taskRows.isEmpty()) {
            return Binding.none(allocation);
        }
        if (rows.size() != 1 || taskRows.size() != 1) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        ContractorActualPaymentAttribution row = taskRows.getFirst();
        requireSourceRow(row, sourceKind, allocation.getSourceId());
        requireSourceAllocation(row, allocation);
        return bindArchived(row, allocation);
    }

    private Binding lockArchivedActualPaymentBinding(
            ContractorActualPaymentAttribution snapshot,
            ContractorPaymentAllocation allocation
    ) {
        lockArchivedSource(snapshot.getSourceKind(), snapshot.getSourceId());
        ContractorActualPaymentAttribution row = attributionRepository
                .findByIdForUpdate(snapshot.getId())
                .orElseThrow(ManualPaymentTaskRouteErrors::stale);
        requireSourceRow(row, snapshot.getSourceKind(), snapshot.getSourceId());
        requireActualAllocation(row, allocation);
        if (!taskDestination(row)) {
            return Binding.none(allocation);
        }
        return bindArchived(row, allocation);
    }

    private Binding bindArchived(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation
    ) {
        ManualPaymentTaskLedgerSourceKind sourceKind = row.getSourceKind()
                == ContractorActualPaymentSourceKind.PAYMENT_LINK
                ? ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK
                : ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE;
        ManualPaymentTaskLedgerService.LockedArchivedReturnSource locked =
                taskLedgerService.lockArchivedReturnSource(
                        row.getActualManualPaymentTaskId(),
                        row.getActualManualPaymentTaskGeneration(),
                        sourceKind,
                        row.getSourceId()
                );
        return new Binding(
                row.getId(),
                row.getActualManualPaymentTaskId(),
                locked.source(),
                allocation.getId(),
                allocation.getMode(),
                allocation.getSourceType(),
                allocation.getSourceId(),
                row.getAmountKopecks(),
                locked.returnedKopecks()
        );
    }

    private void lockArchivedSource(
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId
    ) {
        boolean locked = sourceKind == ContractorActualPaymentSourceKind.PAYMENT_LINK
                ? archivedSourceRepository.lockPaymentLink(sourceId)
                : sourceKind == ContractorActualPaymentSourceKind.COMMON_INVOICE
                        && archivedSourceRepository.lockCommonInvoice(sourceId);
        if (!locked) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    /**
     * Bridges an authoritative PaymentLink reversal even when the task target
     * is OWNER/EXTERNAL_TASK and therefore has no contractor allocation. A
     * provider's partial status carries no returned amount, so it only reopens
     * the task for reconciliation and deliberately writes no financial delta.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAuthoritativePaymentLinkReturn(PaymentLink lockedLink) {
        if (lockedLink == null || !returnedPaymentLinkStatus(lockedLink)
                || lockedLink.getManualSource() != ManualPaymentSource.MANUAL_TASK) {
            return;
        }
        List<ContractorActualPaymentAttribution> rows = attributionRepository
                .findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                        ContractorActualPaymentSourceKind.PAYMENT_LINK,
                        lockedLink.getId()
                );
        if (rows.isEmpty()) {
            recordLegacyAuthoritativePaymentLinkReturn(lockedLink);
            return;
        }
        if (rows.size() != 1) {
            long total = 0L;
            for (ContractorActualPaymentAttribution row : rows) {
                requireSourceRow(row, ContractorActualPaymentSourceKind.PAYMENT_LINK, lockedLink.getId());
                if (row.getAmountKopecks() <= 0L
                        || anyTaskDestination(row)
                        || !ordinaryRedirectDestination(row)) {
                    // A provider partial status has no recipient-level amount.
                    // Mixed TASK batches therefore still require reconciliation.
                    throw ManualPaymentTaskRouteErrors.stale();
                }
                total = Math.addExact(total, row.getAmountKopecks());
            }
            if (total != lockedLink.getAmountKopecks()) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            // Every exact row is an ordinary redirected destination: the task
            // ledger was never credited and has nothing to return.
            return;
        }
        ContractorActualPaymentAttribution row = rows.getFirst();
        requireSourceRow(row, ContractorActualPaymentSourceKind.PAYMENT_LINK, lockedLink.getId());
        if (row.getAmountKopecks() <= 0L
                || row.getAmountKopecks() != lockedLink.getAmountKopecks()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (!anyTaskDestination(row)) {
            // The task receipt was atomically settled as REDIRECTED. Its task
            // confirmed delta is zero, so the provider return belongs only to
            // the explicit OWNER/profile destination and must not debit the task.
            if (!ordinaryRedirectDestination(row)) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            return;
        }
        ManualPaymentTaskSourceRef source;
        long ledgerReturned;
        if (missingGenerationBinding(lockedLink)) {
            validateDurableTaskIdentity(
                    row,
                    lockedLink.getManualPaymentTask() == null
                            ? null : lockedLink.getManualPaymentTask().getId()
            );
            ManualPaymentTaskLedgerService.LockedArchivedReturnSource durable =
                    taskLedgerService.lockArchivedReturnSource(
                            row.getActualManualPaymentTaskId(),
                            row.getActualManualPaymentTaskGeneration(),
                            ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                            lockedLink.getId()
                    );
            source = durable.source();
            ledgerReturned = durable.returnedKopecks();
        } else {
            source = paymentLinkSource(lockedLink);
            validateTaskSource(
                    row,
                    source,
                    lockedLink.getManualPaymentTask().getId(),
                    lockedLink.getManualTaskGeneration()
            );
            ledgerReturned = 0L;
        }
        if (partialPaymentLinkStatus(lockedLink.getStatus())) {
            taskLedgerService.markReturnNeedsAttention(row.getActualManualPaymentTaskId(), source);
            return;
        }
        if (!missingGenerationBinding(lockedLink)) {
            ledgerReturned = taskLedgerService.lockReturnSource(
                    row.getActualManualPaymentTaskId(), source);
        }
        recordTaskReturn(
                row.getId(),
                row.getActualManualPaymentTaskId(),
                source,
                row.getAmountKopecks(),
                ledgerReturned
        );
    }

    /**
     * Pre-V251 confirmations have no typed recipient row and never credited a
     * contractor allocation. Their per-source unverified ledger baseline is
     * therefore the only safe authority. A provider partial status has no
     * amount and only reopens the task; a full terminal status debits exactly
     * that baseline and is idempotent by cumulative total.
     */
    private void recordLegacyAuthoritativePaymentLinkReturn(PaymentLink lockedLink) {
        Long taskId = lockedLink.getManualPaymentTask() == null
                ? null : lockedLink.getManualPaymentTask().getId();
        if (taskId == null || lockedLink.getId() == null || lockedLink.getAmountKopecks() <= 0L) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        ManualPaymentTaskLedgerService.LockedLegacyReturnSource legacy =
                taskLedgerService.lockLegacyConfirmedReturnSource(
                        taskId,
                        ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                        lockedLink.getId()
                );
        if (!Objects.equals(legacy.taskId(), taskId)
                || legacy.confirmedKopecks() != lockedLink.getAmountKopecks()
                || (lockedLink.getManualTaskGeneration() != null
                && lockedLink.getManualTaskGeneration() != legacy.taskGeneration())
                || (!normalize(lockedLink.getManualTaskSourceGeneration()).isBlank()
                && !normalize(lockedLink.getManualTaskSourceGeneration())
                        .equals(legacy.source().sourceGeneration()))) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (partialPaymentLinkStatus(lockedLink.getStatus())) {
            taskLedgerService.markReturnNeedsAttention(taskId, legacy.source());
            return;
        }
        recordLegacyTaskReturn(
                taskId,
                legacy.source(),
                legacy.confirmedKopecks(),
                legacy.returnedKopecks()
        );
    }

    /**
     * Synchronizes the contractor allocation's monotonic returned total to the
     * task ledger. The binding contains the ledger total read while holding the
     * task lock, so retries are no-ops and a previously missed bridge write is
     * repaired by the next source reconciliation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReturn(Binding binding, ContractorPaymentAllocation allocation) {
        if (binding == null || !binding.taskBound()) {
            return;
        }
        requireBoundAllocation(binding, allocation);
        long desiredTotal = allocation.getReturnedKopecks();
        if (desiredTotal < binding.ledgerReturnedKopecks()
                || desiredTotal > binding.attributedAmountKopecks()
                || desiredTotal > allocation.getConfirmedKopecks()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        recordTaskReturn(
                binding.attributionId(),
                binding.taskId(),
                binding.source(),
                desiredTotal,
                binding.ledgerReturnedKopecks()
        );
    }

    private Binding bindSourceAllocation(
            ContractorPaymentAllocation allocation,
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId,
            Supplier<ManualPaymentTaskSourceRef> sourceSupplier,
            Long sourceTaskId,
            Long sourceTaskGeneration,
            boolean durableLedgerFallback
    ) {
        List<ContractorActualPaymentAttribution> rows = attributionRepository
                .findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(sourceKind, sourceId);
        if (rows.isEmpty()) {
            return Binding.none(allocation);
        }
        boolean allocationWasReused = rows.stream().allMatch(row ->
                Objects.equals(row.getOriginalAllocationId(), allocation.getId())
                        && sameContractor(row, allocation));
        if (!allocationWasReused) {
            // A mixed-recipient batch releases the original source allocation
            // and creates exact ACTUAL_PAYMENT allocations. A later source
            // return must not be guessed against that released row.
            return Binding.none(allocation);
        }
        List<ContractorActualPaymentAttribution> taskRows = rows.stream()
                .filter(this::taskDestination)
                .toList();
        if (taskRows.isEmpty()) {
            return Binding.none(allocation);
        }
        // The return API contains only allocationId + cumulative total. TASK
        // and plain PROFILE rows can legally credit the same allocation in a
        // common split, so more than one row is intrinsically ambiguous.
        if (rows.size() != 1 || taskRows.size() != 1) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        ContractorActualPaymentAttribution row = taskRows.getFirst();
        requireSourceRow(row, sourceKind, sourceId);
        requireSourceAllocation(row, allocation);
        if (durableLedgerFallback) {
            validateDurableTaskIdentity(row, sourceTaskId);
            return bindArchived(row, allocation);
        }
        ManualPaymentTaskSourceRef source = sourceSupplier.get();
        validateTaskSource(row, source, sourceTaskId, sourceTaskGeneration);
        return bind(row, allocation, source);
    }

    private Binding bind(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation,
            ManualPaymentTaskSourceRef source
    ) {
        if (!taskDestination(row)) {
            return Binding.none(allocation);
        }
        long ledgerReturned = taskLedgerService.lockReturnSource(
                row.getActualManualPaymentTaskId(), source
        );
        return new Binding(
                row.getId(),
                row.getActualManualPaymentTaskId(),
                source,
                allocation.getId(),
                allocation.getMode(),
                allocation.getSourceType(),
                allocation.getSourceId(),
                row.getAmountKopecks(),
                ledgerReturned
        );
    }

    private ManualPaymentTaskSourceRef paymentLinkSource(PaymentLink link) {
        if (link == null || link.getId() == null || link.getManualSource() != ManualPaymentSource.MANUAL_TASK
                || link.getManualPaymentTask() == null || link.getManualPaymentTask().getId() == null
                || link.getManualTaskGeneration() == null
                || normalize(link.getManualTaskSourceGeneration()).isBlank()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        return new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                link.getId(),
                normalize(link.getManualTaskSourceGeneration())
        );
    }

    private ManualPaymentTaskSourceRef commonInvoiceSource(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null
                || invoice.getPaymentRouteManualSource() != ManualPaymentSource.MANUAL_TASK
                || invoice.getPaymentRouteManualTaskId() == null
                || invoice.getPaymentRouteManualTaskGeneration() == null
                || normalize(invoice.getPaymentRouteManualTaskSourceGeneration()).isBlank()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        return new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                invoice.getId(),
                normalize(invoice.getPaymentRouteManualTaskSourceGeneration())
        );
    }

    /**
     * V252 added source/task generations only to live tables. Archive restore
     * copies the intersection of columns, so a restored source retains its
     * typed task id but legitimately has both generation fields empty. The
     * immutable final attribution plus append-only ledger is authoritative in
     * exactly that shape; a partially present live binding still fails closed.
     */
    private boolean missingGenerationBinding(PaymentLink link) {
        return link != null
                && link.getManualSource() == ManualPaymentSource.MANUAL_TASK
                && link.getManualPaymentTask() != null
                && link.getManualPaymentTask().getId() != null
                && link.getManualTaskGeneration() == null
                && normalize(link.getManualTaskSourceGeneration()).isBlank();
    }

    private boolean missingGenerationBinding(CommonInvoice invoice) {
        return invoice != null
                && invoice.getPaymentRouteManualSource() == ManualPaymentSource.MANUAL_TASK
                && invoice.getPaymentRouteManualTaskId() != null
                && invoice.getPaymentRouteManualTaskGeneration() == null
                && normalize(invoice.getPaymentRouteManualTaskSourceGeneration()).isBlank();
    }

    private void validateDurableTaskIdentity(
            ContractorActualPaymentAttribution row,
            Long sourceTaskId
    ) {
        if (!anyTaskDestination(row)
                || sourceTaskId == null
                || !Objects.equals(row.getActualManualPaymentTaskId(), sourceTaskId)) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    private void validateTaskSource(
            ContractorActualPaymentAttribution row,
            ManualPaymentTaskSourceRef source,
            Long sourceTaskId,
            Long sourceTaskGeneration
    ) {
        requireSourceRow(
                row,
                source.sourceKind() == ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK
                        ? ContractorActualPaymentSourceKind.PAYMENT_LINK
                        : ContractorActualPaymentSourceKind.COMMON_INVOICE,
                source.sourceId()
        );
        if (!anyTaskDestination(row)
                || row.getActualManualPaymentTaskId() == null
                || row.getActualManualPaymentTaskGeneration() == null
                || !Objects.equals(row.getActualManualPaymentTaskId(), sourceTaskId)
                || !Objects.equals(row.getActualManualPaymentTaskGeneration(), sourceTaskGeneration)) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    private void requireSourceRow(
            ContractorActualPaymentAttribution row,
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId
    ) {
        if (row == null || row.getSourceKind() != sourceKind
                || !Objects.equals(row.getSourceId(), sourceId)) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    private void requireSourceAllocation(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation
    ) {
        ContractorAllocationSourceType expected = row.getSourceKind()
                == ContractorActualPaymentSourceKind.PAYMENT_LINK
                ? ContractorAllocationSourceType.PAYMENT_LINK
                : ContractorAllocationSourceType.COMMON_INVOICE;
        if (allocation.getSourceType() != expected
                || !Objects.equals(allocation.getSourceId(), row.getSourceId())
                || !Objects.equals(row.getOriginalAllocationId(), allocation.getId())
                || allocation.getMode() != row.getAccountingMode()
                || allocation.getAmountKopecks() != row.getAmountKopecks()
                || !sameContractor(row, allocation)) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    private void requireActualAllocation(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation
    ) {
        if (allocation.getSourceType() != ContractorAllocationSourceType.ACTUAL_PAYMENT
                || !Objects.equals(allocation.getSourceId(), row.getId())
                || allocation.getMode() != row.getAccountingMode()
                || allocation.getAmountKopecks() != row.getAmountKopecks()
                || !sameContractor(row, allocation)) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    private void requireBoundAllocation(Binding binding, ContractorPaymentAllocation allocation) {
        if (allocation == null
                || !Objects.equals(allocation.getId(), binding.allocationId())
                || allocation.getMode() != binding.mode()
                || allocation.getSourceType() != binding.allocationSourceType()
                || !Objects.equals(allocation.getSourceId(), binding.allocationSourceId())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    private boolean sameContractor(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation
    ) {
        Long profileId = allocation.getRecipientProfile() == null
                ? null : allocation.getRecipientProfile().getId();
        return row.getActualRecipientType() == allocation.getRecipientType()
                && Objects.equals(row.getActualRecipientProfileId(), profileId)
                && row.getActualRecipientProfileId() != null;
    }

    private boolean taskDestination(ContractorActualPaymentAttribution row) {
        return row != null
                && row.getActualCashDestinationKind() == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                && (row.getActualManualPaymentTaskTargetKind()
                        == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                || row.getActualManualPaymentTaskTargetKind()
                        == ManualPaymentTaskAccountingTargetKind.MANAGER);
    }

    private boolean anyTaskDestination(ContractorActualPaymentAttribution row) {
        return row != null
                && row.getActualCashDestinationKind() == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                && row.getActualManualPaymentTaskId() != null
                && row.getActualManualPaymentTaskGeneration() != null
                && row.getActualManualPaymentTaskTargetKind() != null
                && row.getActualManualPaymentTaskTargetKind()
                        != ManualPaymentTaskAccountingTargetKind.UNRESOLVED;
    }

    private boolean ordinaryRedirectDestination(ContractorActualPaymentAttribution row) {
        if (row == null
                || row.getActualManualPaymentTaskId() != null
                || row.getActualManualPaymentTaskGeneration() != null
                || row.getActualManualPaymentTaskTargetKind() != null) {
            return false;
        }
        if (row.getActualCashDestinationKind() == ContractorCashDestinationKind.OWNER) {
            return row.getActualRecipientType() == ContractorRecipientType.OWNER
                    && row.getActualRecipientProfileId() == null;
        }
        return row.getActualCashDestinationKind() == ContractorCashDestinationKind.CONTRACTOR_PROFILE
                && (row.getActualRecipientType() == ContractorRecipientType.SPECIALIST
                    || row.getActualRecipientType() == ContractorRecipientType.MANAGER)
                && row.getActualRecipientProfileId() != null;
    }

    private boolean returnedPaymentLinkStatus(PaymentLink link) {
        PaymentLinkStatus status = link == null ? null : link.getStatus();
        return status == PaymentLinkStatus.REVERSED
                || status == PaymentLinkStatus.PARTIAL_REVERSED
                || status == PaymentLinkStatus.REFUNDED
                || status == PaymentLinkStatus.PARTIAL_REFUNDED
                || (status == PaymentLinkStatus.CANCELED
                    && PaymentReturnRecoveryState.hasLinkSpecificSettledEvidence(link));
    }

    private boolean partialPaymentLinkStatus(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.PARTIAL_REVERSED
                || status == PaymentLinkStatus.PARTIAL_REFUNDED;
    }

    private void recordTaskReturn(
            Long attributionId,
            Long taskId,
            ManualPaymentTaskSourceRef source,
            long desiredTotal,
            long ledgerReturned
    ) {
        if (ledgerReturned < 0L || desiredTotal < ledgerReturned) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        long delta = desiredTotal - ledgerReturned;
        if (delta == 0L) {
            return;
        }
        taskLedgerService.recordReturn(new ManualPaymentTaskReturnCommand(
                taskId,
                source,
                delta,
                operationKey(source, attributionId, desiredTotal),
                ACTOR,
                REASON
        ));
    }

    private void recordLegacyTaskReturn(
            Long taskId,
            ManualPaymentTaskSourceRef source,
            long desiredTotal,
            long ledgerReturned
    ) {
        if (ledgerReturned < 0L || desiredTotal < ledgerReturned) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        long delta = desiredTotal - ledgerReturned;
        if (delta == 0L) {
            return;
        }
        taskLedgerService.recordReturn(new ManualPaymentTaskReturnCommand(
                taskId,
                source,
                delta,
                "TASK:RETURN:" + source.sourceKind() + ":" + source.sourceId()
                        + ":LEGACY:TOTAL:" + desiredTotal,
                ACTOR,
                REASON
        ));
    }

    private String operationKey(Binding binding, long returnedTotalKopecks) {
        return operationKey(binding.source(), binding.attributionId(), returnedTotalKopecks);
    }

    private String operationKey(
            ManualPaymentTaskSourceRef source,
            Long attributionId,
            long returnedTotalKopecks
    ) {
        return "TASK:RETURN:" + source.sourceKind() + ":"
                + source.sourceId() + ":ATTR:" + attributionId
                + ":TOTAL:" + returnedTotalKopecks;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record Binding(
            Long attributionId,
            Long taskId,
            ManualPaymentTaskSourceRef source,
            Long allocationId,
            com.hunt.otziv.contractor_payments.model.ContractorAllocationMode mode,
            ContractorAllocationSourceType allocationSourceType,
            Long allocationSourceId,
            long attributedAmountKopecks,
            long ledgerReturnedKopecks
    ) {
        static Binding none(ContractorPaymentAllocation allocation) {
            return new Binding(
                    null, null, null,
                    allocation == null ? null : allocation.getId(),
                    allocation == null ? null : allocation.getMode(),
                    allocation == null ? null : allocation.getSourceType(),
                    allocation == null ? null : allocation.getSourceId(),
                    0L, 0L
            );
        }

        public boolean taskBound() {
            return taskId != null;
        }
    }
}
