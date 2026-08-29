package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorActualPaymentRecipientCommand;
import com.hunt.otziv.contractor_payments.dto.ContractorActualPaymentSource;
import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentContextResponse;
import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentRecipientResponse;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationEventRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.payments.service.ManualPaymentTaskRouteErrors;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Immutable attribution of a manually recorded customer receipt. Every new
 * row and its contractor allocation/events are committed atomically in the
 * caller's source transaction. A committed row without its accounting events
 * is treated as corruption and is never repaired implicitly by a replay.
 */
@Service
@RequiredArgsConstructor
public class ContractorActualPaymentAttributionService {

    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_EVIDENCE_REFERENCE_LENGTH = 160;
    private static final int MAX_RECEIPT_URL_LENGTH = 1024;
    private static final int MAX_ACTOR_LENGTH = 150;
    private static final Set<ContractorAllocationStatus> OUTSTANDING = Set.of(
            ContractorAllocationStatus.RESERVED,
            ContractorAllocationStatus.CLIENT_REPORTED,
            ContractorAllocationStatus.PARTIALLY_CONFIRMED
    );
    private static final Set<ContractorAllocationStatus> SOURCE_FINAL = Set.of(
            ContractorAllocationStatus.CONFIRMED,
            ContractorAllocationStatus.SIMULATED_PAID,
            ContractorAllocationStatus.LATE_PAYMENT_AFTER_RELEASE,
            ContractorAllocationStatus.OWNER_FALLBACK,
            ContractorAllocationStatus.RELEASED_UNPAID,
            ContractorAllocationStatus.EXPIRED,
            ContractorAllocationStatus.CANCELED,
            ContractorAllocationStatus.PARTIALLY_RETURNED,
            ContractorAllocationStatus.RETURNED
    );

    private final ContractorActualPaymentAttributionRepository attributionRepository;
    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorPaymentAllocationEventRepository eventRepository;
    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorPaymentProfileService profileService;
    private final ContractorPaymentAccountingService accountingService;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final ContractorOrderManagerResolver orderManagerResolver;
    private final BusinessAuditService businessAuditService;
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    private final AppSettingService appSettingService;
    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;
    private final ManualPaymentTaskContractorCapacityService taskCapacityService;

    /** Authoritative non-mutating UI/controller gate using the same rules as the locked path. */
    @Transactional(readOnly = true)
    public boolean actualRecipientAccountingRequired() {
        try {
            return accountingEnabled(accountingPhaseService.current());
        } catch (RuntimeException readFailure) {
            return true;
        }
    }

    /** Locked compatibility gate used by financial state transitions. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean actualRecipientAccountingEnabled() {
        return accountingEnabled(accountingPhaseService.lockCurrent());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ContractorAllocationMode lockEnabledAccountingMode() {
        ContractorAllocationMode mode = accountingPhaseService.lockCurrent();
        if (accountingEnabled(mode)) {
            return mode;
        }
        throw conflict("Учёт фактического получателя выключен; используется прежний порядок ручной оплаты");
    }

    private boolean accountingEnabled(ContractorAllocationMode mode) {
        if (mode == ContractorAllocationMode.LIVE) {
            return true;
        }
        final String raw;
        try {
            raw = appSettingService.getStringFresh(
                    AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED,
                    null
            );
        } catch (RuntimeException readFailure) {
            // A config outage must never reopen the legacy no-attribution path.
            return true;
        }
        if (raw == null) {
            return false;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        // Explicit true enables accounting. A malformed existing value also
        // requires the typed path so an operator typo cannot bypass attribution.
        return true;
    }
    /**
     * Locks current phase, every involved profile in id order, then the source
     * allocation and attribution rows. Over-capacity receipts are facts and
     * are confirmed with a projected-overrun anomaly instead of being rejected.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ContractorActualPaymentAttribution> recordFinalAttributions(
            ContractorActualPaymentSource rawSource,
            List<ContractorActualPaymentRecipientCommand> rawCommands
    ) {
        return recordFinalAttributionsInternal(rawSource, rawCommands, false);
    }

    /**
     * Finishes an already issued source in the accounting mode persisted when
     * its route was frozen. The current phase remains the first financial
     * mutex, but a later SHADOW-to-LIVE promotion must not reinterpret the
     * immutable client-facing receipt.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ContractorActualPaymentAttribution> recordFinalAttributionsForFrozenSource(
            ContractorActualPaymentSource rawSource,
            List<ContractorActualPaymentRecipientCommand> rawCommands,
            ContractorAllocationMode persistedMode
    ) {
        return recordFinalAttributionsInternal(
                rawSource, rawCommands, true, requireFrozenMode(persistedMode));
    }

    private List<ContractorActualPaymentAttribution> recordFinalAttributionsInternal(
            ContractorActualPaymentSource rawSource,
            List<ContractorActualPaymentRecipientCommand> rawCommands,
            boolean committedOrFrozen
    ) {
        return recordFinalAttributionsInternal(
                rawSource, rawCommands, committedOrFrozen, null);
    }

    private List<ContractorActualPaymentAttribution> recordFinalAttributionsInternal(
            ContractorActualPaymentSource rawSource,
            List<ContractorActualPaymentRecipientCommand> rawCommands,
            boolean committedOrFrozen,
            ContractorAllocationMode frozenMode
    ) {
        ContractorActualPaymentSource source = validateSource(rawSource);
        List<ContractorActualPaymentRecipientCommand> commands = validateCommands(rawCommands);
        ContractorAllocationMode current = committedOrFrozen || frozenMode != null
                ? accountingPhaseService.lockCurrent()
                : null;
        ContractorAllocationMode mode = frozenMode != null
                ? frozenMode
                : committedOrFrozen ? current : lockEnabledAccountingMode();

        Map<String, ContractorActualPaymentAttribution> discovered = commands.stream()
                .map(ContractorActualPaymentRecipientCommand::attributionKey)
                .map(attributionRepository::findByAttributionKey)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(
                        ContractorActualPaymentAttribution::getAttributionKey,
                        Function.identity()
                ));
        if (!discovered.isEmpty() && discovered.size() != commands.size()) {
            throw conflict("Неполный повтор фиксации получателя; требуется сверка");
        }
        if (frozenMode != null && discovered.values().stream()
                .anyMatch(row -> row.getAccountingMode() != frozenMode)) {
            throw conflict("Режим учёта выданного платежа изменился; требуется сверка");
        }

        Long originalAllocationId = effectiveOriginalAllocationId(source, mode, discovered.values());
        Long clientFacingAllocationId = effectiveClientFacingAllocationId(source, discovered.values());
        Set<Long> sourceAllocationIds = latestSourceAllocationIds(source);
        addId(sourceAllocationIds, originalAllocationId);
        addId(sourceAllocationIds, clientFacingAllocationId);
        Map<Long, ContractorPaymentProfile> profiles = lockProfiles(
                discoverProfileIds(
                        source,
                        commands,
                        discovered.values(),
                        originalAllocationId,
                        clientFacingAllocationId,
                        sourceAllocationIds
                )
        );
        Map<Long, ContractorPaymentAllocation> allocations = lockAllocations(
                discoverAllocationIds(
                        originalAllocationId,
                        clientFacingAllocationId,
                        sourceAllocationIds,
                        discovered.values()
                )
        );
        ContractorPaymentAllocation original = originalAllocationId == null
                ? null : allocations.get(originalAllocationId);
        ContractorPaymentAllocation clientFacingAllocation = clientFacingAllocationId == null
                ? null : allocations.get(clientFacingAllocationId);
        Map<String, ContractorActualPaymentAttribution> replays = lockReplayRows(commands);

        if (!replays.isEmpty()) {
            List<ContractorActualPaymentAttribution> result = new ArrayList<>(commands.size());
            for (ContractorActualPaymentRecipientCommand command : commands) {
                ContractorActualPaymentAttribution row = replays.get(command.attributionKey());
                requireSameReplay(row, source, command, originalAllocationId, clientFacingAllocationId);
                requireOriginalBinding(row, original, profiles);
                requireClientFacingBinding(row, clientFacingAllocation, profiles);
                requireAccountingApplied(row, profiles, allocations);
                result.add(row);
            }
            return List.copyOf(result);
        }

        requireOriginalBinding(source, mode, original, profiles);
        requireClientFacingBinding(source, clientFacingAllocation, profiles);
        sourceAllocations(source, allocations.values())
                .forEach(allocation -> requireSourceAllocationBinding(source, allocation, profiles));
        requireNoPriorPaymentLinkConfirmation(source, original);
        ClientRecipient clientRecipient = clientRecipient(
                source,
                clientFacingAllocation == null ? original : clientFacingAllocation,
                profiles
        );
        Long originalProfileId = profileId(original);
        boolean reallocate = commands.stream().anyMatch(command ->
                command.actualRecipientType() == null
                        || command.actualRecipientType() == ContractorRecipientType.OWNER
                        || !Objects.equals(command.actualRecipientProfileId(), originalProfileId)
        );
        sourceAllocations(source, allocations.values()).stream()
                .filter(allocation -> !Objects.equals(allocation.getId(), originalAllocationId))
                .forEach(allocation -> releaseHistorical(source, allocation));
        if (reallocate) {
            releaseOriginal(source, original);
        }

        List<ContractorActualPaymentAttribution> recorded = new ArrayList<>(commands.size());
        for (ContractorActualPaymentRecipientCommand command : commands) {
            ContractorPaymentProfile actualProfile = actualProfile(command, profiles);
            if (actualProfile != null) {
                targetAccessPolicy.requireCanManageUser(userId(actualProfile));
            }
            long available = actualProfile == null
                    ? 0L
                    : capacity(actualProfile, mode, reallocate ? null : original, false);
            ContractorActualPaymentAttribution row = ContractorActualPaymentAttribution.createWithDestinations(
                    command.attributionKey(),
                    source.sourceKind(),
                    source.sourceId(),
                    source.evidenceId(),
                    source.orderId(),
                    source.commonInvoiceId(),
                    originalAllocationId,
                    clientFacingAllocationId,
                    mode,
                    clientRecipient.type(),
                    clientRecipient.profileId(),
                    clientRecipient.userId(),
                    clientRecipient.name(),
                    command.actualRecipientType(),
                    actualProfile == null ? null : actualProfile.getId(),
                    userId(actualProfile),
                    firstNonBlank(command.actualRecipientName(), recipientName(actualProfile, command.actualRecipientType())),
                    source.currentWorkerId(),
                    source.currentManagerId(),
                    command.amountKopecks(),
                    actualProfile == null ? null : available,
                    actualProfile == null ? 0L : Math.max(0L, command.amountKopecks() - available),
                    source.effectiveAt(),
                    source.reason(),
                    source.evidenceReference(),
                    source.receiptUrl(),
                    source.actor(),
                    null,
                    source.clientFacingCashDestinationKind(),
                    source.clientFacingManualPaymentTaskId(),
                    source.clientFacingManualPaymentTaskGeneration(),
                    source.clientFacingManualPaymentTaskTargetKind(),
                    command.cashDestinationKind(),
                    command.manualPaymentTaskId(),
                    command.manualPaymentTaskGeneration(),
                    command.manualPaymentTaskTargetKind()
            );
            row = attributionRepository.saveAndFlush(row);
            applyNew(row, actualProfile, original, !reallocate, available);
            recorded.add(row);
        }
        markSourceAllocationsReconciled(sourceAllocations(source, allocations.values()));
        recordBusinessAudit(source, recorded);
        return List.copyOf(recorded);
    }

    /** Strict, write-free replay verifier for already committed source batches. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ContractorActualPaymentAttribution> requireFinalAttributionsAccountingApplied(
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId
    ) {
        if (sourceKind == null || sourceId == null || sourceId <= 0) {
            throw badRequest("Некорректный источник атрибуции");
        }
        List<ContractorActualPaymentAttribution> rows = attributionRepository
                .findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(sourceKind, sourceId);
        if (rows.isEmpty()) {
            throw conflict("У завершённой оплаты отсутствует атрибуция; требуется сверка");
        }
        ContractorActualPaymentAttribution first = rows.getFirst();
        ContractorActualPaymentSource source = sourceFrom(first);
        List<ContractorActualPaymentRecipientCommand> commands = rows.stream()
                .map(this::commandFrom)
                .toList();
        return recordFinalAttributionsInternal(source, commands, true);
    }

    /** Strict replay verifier for a source whose accounting mode was frozen. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ContractorActualPaymentAttribution>
    requireFinalAttributionsAccountingAppliedForFrozenSource(
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId,
            ContractorAllocationMode persistedMode
    ) {
        ContractorAllocationMode mode = requireFrozenMode(persistedMode);
        if (sourceKind == null || sourceId == null || sourceId <= 0) {
            throw badRequest("Некорректный источник атрибуции");
        }
        List<ContractorActualPaymentAttribution> rows = attributionRepository
                .findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(sourceKind, sourceId);
        if (rows.isEmpty()) {
            throw conflict("У завершённой оплаты отсутствует атрибуция; требуется сверка");
        }
        if (rows.stream().anyMatch(row -> row.getAccountingMode() != mode)) {
            throw conflict("Режим учёта выданного платежа изменился; требуется сверка");
        }
        ContractorActualPaymentSource source = sourceFrom(rows.getFirst());
        List<ContractorActualPaymentRecipientCommand> commands = rows.stream()
                .map(this::commandFrom)
                .toList();
        return recordFinalAttributionsInternal(source, commands, true, mode);
    }

    private ContractorAllocationMode requireFrozenMode(ContractorAllocationMode mode) {
        if (mode != ContractorAllocationMode.SHADOW && mode != ContractorAllocationMode.LIVE) {
            throw conflict("У выданного платежа отсутствует режим учёта; требуется сверка");
        }
        return mode;
    }

    private ContractorAllocationMode issuedTaskAccountingMode(PaymentLink link) {
        if (link == null
                || link.getManualSource() != ManualPaymentSource.MANUAL_TASK
                || link.getManualPaymentTask() == null
                || link.getManualPaymentTask().getId() == null
                || link.getManualTaskGeneration() == null
                || link.getManualTaskGeneration() <= 0L
                || normalize(link.getManualTaskSourceGeneration()).isBlank()) {
            return null;
        }
        return link.getManualActualAccountingMode() == null
                ? null : requireFrozenMode(link.getManualActualAccountingMode());
    }

    private ContractorActualPaymentSource sourceFrom(ContractorActualPaymentAttribution row) {
        return new ContractorActualPaymentSource(
                row.getSourceKind(),
                row.getSourceId(),
                row.getEvidenceId(),
                row.getOrderId(),
                row.getCommonInvoiceId(),
                row.getOriginalAllocationId(),
                row.getClientFacingAllocationId(),
                row.getOriginalRecipientType(),
                row.getOriginalRecipientProfileId(),
                row.getOriginalRecipientNameSnapshot(),
                row.getCurrentWorkerId(),
                row.getCurrentManagerId(),
                row.getEffectiveAt(),
                row.getReason(),
                row.getEvidenceReference(),
                row.getReceiptUrl(),
                row.getActor(),
                row.getOriginalCashDestinationKind(),
                row.getOriginalManualPaymentTaskId(),
                row.getOriginalManualPaymentTaskGeneration(),
                row.getOriginalManualPaymentTaskTargetKind()
        );
    }

    private ContractorActualPaymentRecipientCommand commandFrom(ContractorActualPaymentAttribution row) {
        return new ContractorActualPaymentRecipientCommand(
                row.getAttributionKey(),
                row.getActualRecipientType(),
                row.getActualRecipientProfileId(),
                row.getAmountKopecks(),
                row.getActualRecipientNameSnapshot(),
                row.getActualCashDestinationKind() == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                        ? com.hunt.otziv.payments.service.ManualPaymentTaskLedgerService.candidateKey(
                                row.getActualManualPaymentTaskId(), row.getActualManualPaymentTaskGeneration())
                        : recipientKey(row.getActualRecipientType(), row.getActualRecipientProfileId()),
                row.getActualCashDestinationKind(),
                row.getActualManualPaymentTaskId(),
                row.getActualManualPaymentTaskGeneration(),
                row.getActualManualPaymentTaskTargetKind()
        );
    }
    /** Preview only. Submit repeats every identity and capacity check under locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ManualCardPaymentContextResponse manualCardPaymentContext(Order order, PaymentLink link) {
        requireOrdinarySource(order, link);
        ContractorAllocationMode currentMode = accountingPhaseService.lockCurrent();
        ContractorAllocationMode issuedTaskMode = issuedTaskAccountingMode(link);
        ContractorAllocationMode frozenMode = link.getManualActualRecipientFrozenAt() == null
                ? null : requireFrozenMode(link.getManualActualAccountingMode());
        if (!accountingEnabled(currentMode) && frozenMode == null && issuedTaskMode == null) {
            return legacyManualCardPaymentContext(order, link);
        }
        ContractorAllocationMode mode = frozenMode != null
                ? frozenMode : issuedTaskMode != null ? issuedTaskMode : currentMode;
        Long accountingAllocationId = currentAccountingAllocationId(link, mode);
        Long clientFacingAllocationId = link.getContractorAllocationId();
        Optional<ManualPaymentTaskRouteSnapshot> taskCandidate = taskReceiptIntegrationService.candidate(link);
        if (issuedTaskMode != null && taskCandidate.isEmpty()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        Set<Long> profileIds = ordinaryCandidateProfileIds(
                order, link, accountingAllocationId, clientFacingAllocationId);
        taskCandidate.map(ManualPaymentTaskRouteSnapshot::accountingTargetProfileId)
                .filter(Objects::nonNull).ifPresent(profileIds::add);
        Map<Long, ContractorPaymentProfile> profiles = lockProfiles(profileIds);
        Set<Long> allocationIds = new LinkedHashSet<>();
        addId(allocationIds, accountingAllocationId);
        addId(allocationIds, clientFacingAllocationId);
        Map<Long, ContractorPaymentAllocation> allocations = lockAllocations(allocationIds);
        ContractorPaymentAllocation accountingAllocation = accountingAllocationId == null
                ? null
                : allocations.get(accountingAllocationId);
        ContractorPaymentAllocation clientFacingAllocation = clientFacingAllocationId == null
                ? null
                : allocations.get(clientFacingAllocationId);
        requireOriginalBinding(
                paymentLinkSource(order, link, accountingAllocationId), mode, accountingAllocation, profiles
        );
        requirePaymentLinkAllocationBinding(order, link, clientFacingAllocation, null, profiles);
        requireNoExistingNetConfirmation(accountingAllocation);

        ClientRecipient client = frozenClientRecipient(link, profiles);
        if (client == null) {
            client = clientRecipientForPaymentLink(link, clientFacingAllocation, profiles);
        }
        LinkedHashMap<String, ManualCardPaymentRecipientResponse> values = new LinkedHashMap<>();
        if (taskCandidate.isPresent()) {
            addTaskCandidate(values, taskCandidate.get(), profiles, mode, accountingAllocation,
                    link.getAmountKopecks(), true);
        } else {
            addCandidate(values, client, profiles, mode, accountingAllocation, link.getAmountKopecks(), true);
        }
        addAssignedCandidates(values, order, profiles, mode, accountingAllocation, link.getAmountKopecks(), client);
        addProfileCandidate(values, profileId(accountingAllocation), profiles, mode, accountingAllocation,
                link.getAmountKopecks(), client);
        addProfileCandidate(
                values,
                link.getManualActualRecipientProfileId(),
                profiles,
                mode,
                accountingAllocation,
                link.getAmountKopecks(),
                client
        );
        addCandidate(
                values,
                new ClientRecipient(ContractorRecipientType.OWNER, null, null, "Владелец"),
                profiles,
                mode,
                accountingAllocation,
                link.getAmountKopecks(),
                sameRecipient(client, ContractorRecipientType.OWNER, null)
        );
        taskCandidate.ifPresent(snapshot -> suppressOrdinaryAliasForTask(values, snapshot));
        ManualCardPaymentRecipientResponse originalResponse = values.values().stream()
                .filter(ManualCardPaymentRecipientResponse::original)
                .findFirst()
                .orElseThrow(() -> conflict("Исходный получатель платежа не определён"));
        String warning = values.values().stream().anyMatch(value -> value.projectedOverrunKopecks() > 0)
                ? "Для некоторых получателей сумма превышает доступный остаток; поступление будет учтено с превышением"
                : null;
        ManualCardPaymentRecipientResponse preparedRecipient = link.getManualActualRecipientFrozenAt() == null
                ? null
                : values.values().stream()
                        .filter(value -> value.key().equals(frozenRecipientKey(link)))
                        .findFirst()
                        .orElseThrow(() -> conflict("Ранее выбранный фактический получатель больше недоступен"));
        return new ManualCardPaymentContextResponse(
                order.getId(),
                link.getAmountKopecks(),
                originalResponse,
                List.copyOf(values.values()),
                warning,
                link.getManualActualRecipientFrozenAt() != null,
                preparedRecipient,
                link.getManualActualReason(),
                link.getManualActualReceiptUrl(),
                "TASK_RECIPIENT_V1",
                taskCandidate.map(value -> value.source().sourceGeneration()).orElse(null)
        );
    }

    /** Freezes an authorized typed selection before any non-transactional bank call. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void freezePaymentLinkRecipientIntent(
            Order order,
            PaymentLink link,
            String requestedRecipientKey,
            ContractorRecipientType requestedType,
            Long requestedProfileId,
            String rawReason,
            String rawReceiptUrl,
            String rawActor
    ) {
        String reason = required(rawReason, MAX_REASON_LENGTH, "Причина");
        String receiptUrl = optionalReceiptUrl(rawReceiptUrl);
        String actor = required(rawActor, MAX_ACTOR_LENGTH, "Исполнитель операции");
        if (link.getManualActualRecipientFrozenAt() != null) {
            requireFrozenReplay(link, requestedRecipientKey, requestedType, requestedProfileId, reason, receiptUrl);
            return;
        }
        ManualCardPaymentContextResponse context = manualCardPaymentContext(order, link);
        ManualCardPaymentRecipientResponse selected = selectRecipient(
                context, requestedRecipientKey, requestedType, requestedProfileId);
        ContractorAllocationMode mode = issuedTaskAccountingMode(link);
        if (mode == null) {
            mode = lockEnabledAccountingMode();
        }
        Long originalAllocationId = currentAccountingAllocationId(link, mode);
        Worker worker = order.getWorker();
        Manager manager = orderManagerResolver.resolveForRouting(order);

        link.setManualActualAccountingMode(mode);
        link.setManualActualOriginalCashDestinationKind(context.originalRecipient().cashDestinationKind());
        link.setManualActualOriginalTaskId(context.originalRecipient().manualPaymentTaskId());
        link.setManualActualOriginalTaskGeneration(context.originalRecipient().manualPaymentTaskGeneration());
        link.setManualActualOriginalTaskTargetKind(context.originalRecipient().taskTargetKind());
        link.setManualActualOriginalAllocationId(originalAllocationId);
        link.setManualActualClientFacingAllocationId(link.getContractorAllocationId());
        link.setManualActualOriginalRecipientType(context.originalRecipient().recipientType());
        link.setManualActualOriginalRecipientProfileId(context.originalRecipient().recipientProfileId());
        link.setManualActualOriginalRecipientUserId(context.originalRecipient().recipientUserId());
        link.setManualActualOriginalRecipientNameSnapshot(context.originalRecipient().displayName());
        link.setManualActualCashDestinationKind(selected.cashDestinationKind());
        link.setManualActualTaskId(selected.manualPaymentTaskId());
        link.setManualActualTaskGeneration(selected.manualPaymentTaskGeneration());
        link.setManualActualTaskTargetKind(selected.taskTargetKind());
        link.setManualActualRecipientType(selected.recipientType());
        link.setManualActualRecipientProfileId(selected.recipientProfileId());
        link.setManualActualRecipientUserId(selected.recipientUserId());
        link.setManualActualRecipientNameSnapshot(selected.displayName());
        link.setManualActualCurrentWorkerId(worker == null ? null : worker.getId());
        link.setManualActualCurrentManagerId(manager == null ? null : manager.getId());
        link.setManualActualReason(reason);
        link.setManualActualReceiptUrl(receiptUrl);
        link.setManualActualActor(actor);
        link.setManualActualRecipientFrozenAt(LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ContractorActualPaymentAttribution recordPaymentLinkFinalAttribution(
            Order order,
            PaymentLink originalLink,
            PaymentLink evidenceLink
    ) {
        requireOrdinarySource(order, originalLink);
        requireFrozenIntent(originalLink);
        // A frozen intent must remain finishable if test routing is disabled
        // after the bank route was irreversibly closed. New operations still
        // pass through lockEnabledAccountingMode() before freezing.
        ContractorAllocationMode frozenMode = requireFrozenMode(
                originalLink.getManualActualAccountingMode());
        ContractorAllocationMode currentMode = accountingPhaseService.lockCurrent();
        taskReceiptIntegrationService.lockTaskForFinalAttribution(originalLink);
        Long originalAllocationId = originalLink.getManualActualAccountingMode() == currentMode
                ? originalLink.getManualActualOriginalAllocationId()
                : null;
        ContractorActualPaymentSource source = new ContractorActualPaymentSource(
                ContractorActualPaymentSourceKind.PAYMENT_LINK,
                originalLink.getId(),
                evidenceLink.getId(),
                order.getId(),
                null,
                originalAllocationId,
                originalLink.getManualActualClientFacingAllocationId(),
                originalLink.getManualActualOriginalRecipientType(),
                originalLink.getManualActualOriginalRecipientProfileId(),
                originalLink.getManualActualOriginalRecipientNameSnapshot(),
                originalLink.getManualActualCurrentWorkerId(),
                originalLink.getManualActualCurrentManagerId(),
                evidenceLink.getPaidAt() == null ? LocalDateTime.now() : evidenceLink.getPaidAt(),
                originalLink.getManualActualReason(),
                "payment-link-evidence:" + evidenceLink.getId(),
                originalLink.getManualActualReceiptUrl(),
                originalLink.getManualActualActor()
                , originalLink.getManualActualOriginalCashDestinationKind()
                , originalLink.getManualActualOriginalTaskId()
                , originalLink.getManualActualOriginalTaskGeneration()
                , originalLink.getManualActualOriginalTaskTargetKind()
        );
        ContractorActualPaymentRecipientCommand command = new ContractorActualPaymentRecipientCommand(
                "PAYMENT_LINK:" + originalLink.getId(),
                originalLink.getManualActualRecipientType(),
                originalLink.getManualActualRecipientProfileId(),
                evidenceLink.getAmountKopecks(),
                originalLink.getManualActualRecipientNameSnapshot(),
                frozenRecipientKey(originalLink),
                originalLink.getManualActualCashDestinationKind(),
                originalLink.getManualActualTaskId(),
                originalLink.getManualActualTaskGeneration(),
                originalLink.getManualActualTaskTargetKind()
        );
        return recordFinalAttributionsInternal(
                source, List.of(command), true, frozenMode).getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCompletedPaymentReplay(
            PaymentLink link,
            long requestedAmountKopecks,
            String requestedRecipientKey,
            ContractorRecipientType requestedType,
            Long requestedProfileId,
            String reason,
            String receiptUrl
    ) {
        accountingPhaseService.lockCurrent();
        taskReceiptIntegrationService.lockTaskForFinalAttribution(link);
        if (requestedAmountKopecks != link.getAmountKopecks()) {
            throw conflict("Сумма повторной ручной оплаты отличается от уже зафиксированной");
        }
        if (link.getManualActualRecipientFrozenAt() == null) {
            boolean legacyOwnerReplay = requestedProfileId == null
                    && (requestedType == null || requestedType == ContractorRecipientType.OWNER);
            if (legacyOwnerReplay) {
                return; // legacy completed evidence created before V250
            }
            throw conflict("Завершённая оплата создана до выбора фактического получателя");
        }
        requireFrozenIntent(link);
        ContractorAllocationMode frozenMode = requireFrozenMode(
                link.getManualActualAccountingMode());
        requireFrozenReplay(
                link,
                requestedRecipientKey,
                requestedType,
                requestedProfileId,
                required(reason, MAX_REASON_LENGTH, "Причина"),
                optionalReceiptUrl(receiptUrl)
        );
        ContractorActualPaymentAttribution row = attributionRepository
                .findByAttributionKey("PAYMENT_LINK:" + link.getId())
                .orElseThrow(() -> conflict("У завершённой оплаты отсутствует атрибуция; требуется сверка"));
        if (row.getAccountingMode() != frozenMode) {
            throw conflict("Режим учёта выданного платежа изменился; требуется сверка");
        }
        if (row.getActualRecipientType() != link.getManualActualRecipientType()
                || !Objects.equals(row.getActualRecipientProfileId(), link.getManualActualRecipientProfileId())
                || row.getAmountKopecks() != link.getAmountKopecks()) {
            throw conflict("Ручная оплата уже зафиксирована на другого получателя");
        }
        ContractorActualPaymentSource source = new ContractorActualPaymentSource(
                row.getSourceKind(),
                row.getSourceId(),
                row.getEvidenceId(),
                row.getOrderId(),
                row.getCommonInvoiceId(),
                row.getOriginalAllocationId(),
                row.getClientFacingAllocationId(),
                row.getOriginalRecipientType(),
                row.getOriginalRecipientProfileId(),
                row.getOriginalRecipientNameSnapshot(),
                row.getCurrentWorkerId(),
                row.getCurrentManagerId(),
                row.getEffectiveAt(),
                row.getReason(),
                row.getEvidenceReference(),
                row.getReceiptUrl(),
                row.getActor(),
                row.getOriginalCashDestinationKind(),
                row.getOriginalManualPaymentTaskId(),
                row.getOriginalManualPaymentTaskGeneration(),
                row.getOriginalManualPaymentTaskTargetKind()
        );
        ContractorActualPaymentRecipientCommand command = new ContractorActualPaymentRecipientCommand(
                row.getAttributionKey(),
                row.getActualRecipientType(),
                row.getActualRecipientProfileId(),
                row.getAmountKopecks(),
                row.getActualRecipientNameSnapshot(),
                frozenRecipientKey(link),
                row.getActualCashDestinationKind(),
                row.getActualManualPaymentTaskId(),
                row.getActualManualPaymentTaskGeneration(),
                row.getActualManualPaymentTaskTargetKind()
        );
        recordFinalAttributionsInternal(source, List.of(command), true, frozenMode);
    }


    private ContractorActualPaymentSource validateSource(ContractorActualPaymentSource source) {
        if (source == null || source.sourceKind() == null || source.sourceId() == null || source.sourceId() <= 0) {
            throw badRequest("Некорректный источник фактического поступления");
        }
        String reason = required(source.reason(), MAX_REASON_LENGTH, "Причина");
        String evidence = required(
                source.evidenceReference(), MAX_EVIDENCE_REFERENCE_LENGTH, "Ссылка на подтверждение"
        );
        String receiptUrl = optionalReceiptUrl(source.receiptUrl());
        String actor = required(source.actor(), MAX_ACTOR_LENGTH, "Исполнитель операции");
        String clientName = optional(source.clientFacingRecipientName(), 255, "Имя исходного получателя");
        validateDestination(source.clientFacingCashDestinationKind(), source.clientFacingRecipientType(),
                source.clientFacingRecipientProfileId(), source.clientFacingManualPaymentTaskId(),
                source.clientFacingManualPaymentTaskGeneration(),
                source.clientFacingManualPaymentTaskTargetKind(), true);
        return new ContractorActualPaymentSource(
                source.sourceKind(), source.sourceId(), source.evidenceId(), source.orderId(), source.commonInvoiceId(),
                source.originalAllocationId(), source.clientFacingAllocationId(), source.clientFacingRecipientType(),
                source.clientFacingRecipientProfileId(), clientName, source.currentWorkerId(), source.currentManagerId(),
                source.effectiveAt() == null ? LocalDateTime.now() : source.effectiveAt(),
                reason, evidence, receiptUrl, actor, source.clientFacingCashDestinationKind(),
                source.clientFacingManualPaymentTaskId(), source.clientFacingManualPaymentTaskGeneration(),
                source.clientFacingManualPaymentTaskTargetKind()
        );
    }

    private List<ContractorActualPaymentRecipientCommand> validateCommands(
            List<ContractorActualPaymentRecipientCommand> commands
    ) {
        if (commands == null || commands.isEmpty()) {
            throw badRequest("Не выбран фактический получатель");
        }
        Set<String> keys = new LinkedHashSet<>();
        List<ContractorActualPaymentRecipientCommand> result = new ArrayList<>(commands.size());
        for (ContractorActualPaymentRecipientCommand command : commands) {
            if (command == null || command.amountKopecks() <= 0) {
                throw badRequest("Сумма фактического поступления должна быть положительной");
            }
            String key = required(command.attributionKey(), 160, "Ключ операции");
            if (!keys.add(key)) {
                throw badRequest("Ключи строк фактического поступления должны быть уникальными");
            }
            validateDestination(command.cashDestinationKind(), command.actualRecipientType(),
                    command.actualRecipientProfileId(), command.manualPaymentTaskId(),
                    command.manualPaymentTaskGeneration(), command.manualPaymentTaskTargetKind(), false);
            result.add(new ContractorActualPaymentRecipientCommand(
                    key,
                    command.actualRecipientType(),
                    command.actualRecipientProfileId(),
                    command.amountKopecks(),
                    optional(command.actualRecipientName(), 255, "Имя фактического получателя"),
                    required(command.recipientKey(), 160, "Ключ получателя"),
                    command.cashDestinationKind(), command.manualPaymentTaskId(),
                    command.manualPaymentTaskGeneration(), command.manualPaymentTaskTargetKind()
            ));
        }
        return List.copyOf(result);
    }

    private void validateIdentity(ContractorRecipientType type, Long profileId, boolean nullable) {
        if (type == null) {
            if (nullable && profileId == null) {
                return;
            }
            throw badRequest("Не указан тип фактического получателя");
        }
        if (type == ContractorRecipientType.OWNER && profileId != null) {
            throw badRequest("Для владельца платёжный профиль не указывается");
        }
        if (type != ContractorRecipientType.OWNER && (profileId == null || profileId <= 0)) {
            throw badRequest("Для работника требуется платёжный профиль");
        }
    }

    private void validateDestination(
            ContractorCashDestinationKind kind, ContractorRecipientType type, Long profileId,
            Long taskId, Long taskGeneration, ManualPaymentTaskAccountingTargetKind taskTargetKind,
            boolean nullable
    ) {
        if (kind == null) {
            if (nullable && type == null && profileId == null) return;
            throw badRequest("Не указано денежное направление");
        }
        if (kind == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK) {
            if (taskId == null || taskId <= 0 || taskGeneration == null || taskGeneration <= 0
                    || taskTargetKind == null || taskTargetKind == ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
                throw ManualPaymentTaskRouteErrors.unresolved();
            }
            if (taskTargetKind == ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK) {
                if (type != null || profileId != null) throw badRequest("Внешний получатель задания не является работником");
                return;
            }
            ContractorRecipientType expected = switch (taskTargetKind) {
                case OWNER -> ContractorRecipientType.OWNER;
                case SPECIALIST -> ContractorRecipientType.SPECIALIST;
                case MANAGER -> ContractorRecipientType.MANAGER;
                default -> null;
            };
            if (type != expected) throw badRequest("Цель задания не совпадает с получателем");
            validateIdentity(type, profileId, false);
            return;
        }
        if (taskId != null || taskGeneration != null || taskTargetKind != null) {
            throw badRequest("Задание не должно быть указано для другого направления");
        }
        validateIdentity(type, profileId, nullable);
        if (kind == ContractorCashDestinationKind.OWNER && type != ContractorRecipientType.OWNER) {
            throw badRequest("Направление владельца не совпадает с получателем");
        }
        if (kind == ContractorCashDestinationKind.CONTRACTOR_PROFILE
                && (type == null || type == ContractorRecipientType.OWNER)) {
            throw badRequest("Направление работника не совпадает с получателем");
        }
    }

    private Long effectiveOriginalAllocationId(
            ContractorActualPaymentSource source,
            ContractorAllocationMode mode,
            Collection<ContractorActualPaymentAttribution> replays
    ) {
        Long replayId = replays.stream()
                .map(ContractorActualPaymentAttribution::getOriginalAllocationId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (replayId != null) {
            return replayId;
        }
        Long latest = allocationRepository.findLatestId(
                mode.name(), allocationSourceType(source.sourceKind()).name(), source.sourceId()
        ).orElse(null);
        if (source.originalAllocationId() != null
                && !Objects.equals(source.originalAllocationId(), latest)) {
            throw conflict("Исходное распределение больше не является текущим для режима учёта");
        }
        return latest;
    }

    private Long effectiveClientFacingAllocationId(
            ContractorActualPaymentSource source,
            Collection<ContractorActualPaymentAttribution> replays
    ) {
        if (source.clientFacingAllocationId() != null) {
            return source.clientFacingAllocationId();
        }
        return replays.stream()
                .map(ContractorActualPaymentAttribution::getClientFacingAllocationId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Set<Long> latestSourceAllocationIds(ContractorActualPaymentSource source) {
        return new LinkedHashSet<>(allocationRepository.findLatestIdsBySourceAcrossModes(
                allocationSourceType(source.sourceKind()).name(),
                source.sourceId()
        ));
    }

    private Long currentAccountingAllocationId(PaymentLink link, ContractorAllocationMode mode) {
        return allocationRepository.findLatestId(
                mode.name(), ContractorAllocationSourceType.PAYMENT_LINK.name(), link.getId()
        ).orElse(null);
    }

    private ContractorAllocationSourceType allocationSourceType(ContractorActualPaymentSourceKind kind) {
        return kind == ContractorActualPaymentSourceKind.COMMON_INVOICE
                ? ContractorAllocationSourceType.COMMON_INVOICE
                : ContractorAllocationSourceType.PAYMENT_LINK;
    }

    private Set<Long> discoverProfileIds(
            ContractorActualPaymentSource source,
            List<ContractorActualPaymentRecipientCommand> commands,
            Collection<ContractorActualPaymentAttribution> replays,
            Long originalAllocationId,
            Long clientFacingAllocationId,
            Collection<Long> sourceAllocationIds
    ) {
        Set<Long> ids = new LinkedHashSet<>();
        addId(ids, source.clientFacingRecipientProfileId());
        commands.forEach(command -> addId(ids, command.actualRecipientProfileId()));
        replays.forEach(row -> {
            addId(ids, row.getOriginalRecipientProfileId());
            addId(ids, row.getActualRecipientProfileId());
        });
        Set<Long> allocationIds = new LinkedHashSet<>();
        addId(allocationIds, originalAllocationId);
        addId(allocationIds, clientFacingAllocationId);
        if (sourceAllocationIds != null) {
            allocationIds.addAll(sourceAllocationIds);
        }
        replays.forEach(row -> {
            addId(allocationIds, row.getOriginalAllocationId());
            addId(allocationIds, row.getClientFacingAllocationId());
        });
        allocationIds.forEach(id -> allocationRepository.findRecipientProfileIdById(id).ifPresent(ids::add));
        return ids;
    }

    private Set<Long> discoverAllocationIds(
            Long originalAllocationId,
            Long clientFacingAllocationId,
            Collection<Long> sourceAllocationIds,
            Collection<ContractorActualPaymentAttribution> replays
    ) {
        Set<Long> ids = new LinkedHashSet<>();
        addId(ids, originalAllocationId);
        addId(ids, clientFacingAllocationId);
        if (sourceAllocationIds != null) {
            ids.addAll(sourceAllocationIds);
        }
        replays.forEach(row -> {
            addId(ids, row.getOriginalAllocationId());
            addId(ids, row.getClientFacingAllocationId());
            allocationRepository.findLatestId(
                    row.getAccountingMode().name(),
                    ContractorAllocationSourceType.ACTUAL_PAYMENT.name(),
                    row.getId()
            ).ifPresent(ids::add);
        });
        return ids;
    }

    private Map<Long, ContractorPaymentProfile> lockProfiles(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> sorted = ids.stream().filter(Objects::nonNull).sorted().toList();
        List<ContractorPaymentProfile> rows = profileRepository.findAllByIdForUpdate(sorted);
        if (rows.size() != sorted.size()) {
            throw conflict("Платёжный профиль получателя не найден");
        }
        return rows.stream().collect(Collectors.toMap(ContractorPaymentProfile::getId, Function.identity()));
    }

    private Map<Long, ContractorPaymentAllocation> lockAllocations(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> sorted = ids.stream().filter(Objects::nonNull).sorted().toList();
        List<ContractorPaymentAllocation> rows = allocationRepository.findAllByIdForUpdate(sorted);
        if (rows.size() != sorted.size()) {
            throw conflict("Исходное распределение платежа не найдено");
        }
        return rows.stream().collect(Collectors.toMap(ContractorPaymentAllocation::getId, Function.identity()));
    }

    private Map<String, ContractorActualPaymentAttribution> lockReplayRows(
            List<ContractorActualPaymentRecipientCommand> commands
    ) {
        Map<String, ContractorActualPaymentAttribution> result = new LinkedHashMap<>();
        commands.stream().map(ContractorActualPaymentRecipientCommand::attributionKey).sorted()
                .forEach(key -> attributionRepository.findByAttributionKeyForUpdate(key)
                        .ifPresent(row -> result.put(key, row)));
        if (!result.isEmpty() && result.size() != commands.size()) {
            throw conflict("Параллельная фиксация получателя требует повторной сверки");
        }
        return result;
    }

    private void requireSameReplay(
            ContractorActualPaymentAttribution row,
            ContractorActualPaymentSource source,
            ContractorActualPaymentRecipientCommand command,
            Long originalAllocationId,
            Long clientFacingAllocationId
    ) {
        if (row == null) {
            throw conflict("Ключ фактического поступления уже использован с другими данными");
        }
        boolean originalMismatch = source.clientFacingRecipientType() != null
                && (row.getOriginalRecipientType() != source.clientFacingRecipientType()
                || !Objects.equals(row.getOriginalRecipientProfileId(), source.clientFacingRecipientProfileId())
                || !Objects.equals(normalize(row.getOriginalRecipientNameSnapshot()), normalize(source.clientFacingRecipientName())));
        if (row.getSourceKind() != source.sourceKind()
                || !Objects.equals(row.getSourceId(), source.sourceId())
                || !Objects.equals(row.getEvidenceId(), source.evidenceId())
                || !Objects.equals(row.getOrderId(), source.orderId())
                || !Objects.equals(row.getCommonInvoiceId(), source.commonInvoiceId())
                || !Objects.equals(row.getOriginalAllocationId(), originalAllocationId)
                || !Objects.equals(row.getClientFacingAllocationId(), clientFacingAllocationId)
                || originalMismatch
                || row.getActualRecipientType() != command.actualRecipientType()
                || !Objects.equals(row.getActualRecipientProfileId(), command.actualRecipientProfileId())
                || row.getActualCashDestinationKind() != command.cashDestinationKind()
                || !Objects.equals(row.getActualManualPaymentTaskId(), command.manualPaymentTaskId())
                || !Objects.equals(row.getActualManualPaymentTaskGeneration(), command.manualPaymentTaskGeneration())
                || row.getActualManualPaymentTaskTargetKind() != command.manualPaymentTaskTargetKind()
                || row.getAmountKopecks() != command.amountKopecks()
                || !Objects.equals(row.getEffectiveAt(), source.effectiveAt())
                || !Objects.equals(row.getReason(), source.reason())
                || !Objects.equals(row.getEvidenceReference(), source.evidenceReference())
                || !Objects.equals(normalize(row.getReceiptUrl()), normalize(source.receiptUrl()))) {
            throw conflict("Ключ фактического поступления уже использован с другими данными");
        }
        // Actor is intentionally excluded: an authorized retry/repair may be
        // performed by another operator; the first actor remains immutable.
    }

    private void requireOriginalBinding(
            ContractorActualPaymentSource source,
            ContractorAllocationMode mode,
            ContractorPaymentAllocation allocation,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        if (allocation == null) {
            return;
        }
        if (allocation.getMode() != mode
                || allocation.getSourceType() != allocationSourceType(source.sourceKind())
                || !Objects.equals(allocation.getSourceId(), source.sourceId())) {
            throw conflict("Исходное распределение не принадлежит этому платежному источнику");
        }
        if (source.sourceKind() == ContractorActualPaymentSourceKind.PAYMENT_LINK) {
            if (source.orderId() == null
                    || !Objects.equals(allocation.getOrderId(), source.orderId())
                    || allocation.getCommonInvoiceId() != null) {
                throw conflict("Исходное распределение не принадлежит этому заказу");
            }
        } else if (source.commonInvoiceId() == null
                || !Objects.equals(allocation.getCommonInvoiceId(), source.commonInvoiceId())) {
            throw conflict("Исходное распределение не принадлежит этому общему счёту");
        }
        requireAllocationRecipient(allocation, profiles);
    }

    private void requireOriginalBinding(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        if (allocation == null) {
            return;
        }
        if (allocation.getMode() != row.getAccountingMode()
                || allocation.getSourceType() != allocationSourceType(row.getSourceKind())
                || !Objects.equals(allocation.getSourceId(), row.getSourceId())) {
            throw conflict("Сохранённая атрибуция ссылается на чужое распределение");
        }
        if (row.getSourceKind() == ContractorActualPaymentSourceKind.PAYMENT_LINK) {
            if (row.getOrderId() == null
                    || !Objects.equals(allocation.getOrderId(), row.getOrderId())
                    || allocation.getCommonInvoiceId() != null) {
                throw conflict("Сохранённая атрибуция расходится с заказом");
            }
        } else if (row.getCommonInvoiceId() == null
                || !Objects.equals(allocation.getCommonInvoiceId(), row.getCommonInvoiceId())) {
            throw conflict("Сохранённая атрибуция расходится с общим счётом");
        }
        requireAllocationRecipient(allocation, profiles);
    }

    private void requireClientFacingBinding(
            ContractorActualPaymentSource source,
            ContractorPaymentAllocation allocation,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        if (source.clientFacingAllocationId() == null) {
            if (allocation != null) {
                throw conflict("Исходный клиентский маршрут расходится с источником");
            }
            return;
        }
        if (allocation == null) {
            throw conflict("Исходный клиентский маршрут не найден");
        }
        requireSourceAllocationBinding(source, allocation, profiles);
    }

    private void requireClientFacingBinding(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        if (row.getClientFacingAllocationId() == null) {
            if (allocation != null) {
                throw conflict("Сохранённый клиентский маршрут расходится с атрибуцией");
            }
            return;
        }
        if (allocation == null) {
            throw conflict("Сохранённый клиентский маршрут не найден");
        }
        requireSourceAllocationBinding(row, allocation, profiles);
    }

    private void requireSourceAllocationBinding(
            ContractorActualPaymentSource source,
            ContractorPaymentAllocation allocation,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        requireSourceAllocationBinding(
                source.sourceKind(),
                source.sourceId(),
                source.orderId(),
                source.commonInvoiceId(),
                allocation,
                profiles,
                "Распределение не принадлежит фактическому платёжному источнику"
        );
    }

    private void requireSourceAllocationBinding(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        requireSourceAllocationBinding(
                row.getSourceKind(),
                row.getSourceId(),
                row.getOrderId(),
                row.getCommonInvoiceId(),
                allocation,
                profiles,
                "Сохранённая атрибуция ссылается на чужое распределение"
        );
    }

    private void requireSourceAllocationBinding(
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId,
            Long orderId,
            Long commonInvoiceId,
            ContractorPaymentAllocation allocation,
            Map<Long, ContractorPaymentProfile> profiles,
            String message
    ) {
        if (allocation.getSourceType() != allocationSourceType(sourceKind)
                || !Objects.equals(allocation.getSourceId(), sourceId)) {
            throw conflict(message);
        }
        if (sourceKind == ContractorActualPaymentSourceKind.PAYMENT_LINK) {
            if (orderId == null
                    || !Objects.equals(allocation.getOrderId(), orderId)
                    || allocation.getCommonInvoiceId() != null) {
                throw conflict(message);
            }
        } else if (commonInvoiceId == null
                || !Objects.equals(allocation.getCommonInvoiceId(), commonInvoiceId)) {
            throw conflict(message);
        }
        requireAllocationRecipient(allocation, profiles);
    }
    private void requireAllocationRecipient(
            ContractorPaymentAllocation allocation,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        if (allocation.getRecipientType() == ContractorRecipientType.OWNER) {
            if (allocation.getRecipientProfile() != null) {
                throw conflict("Распределение владельца содержит профиль работника");
            }
            return;
        }
        ContractorPaymentProfile profile = allocation.getRecipientProfile() == null
                ? null : profiles.get(allocation.getRecipientProfile().getId());
        requireProfileRole(profile, allocation.getRecipientType());
    }

    private void requirePaymentLinkAllocationBinding(
            Order order,
            PaymentLink link,
            ContractorPaymentAllocation allocation,
            ContractorAllocationMode expectedMode,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        if (allocation == null) {
            return;
        }
        if ((expectedMode != null && allocation.getMode() != expectedMode)
                || allocation.getSourceType() != ContractorAllocationSourceType.PAYMENT_LINK
                || !Objects.equals(allocation.getSourceId(), link.getId())
                || !Objects.equals(allocation.getOrderId(), order.getId())
                || allocation.getCommonInvoiceId() != null) {
            throw conflict("Распределение не принадлежит выбранному платежу заказа");
        }
        requireAllocationRecipient(allocation, profiles);
    }

    private void requireNoPriorPaymentLinkConfirmation(
            ContractorActualPaymentSource source,
            ContractorPaymentAllocation original
    ) {
        if (source.sourceKind() == ContractorActualPaymentSourceKind.PAYMENT_LINK) {
            requireNoExistingNetConfirmation(original);
        }
    }

    private void requireNoExistingNetConfirmation(ContractorPaymentAllocation allocation) {
        if (allocation == null) {
            return;
        }
        long netConfirmed = Math.max(0L, allocation.getConfirmedKopecks() - allocation.getReturnedKopecks());
        if (netConfirmed > 0L) {
            throw conflict("По исходному распределению уже есть частичное поступление; требуется точная сверка остатка");
        }
    }

    private ClientRecipient clientRecipient(
            ContractorActualPaymentSource source,
            ContractorPaymentAllocation allocation,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        if (source.clientFacingCashDestinationKind() == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                && source.clientFacingManualPaymentTaskTargetKind()
                        == ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK) {
            return new ClientRecipient(
                    null,
                    null,
                    null,
                    firstNonBlank(source.clientFacingRecipientName(), "Внешний получатель задания")
            );
        }
        if (source.clientFacingRecipientType() != null) {
            if (source.clientFacingRecipientType() == ContractorRecipientType.OWNER) {
                return new ClientRecipient(
                        ContractorRecipientType.OWNER,
                        null,
                        null,
                        firstNonBlank(source.clientFacingRecipientName(), "Владелец")
                );
            }
            ContractorPaymentProfile profile = profiles.get(source.clientFacingRecipientProfileId());
            requireProfileRole(profile, source.clientFacingRecipientType());
            targetAccessPolicy.requireCanManageUser(userId(profile));
            return new ClientRecipient(
                    source.clientFacingRecipientType(),
                    source.clientFacingRecipientProfileId(),
                    userId(profile),
                    firstNonBlank(source.clientFacingRecipientName(), recipientName(profile, source.clientFacingRecipientType()))
            );
        }
        if (allocation == null || allocation.getRecipientProfile() == null) {
            return new ClientRecipient(ContractorRecipientType.OWNER, null, null, "Владелец");
        }
        ContractorPaymentProfile profile = profiles.get(allocation.getRecipientProfile().getId());
        requireProfileRole(profile, allocation.getRecipientType());
        targetAccessPolicy.requireCanManageUser(userId(profile));
        return new ClientRecipient(
                allocation.getRecipientType(),
                profile.getId(),
                userId(profile),
                firstNonBlank(allocation.getRecipientNameSnapshot(), recipientName(profile, allocation.getRecipientType()))
        );
    }

    private ContractorPaymentProfile actualProfile(
            ContractorActualPaymentRecipientCommand command,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        if (command.actualRecipientType() == null
                || command.actualRecipientType() == ContractorRecipientType.OWNER) {
            return null;
        }
        ContractorPaymentProfile profile = profiles.get(command.actualRecipientProfileId());
        requireProfileRole(profile, command.actualRecipientType());
        return profile;
    }

    private void requireProfileRole(ContractorPaymentProfile profile, ContractorRecipientType type) {
        ContractorRole role = type == ContractorRecipientType.SPECIALIST
                ? ContractorRole.SPECIALIST : ContractorRole.MANAGER;
        if (profile == null || profile.getRole() != role
                || profile.getUser() == null || profile.getUser().getId() == null) {
            throw conflict("Платёжный профиль не соответствует выбранному получателю");
        }
    }

    private long capacity(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            ContractorPaymentAllocation reusableOriginal,
            boolean frozenTaskTarget
    ) {
        long available = frozenTaskTarget
                ? Math.max(0L, profileService.capacityPosition(profile, mode))
                : taskCapacityService.ordinaryAvailable(profile, mode);
        if (reusableOriginal != null && Objects.equals(profileId(reusableOriginal), profile.getId())) {
            available = Math.addExact(available, outstanding(reusableOriginal));
        }
        return Math.max(0L, available);
    }

    private void releaseOriginal(ContractorActualPaymentSource source, ContractorPaymentAllocation original) {
        if (original == null || original.getRecipientProfile() == null || !OUTSTANDING.contains(original.getStatus())) {
            return;
        }
        boolean released = accountingService.recordRelease(
                original,
                ContractorAllocationStatus.CANCELED,
                source.effectiveAt(),
                "Фактический получатель ручной оплаты отличается от исходного маршрута",
                reallocationRef(source.sourceKind(), source.sourceId())
        );
        if (!released) {
            throw conflict("Не удалось атомарно освободить исходное распределение");
        }
        allocationRepository.saveAndFlush(original);
    }

    private void releaseHistorical(
            ContractorActualPaymentSource source,
            ContractorPaymentAllocation allocation
    ) {
        releaseHistorical(
                allocation,
                source.effectiveAt(),
                "Исторический маршрут закрыт при фиксации фактического получателя",
                historicalReallocationRef(source.sourceKind(), source.sourceId())
        );
    }

    private void releaseHistorical(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation
    ) {
        releaseHistorical(
                allocation,
                row.getEffectiveAt(),
                row.getReason(),
                historicalReallocationRef(row.getSourceKind(), row.getSourceId())
        );
    }

    private void releaseHistorical(
            ContractorPaymentAllocation allocation,
            LocalDateTime effectiveAt,
            String reason,
            String externalRef
    ) {
        if (allocation == null
                || allocation.getRecipientProfile() == null
                || !OUTSTANDING.contains(allocation.getStatus())) {
            return;
        }
        boolean released = accountingService.recordRelease(
                allocation,
                ContractorAllocationStatus.CANCELED,
                effectiveAt,
                reason,
                externalRef
        );
        if (!released) {
            throw conflict("Не удалось атомарно освободить историческое распределение");
        }
        allocationRepository.saveAndFlush(allocation);
    }
    private void applyNew(
            ContractorActualPaymentAttribution row,
            ContractorPaymentProfile actualProfile,
            ContractorPaymentAllocation original,
            boolean reuseOriginal,
            long available
    ) {
        if (actualProfile == null) {
            return; // OWNER never receives a contractor credit/event
        }
        if (reuseOriginal && original != null && Objects.equals(profileId(original), actualProfile.getId())) {
            bindActualTaskAllocation(original, row, actualProfile);
            confirm(original, row, !OUTSTANDING.contains(original.getStatus()));
            return;
        }
        confirmActualAllocation(row, actualProfile, available);
    }

    private void requireAccountingApplied(
            ContractorActualPaymentAttribution row,
            Map<Long, ContractorPaymentProfile> profiles,
            Map<Long, ContractorPaymentAllocation> allocations
    ) {
        List<ContractorPaymentAllocation> sourceAllocations = sourceAllocations(row, allocations.values());
        sourceAllocations.forEach(allocation -> {
            requireSourceAllocationBinding(row, allocation, profiles);
            if (!SOURCE_FINAL.contains(allocation.getStatus())
                    || allocation.isNeedsReturnAmount()
                    || !normalize(allocation.getReconcileClaimToken()).isBlank()
                    || allocation.getLastReconciledAt() == null) {
                throw conflict("Атрибуция сохранена без завершённого учёта источника; требуется ручная сверка");
            }
        });

        ContractorPaymentAllocation original = row.getOriginalAllocationId() == null
                ? null : allocations.get(row.getOriginalAllocationId());
        ContractorPaymentAllocation actualAllocation = allocations.values().stream()
                .filter(allocation -> allocation.getMode() == row.getAccountingMode())
                .filter(allocation -> allocation.getSourceType() == ContractorAllocationSourceType.ACTUAL_PAYMENT)
                .filter(allocation -> Objects.equals(allocation.getSourceId(), row.getId()))
                .findFirst()
                .orElse(null);

        if (row.getActualRecipientProfileId() == null) {
            if (actualAllocation != null) {
                throw conflict("Для оплаты владельцу обнаружено лишнее распределение работника");
            }
            return;
        }

        ContractorPaymentProfile actualProfile = profiles.get(row.getActualRecipientProfileId());
        requireProfileRole(actualProfile, row.getActualRecipientType());
        boolean confirmedOnOriginal = original != null
                && Objects.equals(profileId(original), actualProfile.getId())
                && eventRepository.existsByAllocationIdAndExternalRef(
                        original.getId(), confirmationRef(row.getId())
                );
        if (confirmedOnOriginal) {
            requireActualTaskAllocationBinding(original, row, actualProfile);
            return;
        }
        if (actualAllocation == null
                || actualAllocation.getMode() != row.getAccountingMode()
                || !Objects.equals(profileId(actualAllocation), actualProfile.getId())
                || !Objects.equals(actualAllocation.getManualPaymentTaskId(),
                        expectedActualTaskAllocationId(row, actualProfile))
                || actualAllocation.getAmountKopecks() != row.getAmountKopecks()
                || !SOURCE_FINAL.contains(actualAllocation.getStatus())
                || !eventRepository.existsByAllocationIdAndExternalRef(
                        actualAllocation.getId(), confirmationRef(row.getId())
                )) {
            throw conflict("Атрибуция сохранена без подтверждения фактического получателя; требуется ручная сверка");
        }
    }

    private void confirm(
            ContractorPaymentAllocation allocation,
            ContractorActualPaymentAttribution row,
            boolean late
    ) {
        long target = Math.addExact(allocation.getConfirmedKopecks(), row.getAmountKopecks());
        boolean changed = accountingService.recordConfirmation(
                allocation,
                target,
                row.getEffectiveAt(),
                row.getReason(),
                confirmationRef(row.getId()),
                row.getAccountingMode() == ContractorAllocationMode.SHADOW,
                late
        );
        if (!changed && !eventRepository.existsByAllocationIdAndExternalRef(
                allocation.getId(), confirmationRef(row.getId())
        )) {
            throw conflict("Не удалось зафиксировать фактическое поступление");
        }
        allocationRepository.saveAndFlush(allocation);
    }

    private void confirmActualAllocation(
            ContractorActualPaymentAttribution row,
            ContractorPaymentProfile profile,
            long available
    ) {
        ContractorPaymentAllocation allocation = allocationRepository
                .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                        row.getAccountingMode(), ContractorAllocationSourceType.ACTUAL_PAYMENT, row.getId()
                ).orElse(null);
        if (allocation == null) {
            allocation = new ContractorPaymentAllocation();
            allocation.setMode(row.getAccountingMode());
            allocation.setSourceType(ContractorAllocationSourceType.ACTUAL_PAYMENT);
            allocation.setSourceId(row.getId());
            allocation.setAttemptNo(1);
            allocation.setOrderId(row.getOrderId());
            allocation.setCommonInvoiceId(row.getCommonInvoiceId());
            allocation.setManualPaymentTaskId(expectedActualTaskAllocationId(row, profile));
            allocation.setRecipientType(row.getActualRecipientType());
            allocation.setRecipientProfile(profile);
            allocation.setRecipientUserId(profile.getUser().getId());
            allocation.setCurrentWorkerId(row.getCurrentWorkerId());
            allocation.setCurrentManagerId(row.getCurrentManagerId());
            allocation.setRecipientNameSnapshot(row.getActualRecipientNameSnapshot());
            allocation.setPaymentPhoneSnapshot(ContractorPaymentTransferNumber.normalize(profile.getPaymentPhone()));
            allocation.setBankNameSnapshot(profile.getBankName());
            allocation.setPaymentCommentSnapshot(profile.getPaymentComment());
            allocation.setAmountKopecks(row.getAmountKopecks());
            allocation.setAvailableBeforeKopecks(available);
            allocation.setStatus(ContractorAllocationStatus.RESERVED);
            allocation.setReservedAt(row.getEffectiveAt());
            allocation = allocationRepository.saveAndFlush(allocation);
            accountingService.recordReservation(allocation);
        } else if (!Objects.equals(profileId(allocation), profile.getId())
                || allocation.getAmountKopecks() != row.getAmountKopecks()
                || allocation.getMode() != row.getAccountingMode()
                || !Objects.equals(allocation.getManualPaymentTaskId(),
                        expectedActualTaskAllocationId(row, profile))) {
            throw conflict("Учёт фактического получателя расходится с атрибуцией");
        }
        confirm(allocation, row, false);
    }

    private void bindActualTaskAllocation(
            ContractorPaymentAllocation allocation,
            ContractorActualPaymentAttribution row,
            ContractorPaymentProfile profile
    ) {
        Long expectedTaskId = expectedActualTaskAllocationId(row, profile);
        if (allocation.getManualPaymentTaskId() != null
                && !Objects.equals(allocation.getManualPaymentTaskId(), expectedTaskId)) {
            throw conflict("Распределение уже связано с другим платёжным заданием");
        }
        allocation.setManualPaymentTaskId(expectedTaskId);
    }

    private void requireActualTaskAllocationBinding(
            ContractorPaymentAllocation allocation,
            ContractorActualPaymentAttribution row,
            ContractorPaymentProfile profile
    ) {
        if (!Objects.equals(allocation.getManualPaymentTaskId(),
                expectedActualTaskAllocationId(row, profile))) {
            throw conflict("Подтверждённое распределение не связано с денежной целью задания");
        }
    }

    private Long expectedActualTaskAllocationId(
            ContractorActualPaymentAttribution row,
            ContractorPaymentProfile profile
    ) {
        if (row.getActualCashDestinationKind()
                != ContractorCashDestinationKind.MANUAL_PAYMENT_TASK) {
            return null;
        }
        ManualPaymentTaskAccountingTargetKind kind = row.getActualManualPaymentTaskTargetKind();
        boolean contractorTask = kind == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                || kind == ManualPaymentTaskAccountingTargetKind.MANAGER;
        ContractorRecipientType expectedType = kind == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                ? ContractorRecipientType.SPECIALIST : ContractorRecipientType.MANAGER;
        if (!contractorTask
                || row.getActualManualPaymentTaskId() == null
                || row.getActualManualPaymentTaskId() <= 0L
                || row.getActualRecipientType() != expectedType
                || profile == null
                || !Objects.equals(profile.getId(), row.getActualRecipientProfileId())) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        return row.getActualManualPaymentTaskId();
    }

    private Set<Long> ordinaryCandidateProfileIds(
            Order order,
            PaymentLink link,
            Long accountingAllocationId,
            Long clientFacingAllocationId
    ) {
        Set<Long> ids = new LinkedHashSet<>();
        Worker worker = order.getWorker();
        if (worker != null && worker.getUser() != null) {
            profileRepository.findIdByUserIdAndRole(worker.getUser().getId(), ContractorRole.SPECIALIST)
                    .ifPresent(ids::add);
        }
        Manager manager = orderManagerResolver.resolveForRouting(order);
        if (manager != null && manager.getUser() != null) {
            profileRepository.findIdByUserIdAndRole(manager.getUser().getId(), ContractorRole.MANAGER)
                    .ifPresent(ids::add);
        }
        addId(ids, link.getManualActualRecipientProfileId());
        addId(ids, link.getManualActualOriginalRecipientProfileId());
        if (accountingAllocationId != null) {
            allocationRepository.findRecipientProfileIdById(accountingAllocationId).ifPresent(ids::add);
        }
        if (clientFacingAllocationId != null) {
            allocationRepository.findRecipientProfileIdById(clientFacingAllocationId).ifPresent(ids::add);
        }
        return ids;
    }

    private ClientRecipient frozenClientRecipient(
            PaymentLink link,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        if (link.getManualActualRecipientFrozenAt() == null
                || link.getManualActualOriginalRecipientType() == null) {
            return null;
        }
        Long originalProfileId = link.getManualActualOriginalRecipientProfileId();
        ContractorPaymentProfile profile = originalProfileId == null
                ? null : profiles.get(originalProfileId);
        if (link.getManualActualOriginalRecipientType() != ContractorRecipientType.OWNER) {
            requireProfileRole(profile, link.getManualActualOriginalRecipientType());
        }
        return new ClientRecipient(
                link.getManualActualOriginalRecipientType(),
                link.getManualActualOriginalRecipientProfileId(),
                link.getManualActualOriginalRecipientUserId(),
                firstNonBlank(link.getManualActualOriginalRecipientNameSnapshot(),
                        recipientName(profile, link.getManualActualOriginalRecipientType()))
        );
    }

    private ClientRecipient clientRecipientForPaymentLink(
            PaymentLink link,
            ContractorPaymentAllocation original,
            Map<Long, ContractorPaymentProfile> profiles
    ) {
        if (link.getManualSource() != ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            return new ClientRecipient(ContractorRecipientType.OWNER, null, null, "Владелец");
        }
        if (original == null || original.getRecipientProfile() == null) {
            throw conflict("У ссылки с реквизитами работника отсутствует исходное распределение");
        }
        ContractorPaymentProfile profile = profiles.get(original.getRecipientProfile().getId());
        requireProfileRole(profile, original.getRecipientType());
        return new ClientRecipient(
                original.getRecipientType(),
                profile.getId(),
                userId(profile),
                firstNonBlank(original.getRecipientNameSnapshot(), recipientName(profile, original.getRecipientType()))
        );
    }

    private void addAssignedCandidates(
            Map<String, ManualCardPaymentRecipientResponse> target,
            Order order,
            Map<Long, ContractorPaymentProfile> profiles,
            ContractorAllocationMode mode,
            ContractorPaymentAllocation original,
            long amount,
            ClientRecipient client
    ) {
        Worker worker = order.getWorker();
        if (worker != null && worker.getUser() != null) {
            profileRepository.findIdByUserIdAndRole(worker.getUser().getId(), ContractorRole.SPECIALIST)
                    .ifPresent(id -> addProfileCandidate(target, id, profiles, mode, original, amount, client));
        }
        Manager manager = orderManagerResolver.resolveForRouting(order);
        if (manager != null && manager.getUser() != null) {
            profileRepository.findIdByUserIdAndRole(manager.getUser().getId(), ContractorRole.MANAGER)
                    .ifPresent(id -> addProfileCandidate(target, id, profiles, mode, original, amount, client));
        }
    }

    private void addProfileCandidate(
            Map<String, ManualCardPaymentRecipientResponse> target,
            Long profileId,
            Map<Long, ContractorPaymentProfile> profiles,
            ContractorAllocationMode mode,
            ContractorPaymentAllocation original,
            long amount,
            ClientRecipient client
    ) {
        if (profileId == null) {
            return;
        }
        ContractorPaymentProfile profile = profiles.get(profileId);
        if (profile == null) {
            return;
        }
        ContractorRecipientType type = recipientType(profile.getRole());
        addCandidate(
                target,
                new ClientRecipient(type, profile.getId(), userId(profile), recipientName(profile, type)),
                profiles,
                mode,
                original,
                amount,
                sameRecipient(client, type, profile.getId())
        );
    }

    private void addCandidate(
            Map<String, ManualCardPaymentRecipientResponse> target,
            ClientRecipient candidate,
            Map<Long, ContractorPaymentProfile> profiles,
            ContractorAllocationMode mode,
            ContractorPaymentAllocation original,
            long amount,
            boolean isOriginal
    ) {
        if (!targetAccessPolicy.canManageUser(candidate.userId())) {
            return;
        }
        ContractorPaymentProfile profile = candidate.profileId() == null
                ? null : profiles.get(candidate.profileId());
        long available = profile == null ? 0L : capacity(profile, mode, original, false);
        String accountingRecipientName = profile == null
                ? candidate.name()
                : recipientName(profile, candidate.type());
        String bankRecipientName = profile == null
                ? ""
                : isOriginal
                        ? firstNonBlank(candidate.name(), profile.getRecipientName())
                        : normalize(profile.getRecipientName());
        ManualCardPaymentRecipientResponse response = new ManualCardPaymentRecipientResponse(
                candidate.type(),
                candidate.profileId(),
                candidate.userId(),
                accountingRecipientName,
                available,
                profile == null ? 0L : Math.max(0L, amount - available),
                isOriginal,
                bankRecipientName
        );
        String key = recipientKey(candidate.type(), candidate.profileId());
        // The accounting identity and the bank card holder are deliberately
        // separate. Several contractors may use the same bank requisites, but
        // reservations, confirmations and notifications remain keyed by the
        // contractor profile/user rather than by the card holder name.
        ManualCardPaymentRecipientResponse existing = target.get(key);
        if (existing == null || (isOriginal && !existing.original())) {
            target.put(key, response);
        }
    }

    private void addTaskCandidate(
            Map<String, ManualCardPaymentRecipientResponse> target,
            ManualPaymentTaskRouteSnapshot snapshot,
            Map<Long, ContractorPaymentProfile> profiles,
            ContractorAllocationMode mode,
            ContractorPaymentAllocation original,
            long amount,
            boolean isOriginal
    ) {
        ManualPaymentTaskReceiptIntegrationService.Destination destination =
                taskReceiptIntegrationService.destination(snapshot);
        ContractorPaymentProfile profile = destination.recipientProfileId() == null
                ? null : profiles.get(destination.recipientProfileId());
        boolean contractorTarget = snapshot.accountingTargetKind()
                == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                || snapshot.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.MANAGER;
        if (contractorTarget) {
            ContractorRole expectedRole = snapshot.accountingTargetKind()
                    == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                    ? ContractorRole.SPECIALIST : ContractorRole.MANAGER;
            if (profile == null || profile.getRole() != expectedRole || profile.getUser() == null
                    || profile.getUser().getId() == null
                    || !targetAccessPolicy.canManageUser(profile.getUser().getId())) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
        } else if (destination.recipientProfileId() != null) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        Long userId = userId(profile);
        long available = profile == null ? 0L : capacity(profile, mode, original, true);
        String display = destination.bankRecipientName().isBlank()
                ? destination.accountingTargetLabel() : destination.bankRecipientName();
        target.put(snapshot.candidateKey(), new ManualCardPaymentRecipientResponse(
                destination.recipientType(), destination.recipientProfileId(), userId, display,
                available, profile == null ? 0L : Math.max(0L, amount - available), isOriginal,
                snapshot.candidateKey(), ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                snapshot.taskId(), snapshot.taskGeneration(), snapshot.accountingTargetKind(),
                snapshot.bankRecipientName(), snapshot.accountingTargetLabel(),
                "Сумма будет зачтена в платёжное задание",
                snapshot.bankRecipientName()
        ));
    }

    private void suppressOrdinaryAliasForTask(
            Map<String, ManualCardPaymentRecipientResponse> candidates,
            ManualPaymentTaskRouteSnapshot task
    ) {
        if (task == null || task.accountingTargetKind() == null) {
            return;
        }
        switch (task.accountingTargetKind()) {
            case OWNER -> candidates.remove(recipientKey(ContractorRecipientType.OWNER, null));
            case SPECIALIST -> candidates.remove(recipientKey(
                    ContractorRecipientType.SPECIALIST, task.accountingTargetProfileId()));
            case MANAGER -> candidates.remove(recipientKey(
                    ContractorRecipientType.MANAGER, task.accountingTargetProfileId()));
            case EXTERNAL_TASK, UNRESOLVED -> {
                // EXTERNAL_TASK has no ordinary accounting alias. UNRESOLVED
                // is rejected by addTaskCandidate before this point.
            }
        }
    }

    private ManualCardPaymentRecipientResponse selectRecipient(
            ManualCardPaymentContextResponse context,
            String requestedRecipientKey,
            ContractorRecipientType requestedType,
            Long requestedProfileId
    ) {
        String key = normalize(requestedRecipientKey);
        if (!key.isBlank()) {
            return context.candidates().stream().filter(value -> value.key().equals(key)).findFirst()
                    .orElseThrow(ManualPaymentTaskRouteErrors::stale);
        }
        if (context.originalRecipient().cashDestinationKind()
                == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK) {
            throw ManualPaymentTaskRouteErrors.actualRecipientRequired();
        }
        if (requestedType == null && requestedProfileId == null) {
            return context.originalRecipient();
        }
        validateIdentity(requestedType, requestedProfileId, false);
        return context.candidates().stream()
                .filter(value -> value.recipientType() == requestedType)
                .filter(value -> Objects.equals(value.recipientProfileId(), requestedProfileId))
                .findFirst()
                .orElseThrow(() -> conflict("Выбранный получатель не относится к текущему заказу"));
    }

    private String frozenRecipientKey(PaymentLink link) {
        if (link.getManualActualCashDestinationKind() == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK) {
            return com.hunt.otziv.payments.service.ManualPaymentTaskLedgerService.candidateKey(
                    link.getManualActualTaskId(), link.getManualActualTaskGeneration());
        }
        return recipientKey(link.getManualActualRecipientType(), link.getManualActualRecipientProfileId());
    }

    private ContractorActualPaymentSource paymentLinkSource(
            Order order,
            PaymentLink link,
            Long originalAllocationId
    ) {
        return new ContractorActualPaymentSource(
                ContractorActualPaymentSourceKind.PAYMENT_LINK,
                link.getId(),
                null,
                order.getId(),
                null,
                originalAllocationId,
                null,
                null,
                null,
                order.getWorker() == null ? null : order.getWorker().getId(),
                order.getManager() == null ? null : order.getManager().getId(),
                LocalDateTime.now(),
                "preview",
                "preview",
                null,
                "system"
        );
    }

    private ManualCardPaymentContextResponse legacyManualCardPaymentContext(Order order, PaymentLink link) {
        ManualCardPaymentRecipientResponse owner = new ManualCardPaymentRecipientResponse(
                ContractorRecipientType.OWNER,
                null,
                null,
                "Владелец",
                0L,
                0L,
                true
        );
        return new ManualCardPaymentContextResponse(
                order.getId(),
                link.getAmountKopecks(),
                owner,
                List.of(owner),
                "Распределённый учёт выключен; ручная оплата будет сохранена по прежнему порядку",
                false,
                null,
                null,
                null
        );
    }

    private void requireOrdinarySource(Order order, PaymentLink link) {
        if (order == null || order.getId() == null || link == null || link.getId() == null
                || link.getOrder() == null || !Objects.equals(link.getOrder().getId(), order.getId())
                || link.getAmountKopecks() <= 0) {
            throw conflict("Платёжный источник заказа изменился");
        }
    }

    private void requireFrozenIntent(PaymentLink link) {
        if (link.getManualActualRecipientFrozenAt() == null
                || link.getManualActualAccountingMode() == null
                || normalize(link.getManualActualReason()).isBlank()
                || normalize(link.getManualActualActor()).isBlank()) {
            throw conflict("Получатель оплаты не был надёжно зафиксирован до сверки банка");
        }
        validateDestination(
                link.getManualActualOriginalCashDestinationKind(),
                link.getManualActualOriginalRecipientType(),
                link.getManualActualOriginalRecipientProfileId(),
                link.getManualActualOriginalTaskId(),
                link.getManualActualOriginalTaskGeneration(),
                link.getManualActualOriginalTaskTargetKind(),
                false
        );
        validateDestination(
                link.getManualActualCashDestinationKind(),
                link.getManualActualRecipientType(),
                link.getManualActualRecipientProfileId(),
                link.getManualActualTaskId(),
                link.getManualActualTaskGeneration(),
                link.getManualActualTaskTargetKind(),
                false
        );
    }

    private void requireFrozenReplay(
            PaymentLink link,
            String requestedRecipientKey,
            ContractorRecipientType requestedType,
            Long requestedProfileId,
            String reason,
            String receiptUrl
    ) {
        String recipientKey = normalize(requestedRecipientKey);
        if (!recipientKey.isBlank() && !frozenRecipientKey(link).equals(recipientKey)) {
            throw conflict("Параметры ручной оплаты уже зафиксированы и не могут быть изменены");
        }
        if (recipientKey.isBlank() && requestedType == null && requestedProfileId == null) {
            throw conflict("Повтор операции должен явно указать ранее выбранного получателя");
        }
        validateDestination(
                link.getManualActualCashDestinationKind(),
                requestedType,
                requestedProfileId,
                link.getManualActualTaskId(),
                link.getManualActualTaskGeneration(),
                link.getManualActualTaskTargetKind(),
                false
        );
        if (requestedType != link.getManualActualRecipientType()
                || !Objects.equals(requestedProfileId, link.getManualActualRecipientProfileId())
                || !Objects.equals(reason, normalize(link.getManualActualReason()))
                || !Objects.equals(normalize(receiptUrl), normalize(link.getManualActualReceiptUrl()))) {
            throw conflict("Параметры ручной оплаты уже зафиксированы и не могут быть изменены");
        }
    }

    private void recordBusinessAudit(
            ContractorActualPaymentSource source,
            List<ContractorActualPaymentAttribution> rows
    ) {
        List<Map<String, Object>> safeRows = rows.stream().map(row -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("attributionId", row.getId());
            if (row.getActualRecipientType() != null) {
                value.put("recipientType", row.getActualRecipientType().name());
            }
            if (row.getActualRecipientProfileId() != null) {
                value.put("recipientProfileId", row.getActualRecipientProfileId());
            }
            if (row.getActualCashDestinationKind() != null) {
                value.put("cashDestinationKind", row.getActualCashDestinationKind().name());
            }
            if (row.getActualCashDestinationKind() == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK) {
                value.put("manualPaymentTaskId", row.getActualManualPaymentTaskId());
                value.put("manualPaymentTaskGeneration", row.getActualManualPaymentTaskGeneration());
                if (row.getActualManualPaymentTaskTargetKind() != null) {
                    value.put("manualPaymentTaskTargetKind", row.getActualManualPaymentTaskTargetKind().name());
                }
            }
            value.put("amountKopecks", row.getAmountKopecks());
            value.put("projectedOverrunKopecks", row.getProjectedOverrunKopecks());
            return value;
        }).toList();
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("sourceKind", source.sourceKind().name());
        newValue.put("sourceId", source.sourceId());
        newValue.put("rows", safeRows);
        businessAuditService.recordRequiredInCurrentTransaction(
                "MANUAL_PAYMENT_ACTUAL_RECIPIENT_RECORDED",
                "ACTUAL_PAYMENT_ATTRIBUTION",
                source.sourceId(),
                source.orderId(),
                null,
                null,
                newValue,
                "sourceKind=" + source.sourceKind().name()
                        + ", sourceId=" + source.sourceId()
                        + ", attributionCount=" + rows.size()
        );
    }

    private List<ContractorPaymentAllocation> sourceAllocations(
            ContractorActualPaymentSource source,
            Collection<ContractorPaymentAllocation> allocations
    ) {
        return sourceAllocations(
                source.sourceKind(), source.sourceId(), source.orderId(), source.commonInvoiceId(), allocations
        );
    }

    private List<ContractorPaymentAllocation> sourceAllocations(
            ContractorActualPaymentAttribution row,
            Collection<ContractorPaymentAllocation> allocations
    ) {
        return sourceAllocations(
                row.getSourceKind(), row.getSourceId(), row.getOrderId(), row.getCommonInvoiceId(), allocations
        );
    }

    private List<ContractorPaymentAllocation> sourceAllocations(
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId,
            Long orderId,
            Long commonInvoiceId,
            Collection<ContractorPaymentAllocation> allocations
    ) {
        if (allocations == null || allocations.isEmpty()) {
            return List.of();
        }
        ContractorAllocationSourceType sourceType = allocationSourceType(sourceKind);
        return allocations.stream()
                .filter(Objects::nonNull)
                .filter(allocation -> allocation.getSourceType() == sourceType)
                .filter(allocation -> Objects.equals(allocation.getSourceId(), sourceId))
                // Do not filter corrupt order/common bindings out of the locked set:
                // requireSourceAllocationBinding must see them and fail closed.
                .sorted(java.util.Comparator.comparing(ContractorPaymentAllocation::getId))
                .toList();
    }

    private void markSourceAllocationsReconciled(Collection<ContractorPaymentAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            return;
        }
        LocalDateTime reconciledAt = LocalDateTime.now();
        for (ContractorPaymentAllocation allocation : allocations.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(java.util.Comparator.comparing(ContractorPaymentAllocation::getId))
                .toList()) {
            if (allocation.isNeedsReturnAmount() || !normalize(allocation.getReconcileClaimToken()).isBlank()) {
                throw conflict("Учёт распределения занят возвратом или сверкой; повторите операцию позже");
            }
            allocation.setLastReconciledAt(reconciledAt);
            allocation.setReconcileLeaseUntil(null);
            allocation.setReconcileNextRetryAt(null);
            allocation.setReconcileLastErrorCode(null);
            allocationRepository.saveAndFlush(allocation);
        }
    }
    private long outstanding(ContractorPaymentAllocation allocation) {
        if (allocation == null || !OUTSTANDING.contains(allocation.getStatus())) {
            return 0L;
        }
        long net = Math.max(0L, allocation.getConfirmedKopecks() - allocation.getReturnedKopecks());
        return Math.max(0L, allocation.getAmountKopecks() - net);
    }

    private Long profileId(ContractorPaymentAllocation allocation) {
        return allocation == null || allocation.getRecipientProfile() == null
                ? null : allocation.getRecipientProfile().getId();
    }

    private Long userId(ContractorPaymentProfile profile) {
        return profile == null || profile.getUser() == null ? null : profile.getUser().getId();
    }

    private String recipientName(ContractorPaymentProfile profile, ContractorRecipientType type) {
        if (type == ContractorRecipientType.OWNER) {
            return "Владелец";
        }
        if (profile == null) {
            return type == ContractorRecipientType.SPECIALIST ? "Специалист" : "Менеджер";
        }
        User user = profile.getUser();
        return firstNonBlank(
                user == null ? null : user.getFio(),
                user == null ? null : user.getUsername(),
                profile.getRecipientName(),
                type == ContractorRecipientType.SPECIALIST ? "Специалист" : "Менеджер"
        );
    }

    private ContractorRecipientType recipientType(ContractorRole role) {
        return role == ContractorRole.SPECIALIST
                ? ContractorRecipientType.SPECIALIST : ContractorRecipientType.MANAGER;
    }

    private boolean sameRecipient(ClientRecipient recipient, ContractorRecipientType type, Long profileId) {
        return recipient.type() == type && Objects.equals(recipient.profileId(), profileId);
    }

    private String recipientKey(ContractorRecipientType type, Long profileId) {
        return type.name() + ":" + (profileId == null ? "OWNER" : profileId);
    }

    private String confirmationRef(Long id) {
        return "ACTUAL_PAYMENT:CONFIRM:" + id;
    }

    private String reallocationRef(ContractorActualPaymentSourceKind kind, Long sourceId) {
        return "ACTUAL_PAYMENT:REALLOCATE:" + kind.name() + ":" + sourceId;
    }

    private String historicalReallocationRef(ContractorActualPaymentSourceKind kind, Long sourceId) {
        return "ACTUAL_PAYMENT:HISTORICAL_REALLOCATE:" + kind.name() + ":" + sourceId;
    }

    private void addId(Collection<Long> ids, Long id) {
        if (id != null && id > 0) {
            ids.add(id);
        }
    }

    private String required(String value, int max, String label) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw badRequest(label + " не заполнена");
        }
        if (normalized.length() > max) {
            throw badRequest(label + " не должна превышать " + max + " символов");
        }
        return normalized;
    }

    private String optional(String value, int max, String label) {
        String normalized = normalize(value);
        if (normalized.length() > max) {
            throw badRequest(label + " не должна превышать " + max + " символов");
        }
        return normalized.isBlank() ? null : normalized;
    }

    private String optionalReceiptUrl(String value) {
        String normalized = optional(value, MAX_RECEIPT_URL_LENGTH, "Ссылка на чек");
        if (normalized == null) {
            return null;
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = normalize(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || normalize(uri.getHost()).isBlank()
                    || uri.getUserInfo() != null) {
                throw badRequest("Ссылка на чек должна быть безопасной ссылкой http/https");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw badRequest("Ссылка на чек имеет неверный формат");
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record ClientRecipient(
            ContractorRecipientType type,
            Long profileId,
            Long userId,
            String name
    ) {
    }
}
