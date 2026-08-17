package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.dto.CommonManualPaymentTaskReturnRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentTaskReturnResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.payments.dto.ManualPaymentTaskReturnCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.repository.ManualPaymentTaskArchivedSourceRepository;
import com.hunt.otziv.payments.repository.ManualPaymentTaskArchivedSourceRepository.ArchivedCommonTaskSource;
import com.hunt.otziv.payments.service.ManualPaymentTaskLedgerService;
import com.hunt.otziv.payments.service.ManualPaymentTaskRouteErrors;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Privileged exact-evidence return for common-invoice task receipts whose
 * accounting target is OWNER or EXTERNAL_TASK. Those targets intentionally
 * have no contractor allocation, so no provider/allocation return hook can
 * produce this fact for them.
 */
@Service
@RequiredArgsConstructor
public class CommonManualPaymentTaskReturnService {

    private static final String OPERATION_PREFIX = "TASK:RETURN:COMMON_INVOICE:";

    private final CommonInvoiceRepository invoiceRepository;
    private final ContractorActualPaymentAttributionRepository attributionRepository;
    private final ManualPaymentTaskArchivedSourceRepository archivedSourceRepository;
    private final ManualPaymentTaskLedgerService taskLedgerService;

    @Transactional
    public CommonManualPaymentTaskReturnResponse record(
            Long invoiceId,
            CommonManualPaymentTaskReturnRequest request,
            String actor
    ) {
        if (invoiceId == null || invoiceId <= 0 || request == null) {
            throw badRequest("Некорректный источник возврата");
        }
        String evidenceReference = normalize(request.evidenceReference());
        String reason = normalize(request.reason());
        String normalizedActor = normalize(actor);
        if (evidenceReference.isBlank() || reason.isBlank() || normalizedActor.isBlank()) {
            throw badRequest("Укажите атрибуцию, подтверждение, причину и исполнителя возврата");
        }

        CommonInvoice live = invoiceRepository.findByIdForUpdate(invoiceId).orElse(null);
        ArchivedCommonTaskSource archived = live == null
                ? archivedSourceRepository.lockCommonTaskInvoice(invoiceId)
                : null;
        if (live == null && archived == null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }

        List<ContractorActualPaymentAttribution> discoveredRows = attributionRepository
                .findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                        ContractorActualPaymentSourceKind.COMMON_INVOICE, invoiceId);
        if (request.attributionId() == null) {
            return recordLegacy(
                    invoiceId, live, archived, discoveredRows,
                    request.cumulativeReturnedKopecks(), evidenceReference,
                    reason, normalizedActor
            );
        }
        if (request.attributionId() <= 0) {
            throw badRequest("Некорректная атрибуция возврата");
        }
        ContractorActualPaymentAttribution discovered = selectExactTaskAttribution(
                discoveredRows,
                request.attributionId(),
                "Источник содержит неоднозначную разбивку заданий; требуется ручная сверка"
        );
        requireExactAttribution(invoiceId, request, evidenceReference, discovered);

        LockedSource locked = live == null
                ? archivedSource(discovered, archived)
                : liveSource(live, discovered);

        // Preserve the financial lock order used by confirmation:
        // source -> task/ledger -> immutable attribution. Discovery above is
        // non-locking and is fully revalidated after the task lock is held.
        List<ContractorActualPaymentAttribution> lockedRows = attributionRepository
                .findAllBySourceForUpdate(ContractorActualPaymentSourceKind.COMMON_INVOICE, invoiceId);
        ContractorActualPaymentAttribution row = selectExactTaskAttribution(
                lockedRows,
                request.attributionId(),
                "Источник изменил разбивку заданий; требуется ручная сверка"
        );
        requireExactAttribution(invoiceId, request, evidenceReference, row);
        requireSameImmutableAttribution(discovered, row);
        long requestedTotal = request.cumulativeReturnedKopecks();
        long attributedAmount = row.getAmountKopecks();
        if (requestedTotal > attributedAmount
                || locked.returnedKopecks() < 0
                || locked.returnedKopecks() > attributedAmount
                || requestedTotal < locked.returnedKopecks()) {
            throw conflict("Накопительный возврат не соответствует подтверждённой сумме источника");
        }
        long delta = requestedTotal - locked.returnedKopecks();
        if (delta > 0) {
            taskLedgerService.recordReturn(new ManualPaymentTaskReturnCommand(
                    row.getActualManualPaymentTaskId(),
                    locked.source(),
                    delta,
                    operationKey(invoiceId, row.getId(), requestedTotal),
                    normalizedActor,
                    reason
            ));
        }
        return new CommonManualPaymentTaskReturnResponse(
                invoiceId,
                row.getId(),
                row.getActualManualPaymentTaskId(),
                requestedTotal,
                delta,
                delta == 0
        );
    }

    private LockedSource liveSource(
            CommonInvoice invoice,
            ContractorActualPaymentAttribution row
    ) {
        if (invoice.getPaymentRouteManualSource() != ManualPaymentSource.MANUAL_TASK
                || !Objects.equals(invoice.getPaymentRouteManualTaskId(),
                        row.getActualManualPaymentTaskId())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        boolean completeBinding = invoice.getPaymentRouteManualTaskGeneration() != null
                && !normalize(invoice.getPaymentRouteManualTaskSourceGeneration()).isBlank();
        boolean restoredWithoutV252Binding = invoice.getPaymentRouteManualTaskGeneration() == null
                && normalize(invoice.getPaymentRouteManualTaskSourceGeneration()).isBlank();
        if (completeBinding) {
            if (!Objects.equals(invoice.getPaymentRouteManualTaskGeneration(),
                    row.getActualManualPaymentTaskGeneration())) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                    ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                    invoice.getId(),
                    normalize(invoice.getPaymentRouteManualTaskSourceGeneration())
            );
            return new LockedSource(
                    source,
                    taskLedgerService.lockReturnSource(row.getActualManualPaymentTaskId(), source)
            );
        }
        if (!restoredWithoutV252Binding) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        return archivedSource(row, null);
    }

    private LockedSource archivedSource(
            ContractorActualPaymentAttribution row,
            ArchivedCommonTaskSource archived
    ) {
        if (archived != null
                && !Objects.equals(archived.taskId(), row.getActualManualPaymentTaskId())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        ManualPaymentTaskLedgerService.LockedArchivedReturnSource locked =
                taskLedgerService.lockArchivedReturnSource(
                        row.getActualManualPaymentTaskId(),
                        row.getActualManualPaymentTaskGeneration(),
                        ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE,
                        row.getSourceId()
                );
        return new LockedSource(locked.source(), locked.returnedKopecks());
    }

    private CommonManualPaymentTaskReturnResponse recordLegacy(
            Long invoiceId,
            CommonInvoice live,
            ArchivedCommonTaskSource archived,
            List<ContractorActualPaymentAttribution> discoveredRows,
            long requestedTotal,
            String evidenceReference,
            String reason,
            String actor
    ) {
        if (discoveredRows != null && !discoveredRows.isEmpty()) {
            throw conflict("У источника уже есть типизированная атрибуция; укажите её точно");
        }
        ManualPaymentTaskLedgerService.LockedLegacyReturnSource locked =
                taskLedgerService.lockLegacyConfirmedReturnSource(
                        ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, invoiceId);
        requireLegacySourceIdentity(invoiceId, live, archived, locked, evidenceReference);
        if (requestedTotal < locked.returnedKopecks()
                || requestedTotal > locked.confirmedKopecks()) {
            throw conflict("Накопительный возврат не соответствует исторической сумме источника");
        }
        long delta = requestedTotal - locked.returnedKopecks();
        if (delta > 0) {
            taskLedgerService.recordReturn(new ManualPaymentTaskReturnCommand(
                    locked.taskId(),
                    locked.source(),
                    delta,
                    legacyOperationKey(invoiceId, requestedTotal),
                    actor,
                    reason
            ));
        }
        return new CommonManualPaymentTaskReturnResponse(
                invoiceId,
                null,
                locked.taskId(),
                requestedTotal,
                delta,
                delta == 0
        );
    }

    private void requireLegacySourceIdentity(
            Long invoiceId,
            CommonInvoice live,
            ArchivedCommonTaskSource archived,
            ManualPaymentTaskLedgerService.LockedLegacyReturnSource locked,
            String evidenceReference
    ) {
        if (!Objects.equals(locked.source().sourceId(), invoiceId)
                || locked.source().sourceKind() != ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE
                || !Objects.equals(normalize(locked.evidenceReference()), evidenceReference)
                || locked.confirmedKopecks() <= 0L) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        Long routeTaskId = live == null ? archived.taskId() : live.getPaymentRouteManualTaskId();
        Long routeAmount = live == null
                ? archived.routeAmountKopecks() : live.getPaymentRouteAmountKopecks();
        if ((live != null && live.getPaymentRouteManualSource() != ManualPaymentSource.MANUAL_TASK)
                || !Objects.equals(routeTaskId, locked.taskId())
                || !Objects.equals(routeAmount, locked.confirmedKopecks())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (live == null) {
            return;
        }
        String sourceGeneration = normalize(live.getPaymentRouteManualTaskSourceGeneration());
        boolean completeBinding = live.getPaymentRouteManualTaskGeneration() != null
                && !sourceGeneration.isBlank();
        boolean missingBinding = live.getPaymentRouteManualTaskGeneration() == null
                && sourceGeneration.isBlank();
        if ((!completeBinding && !missingBinding)
                || (completeBinding
                    && (!Objects.equals(live.getPaymentRouteManualTaskGeneration(), locked.taskGeneration())
                        || !sourceGeneration.equals(locked.source().sourceGeneration())))) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    private void requireExactAttribution(
            Long invoiceId,
            CommonManualPaymentTaskReturnRequest request,
            String evidenceReference,
            ContractorActualPaymentAttribution row
    ) {
        ManualPaymentTaskAccountingTargetKind target = row == null
                ? null : row.getActualManualPaymentTaskTargetKind();
        if (row == null
                || !Objects.equals(row.getId(), request.attributionId())
                || row.getSourceKind() != ContractorActualPaymentSourceKind.COMMON_INVOICE
                || !Objects.equals(row.getSourceId(), invoiceId)
                || !Objects.equals(normalize(row.getEvidenceReference()), evidenceReference)
                || row.getActualCashDestinationKind() != ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                || (target != ManualPaymentTaskAccountingTargetKind.OWNER
                    && target != ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK)
                || row.getActualManualPaymentTaskId() == null
                || row.getActualManualPaymentTaskGeneration() == null
                || row.getActualManualPaymentTaskGeneration() <= 0
                || row.getAmountKopecks() <= 0) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
    }

    private ContractorActualPaymentAttribution selectExactTaskAttribution(
            List<ContractorActualPaymentAttribution> rows,
            Long requestedAttributionId,
            String ambiguityReason
    ) {
        List<ContractorActualPaymentAttribution> taskRows = rows == null
                ? List.of()
                : rows.stream().filter(this::isTaskAttribution).toList();
        if (taskRows.size() != 1
                || !Objects.equals(taskRows.getFirst().getId(), requestedAttributionId)) {
            throw conflict(ambiguityReason);
        }
        return taskRows.getFirst();
    }

    private boolean isTaskAttribution(ContractorActualPaymentAttribution row) {
        return row != null
                && row.getActualCashDestinationKind()
                        == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                && row.getActualManualPaymentTaskId() != null
                && row.getActualManualPaymentTaskGeneration() != null
                && row.getActualManualPaymentTaskTargetKind() != null
                && row.getActualManualPaymentTaskTargetKind()
                        != ManualPaymentTaskAccountingTargetKind.UNRESOLVED;
    }

    private void requireSameImmutableAttribution(
            ContractorActualPaymentAttribution discovered,
            ContractorActualPaymentAttribution locked
    ) {
        if (discovered == null || locked == null
                || !Objects.equals(discovered.getId(), locked.getId())
                || discovered.getSourceKind() != locked.getSourceKind()
                || !Objects.equals(discovered.getSourceId(), locked.getSourceId())
                || !Objects.equals(normalize(discovered.getEvidenceReference()),
                        normalize(locked.getEvidenceReference()))
                || discovered.getActualCashDestinationKind()
                        != locked.getActualCashDestinationKind()
                || !Objects.equals(discovered.getActualManualPaymentTaskId(),
                        locked.getActualManualPaymentTaskId())
                || !Objects.equals(discovered.getActualManualPaymentTaskGeneration(),
                        locked.getActualManualPaymentTaskGeneration())
                || discovered.getActualManualPaymentTaskTargetKind()
                        != locked.getActualManualPaymentTaskTargetKind()
                || discovered.getAmountKopecks() != locked.getAmountKopecks()) {
            throw conflict("Атрибуция источника изменилась; требуется ручная сверка");
        }
    }

    private String operationKey(Long invoiceId, Long attributionId, long total) {
        return OPERATION_PREFIX + invoiceId + ":ATTR:" + attributionId + ":TOTAL:" + total;
    }

    private String legacyOperationKey(Long invoiceId, long total) {
        return OPERATION_PREFIX + invoiceId + ":LEGACY:TOTAL:" + total;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private record LockedSource(ManualPaymentTaskSourceRef source, long returnedKopecks) {
    }
}
