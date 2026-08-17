package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionResponse;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRowRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentOptionsResponse;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentRecipientCandidateResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.contractor_payments.dto.ContractorActualPaymentRecipientCommand;
import com.hunt.otziv.contractor_payments.dto.ContractorActualPaymentSource;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorOrderManagerResolver;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentAccountingPhaseService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import com.hunt.otziv.payments.service.ManualPaymentTaskRouteErrors;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.security.Principal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Common-invoice boundary for immutable actual-recipient facts. Candidate
 * discovery and validation live here so neither the web nor mobile client can
 * submit an arbitrary contractor profile id.
 */
@Service
@RequiredArgsConstructor
public class CommonManualPaymentAttributionCoordinator {

    private static final String OWNER_KEY = "OWNER";
    private static final String KEY_PREFIX = "COMMON_INVOICE:";
    private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9._-]{1,48}");
    private static final Set<ContractorAllocationStatus> OUTSTANDING = EnumSet.of(
            ContractorAllocationStatus.RESERVED,
            ContractorAllocationStatus.CLIENT_REPORTED,
            ContractorAllocationStatus.PARTIALLY_CONFIRMED
    );

    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorActualPaymentAttributionRepository attributionRepository;
    private final ContractorActualPaymentAttributionService attributionService;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final ManualPaymentTaskContractorCapacityService taskCapacityService;
    private final ContractorOrderManagerResolver orderManagerResolver;
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    @Transactional(readOnly = true)
    public CommonManualPaymentOptionsResponse options(
            CommonInvoice invoice,
            List<CommonInvoiceOrder> items,
            long remainingKopecks
    ) {
        requireInvoice(invoice);
        ContractorAllocationMode mode = accountingMode(invoice);
        PreparedContext context = context(invoice, activeItems(items), mode);
        List<CommonManualPaymentRecipientCandidateResponse> candidates = context.candidates().values().stream()
                .map(CandidateState::response)
                .toList();
        if (candidates.stream().noneMatch(candidate -> candidate.key().equals(context.defaultRecipientKey()))) {
            throw conflict("Исходный получатель общего счета недоступен; нужна ручная сверка");
        }
        return new CommonManualPaymentOptionsResponse(
                invoice.getId(),
                Math.max(0L, remainingKopecks),
                context.defaultRecipientKey(),
                candidates,
                history(invoice.getId()),
                "TASK_RECIPIENT_V1",
                normalize(invoice.getPaymentRouteManualTaskSourceGeneration())
        );
    }

    @Transactional(readOnly = true)
    public boolean hasRecordedAttribution(Long invoiceId) {
        return invoiceId != null && attributionRepository
                .existsBySourceKindAndSourceIdAndEvidenceId(
                        ContractorActualPaymentSourceKind.COMMON_INVOICE,
                        invoiceId,
                        null
                );
    }

    /**
     * Recognizes a network retry after the outer common-invoice transaction
     * has already committed. No new key is allowed for a terminal invoice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean replayIfRecorded(
            CommonInvoice invoice,
            List<CommonInvoiceOrder> items,
            CommonManualPaymentAttributionRequest request,
            Principal principal
    ) {
        requireInvoice(invoice);
        validateRequest(request);
        Set<String> rowKeys = new HashSet<>();
        Set<String> recipients = new HashSet<>();
        Map<String, ContractorActualPaymentAttribution> recorded = new LinkedHashMap<>();
        for (CommonManualPaymentAttributionRowRequest row : request.attributions()) {
            String rowKey = cleanKey(row == null ? null : row.rowKey(), "ключ строки");
            if (!rowKeys.add(rowKey)) {
                throw badRequest("Ключи строк распределения не должны повторяться");
            }
            validateRecipientShape(row);
            if (!recipients.add(requestedRecipientKey(row))) {
                throw badRequest("Один получатель не должен повторяться в нескольких строках");
            }
            String key = attributionKey(invoice.getId(), request.idempotencyKey(), rowKey);
            recorded.put(key, null);
        }
        String evidenceReference = evidenceReference(invoice.getId(), request.idempotencyKey());
        List<ContractorActualPaymentAttribution> recordedBatch = attributionRepository
                .findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                        ContractorActualPaymentSourceKind.COMMON_INVOICE,
                        invoice.getId()
                ).stream()
                .filter(value -> Objects.equals(value.getEvidenceReference(), evidenceReference))
                .toList();
        if (recordedBatch.isEmpty()) {
            return false;
        }
        Map<String, ContractorActualPaymentAttribution> recordedBatchByKey = new LinkedHashMap<>();
        for (ContractorActualPaymentAttribution value : recordedBatch) {
            if (recordedBatchByKey.put(value.getAttributionKey(), value) != null) {
                throw conflict("Записанная разбивка платежа повреждена и требует сверки");
            }
        }
        if (recordedBatchByKey.size() != request.attributions().size()
                || !recordedBatchByKey.keySet().equals(recorded.keySet())) {
            throw conflict("Ключ повтора частично использован; платеж не изменен и требует сверки");
        }
        recorded.clear();
        recorded.putAll(recordedBatchByKey);
        String reason = normalize(request.reason());
        String receiptUrl = normalize(request.receiptUrl());
        for (CommonManualPaymentAttributionRowRequest row : request.attributions()) {
            String rowKey = cleanKey(row.rowKey(), "ключ строки");
            String key = attributionKey(invoice.getId(), request.idempotencyKey(), rowKey);
            ContractorActualPaymentAttribution existing = recorded.get(key);
            if (existing == null
                    || existing.getSourceKind() != ContractorActualPaymentSourceKind.COMMON_INVOICE
                    || !Objects.equals(existing.getSourceId(), invoice.getId())
                    || !Objects.equals(existing.getCommonInvoiceId(), invoice.getId())
                    || !Objects.equals(recordedRecipientKey(existing), requestedRecipientKey(row))
                    || existing.getActualRecipientType() != row.recipientType()
                    || !Objects.equals(existing.getActualRecipientProfileId(), row.recipientProfileId())
                    || existing.getAmountKopecks() != row.amountKopecks()
                    || !Objects.equals(existing.getEffectiveAt(), request.effectiveAt())
                    || !Objects.equals(normalize(existing.getReason()), reason)
                    || !Objects.equals(normalize(existing.getReceiptUrl()), receiptUrl)
                    || !Objects.equals(existing.getEvidenceReference(), evidenceReference)) {
                throw conflict("Ключ фактического поступления уже использован с другими данными");
            }
        }
        ContractorAllocationMode mode = accountingMode(invoice);
        settleTaskReceipt(invoice, request, recordedBatch);
        if (isFrozenTaskRoute(invoice)) {
            attributionService.requireFinalAttributionsAccountingAppliedForFrozenSource(
                    ContractorActualPaymentSourceKind.COMMON_INVOICE, invoice.getId(), mode);
        } else {
            attributionService.requireFinalAttributionsAccountingApplied(
                    ContractorActualPaymentSourceKind.COMMON_INVOICE, invoice.getId());
        }
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RecordedBatch recordFinalReceipt(
            CommonInvoice invoice,
            List<CommonInvoiceOrder> items,
            long expectedAmountKopecks,
            CommonManualPaymentAttributionRequest request,
            Principal principal
    ) {
        if (expectedAmountKopecks <= 0) {
            throw conflict("У общего счета нет суммы для ручного зачисления");
        }
        ContractorAllocationMode mode = accountingMode(invoice);
        Optional<ManualPaymentTaskRouteSnapshot> frozenTaskRoute = taskReceiptIntegrationService.candidate(invoice);
        settleTaskReceipt(invoice, request, frozenTaskRoute, principal);
        PreparedBatch batch = prepare(
                invoice,
                activeItems(items),
                request,
                principal,
                expectedAmountKopecks,
                true,
                mode,
                frozenTaskRoute
        );
        boolean replay = batch.commands().stream()
                .allMatch(command -> attributionRepository.findByAttributionKey(command.attributionKey()).isPresent());
        List<ContractorActualPaymentAttribution> recorded = isFrozenTaskRoute(invoice)
                ? attributionService.recordFinalAttributionsForFrozenSource(
                        batch.source(), batch.commands(), mode)
                : attributionService.recordFinalAttributions(batch.source(), batch.commands());
        return new RecordedBatch(List.copyOf(recorded), replay);
    }

    private ContractorAllocationMode accountingMode(CommonInvoice invoice) {
        if (!isFrozenTaskRoute(invoice)) {
            return attributionService.lockEnabledAccountingMode();
        }
        ContractorAllocationMode persisted = invoice.getPaymentRouteManualTaskAccountingMode();
        if (persisted == null) {
            throw conflict("У выданного маршрута задания отсутствует режим учёта; требуется сверка");
        }
        // The global phase row remains the first financial mutex. A later
        // SHADOW->LIVE promotion must not reinterpret the issued receipt.
        accountingPhaseService.lockCurrent();
        return persisted;
    }

    private boolean isFrozenTaskRoute(CommonInvoice invoice) {
        return invoice != null
                && invoice.getPaymentRouteManualSource() == ManualPaymentSource.MANUAL_TASK
                && invoice.getPaymentRouteSelectedAt() != null
                && invoice.getPaymentRouteManualTaskId() != null
                && invoice.getPaymentRouteManualTaskGeneration() != null
                && !normalize(invoice.getPaymentRouteManualTaskSourceGeneration()).isBlank();
    }

    private PreparedBatch prepare(
            CommonInvoice invoice,
            List<CommonInvoiceOrder> items,
            CommonManualPaymentAttributionRequest request,
            Principal principal,
            Long expectedAmountKopecks,
            boolean validateCandidates,
            ContractorAllocationMode mode
            , Optional<ManualPaymentTaskRouteSnapshot> frozenTaskRoute
    ) {
        requireInvoice(invoice);
        validateRequest(request);
        PreparedContext context = context(invoice, items, mode, frozenTaskRoute);
        Map<String, CommonManualPaymentRecipientCandidateResponse> candidatesByRecipient = new LinkedHashMap<>();
        context.candidates().values().stream()
                .map(CandidateState::response)
                .forEach(candidate -> candidatesByRecipient.put(candidate.key(), candidate));

        Set<String> rowKeys = new HashSet<>();
        Set<String> recipients = new HashSet<>();
        Set<String> economicRecipients = new HashSet<>();
        long total = 0L;
        List<ContractorActualPaymentRecipientCommand> commands = new ArrayList<>();
        for (CommonManualPaymentAttributionRowRequest row : request.attributions()) {
            String rowKey = cleanKey(row == null ? null : row.rowKey(), "ключ строки");
            if (!rowKeys.add(rowKey)) {
                throw badRequest("Ключи строк распределения не должны повторяться");
            }
            validateRecipientShape(row);
            String recipientKey = requestedRecipientKey(row);
            CommonManualPaymentRecipientCandidateResponse candidate = candidatesByRecipient.get(recipientKey);
            if (!recipients.add(recipientKey)) {
                throw badRequest("Один получатель не должен повторяться в нескольких строках");
            }
            if (validateCandidates && candidate == null) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            if (candidate != null
                    && (!Objects.equals(candidate.recipientType(), row.recipientType())
                    || !Objects.equals(candidate.recipientProfileId(), row.recipientProfileId()))) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            if (candidate != null) {
                requireUniqueEconomicRecipient(economicRecipients, candidate);
            }
            if (candidate != null && candidate.recipientUserId() != null) {
                targetAccessPolicy.requireCanManageUser(candidate.recipientUserId());
            }
            try {
                total = Math.addExact(total, row.amountKopecks());
            } catch (ArithmeticException exception) {
                throw badRequest("Сумма распределения слишком велика");
            }
            commands.add(new ContractorActualPaymentRecipientCommand(
                    attributionKey(invoice.getId(), request.idempotencyKey(), rowKey),
                    row.recipientType(),
                    row.recipientProfileId(),
                    row.amountKopecks(),
                    candidate == null ? null : candidate.label(),
                    recipientKey,
                    candidate == null ? null : candidate.cashDestinationKind(),
                    candidate == null ? null : candidate.manualPaymentTaskId(),
                    candidate == null ? null : candidate.manualPaymentTaskGeneration(),
                    candidate == null ? null : candidate.taskTargetKind()
            ));
        }
        if (expectedAmountKopecks != null && total != expectedAmountKopecks) {
            throw badRequest("Сумма по получателям должна быть равна остатку общего счета");
        }

        ContractorActualPaymentSource source = new ContractorActualPaymentSource(
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                invoice.getId(),
                null,
                null,
                invoice.getId(),
                context.explicitOriginalAllocationId(),
                invoice.getContractorAllocationId(),
                context.clientFacingRecipientType(),
                context.clientFacingRecipientProfileId(),
                context.clientFacingRecipientName(),
                context.currentWorkerId(),
                context.currentManagerId(),
                request.effectiveAt(),
                normalize(request.reason()),
                evidenceReference(invoice.getId(), request.idempotencyKey()),
                normalize(request.receiptUrl()),
                actor(principal),
                context.clientFacingCashDestinationKind(),
                context.clientFacingTaskId(),
                context.clientFacingTaskGeneration(),
                context.clientFacingTaskTargetKind()
        );
        return new PreparedBatch(source, List.copyOf(commands));
    }

    void requireUniqueEconomicRecipient(
            Set<String> seen,
            CommonManualPaymentRecipientCandidateResponse candidate
    ) {
        String key;
        if (candidate.cashDestinationKind() == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK) {
            key = candidate.taskTargetKind() == null ? "" : switch (candidate.taskTargetKind()) {
                case OWNER -> OWNER_KEY;
                case SPECIALIST, MANAGER -> candidate.recipientProfileId() == null
                        ? "" : "PROFILE:" + candidate.recipientProfileId();
                case EXTERNAL_TASK -> candidate.key();
                default -> "";
            };
        } else if (candidate.recipientType() == ContractorRecipientType.OWNER) {
            key = OWNER_KEY;
        } else {
            key = candidate.recipientProfileId() == null
                    ? "" : "PROFILE:" + candidate.recipientProfileId();
        }
        if (key.isBlank()) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        if (!seen.add(key)) {
            throw badRequest(
                    "Нельзя разбивать один платёж между заданием и тем же фактическим получателем"
            );
        }
    }

    private PreparedContext context(CommonInvoice invoice, List<CommonInvoiceOrder> items, ContractorAllocationMode mode) {
        return context(invoice, items, mode, taskReceiptIntegrationService.candidate(invoice));
    }

    private PreparedContext context(
            CommonInvoice invoice, List<CommonInvoiceOrder> items, ContractorAllocationMode mode,
            Optional<ManualPaymentTaskRouteSnapshot> taskRoute
    ) {
        ContractorPaymentAllocation accountingAllocation = currentAccountingAllocation(invoice, mode).orElse(null);
        boolean contractorWasShown = invoice.getPaymentRouteManualSource()
                == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE;
        ContractorPaymentAllocation clientAllocation = contractorWasShown
                ? explicitClientAllocation(invoice).orElseThrow(() ->
                        conflict("Зафиксированный получатель общего счета поврежден; нужна ручная сверка"))
                : null;
        ContractorRecipientType clientType = taskRoute.isPresent()
                ? taskReceiptIntegrationService.destination(taskRoute.get()).recipientType()
                : clientAllocation == null
                ? ContractorRecipientType.OWNER
                : clientAllocation.getRecipientType();
        Long clientProfileId = taskRoute.isPresent()
                ? taskReceiptIntegrationService.destination(taskRoute.get()).recipientProfileId()
                : clientAllocation == null || clientAllocation.getRecipientProfile() == null
                ? null
                : clientAllocation.getRecipientProfile().getId();
        String clientName = taskRoute.isPresent()
                ? taskRoute.get().bankRecipientName()
                : clientAllocation == null
                ? "Владелец"
                : normalize(clientAllocation.getRecipientNameSnapshot());
        if (clientAllocation != null && (clientProfileId == null || !isContractor(clientType))) {
            throw conflict("Зафиксированный платежный профиль общего счета недоступен; нужна ручная сверка");
        }

        Map<String, CandidateState> candidates = new LinkedHashMap<>();
        CandidateState owner = new CandidateState(
                OWNER_KEY,
                ContractorRecipientType.OWNER,
                null,
                null,
                "Владелец",
                clientType == ContractorRecipientType.OWNER,
                true,
                true,
                null
        );
        candidates.put(owner.key, owner);
        taskRoute.ifPresent(snapshot -> {
            ManualPaymentTaskReceiptIntegrationService.Destination destination =
                    taskReceiptIntegrationService.destination(snapshot);
            ContractorPaymentProfile taskProfile = destination.recipientProfileId() == null ? null
                    : profileRepository.findById(destination.recipientProfileId())
                            .orElseThrow(ManualPaymentTaskRouteErrors::stale);
            if (snapshot.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                    || snapshot.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.MANAGER) {
                ContractorRole expected = snapshot.accountingTargetKind()
                        == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                        ? ContractorRole.SPECIALIST : ContractorRole.MANAGER;
                if (taskProfile == null || taskProfile.getRole() != expected || taskProfile.getUser() == null
                        || taskProfile.getUser().getId() == null
                        || !targetAccessPolicy.canManageUser(taskProfile.getUser().getId())) {
                    throw ManualPaymentTaskRouteErrors.stale();
                }
            } else if (taskProfile != null) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            Long userId = taskProfile == null ? null : taskProfile.getUser().getId();
            candidates.put(snapshot.candidateKey(), new CandidateState(
                    snapshot.candidateKey(), destination.recipientType(), destination.recipientProfileId(),
                    userId, "Платёжное задание #" + snapshot.taskId() + " · " + snapshot.bankRecipientName(),
                    true, true, true, null,
                    ContractorCashDestinationKind.MANUAL_PAYMENT_TASK, snapshot.taskId(),
                    snapshot.taskGeneration(), snapshot.accountingTargetKind(), snapshot.bankRecipientName(),
                    snapshot.accountingTargetLabel(), "Сумма будет зачтена в платёжное задание"
            ));
        });

        if (clientAllocation != null) {
            addProfileCandidate(
                    candidates,
                    clientAllocation.getRecipientProfile(),
                    clientType,
                    true,
                    false,
                    availableFor(clientAllocation.getRecipientProfile(), mode, accountingAllocation)
            );
        }

        Set<Long> workerIds = new HashSet<>();
        Set<Long> managerIds = new HashSet<>();
        for (CommonInvoiceOrder item : items) {
            Order order = item == null ? null : item.getOrder();
            Worker worker = order == null ? null : order.getWorker();
            User workerUser = worker == null ? null : worker.getUser();
            if (worker != null && worker.getId() != null) {
                workerIds.add(worker.getId());
            }
            addUserProfileCandidate(
                    candidates,
                    workerUser,
                    ContractorRole.SPECIALIST,
                    ContractorRecipientType.SPECIALIST,
                    clientType,
                    clientProfileId,
                    mode,
                    accountingAllocation
            );

            Manager manager = orderManagerResolver.resolveForRouting(order);
            if (manager != null && manager.getId() != null) {
                managerIds.add(manager.getId());
            }
            addUserProfileCandidate(
                    candidates,
                    manager == null ? null : manager.getUser(),
                    ContractorRole.MANAGER,
                    ContractorRecipientType.MANAGER,
                    clientType,
                    clientProfileId,
                    mode,
                    accountingAllocation
            );
        }
        Manager accountManager = invoice.getAccount() == null ? null : invoice.getAccount().getManager();
        if (accountManager != null && accountManager.getId() != null) {
            managerIds.add(accountManager.getId());
        }
        addUserProfileCandidate(
                candidates,
                accountManager == null ? null : accountManager.getUser(),
                ContractorRole.MANAGER,
                ContractorRecipientType.MANAGER,
                clientType,
                clientProfileId,
                mode,
                accountingAllocation
        );
        taskRoute.ifPresent(snapshot -> {
            ManualPaymentTaskAccountingTargetKind targetKind = snapshot.accountingTargetKind();
            if (targetKind == null) {
                throw ManualPaymentTaskRouteErrors.stale();
            }
            switch (targetKind) {
                case OWNER -> candidates.remove(OWNER_KEY);
                case SPECIALIST, MANAGER -> {
                    Long taskProfileId = taskReceiptIntegrationService.destination(snapshot).recipientProfileId();
                    if (taskProfileId == null) {
                        throw ManualPaymentTaskRouteErrors.stale();
                    }
                    candidates.remove(profileKey(taskProfileId));
                }
                case EXTERNAL_TASK -> {
                    // No ordinary OWNER/PROFILE alias exists for an external task recipient.
                }
                default -> throw ManualPaymentTaskRouteErrors.stale();
            }
        });

        String defaultKey = taskRoute.map(ManualPaymentTaskRouteSnapshot::candidateKey).orElseGet(() ->
                clientType == ContractorRecipientType.OWNER
                ? OWNER_KEY
                : profileKey(clientProfileId));
        Long currentWorkerId = workerIds.size() == 1 ? workerIds.iterator().next() : null;
        Long currentManagerId = managerIds.size() == 1 ? managerIds.iterator().next() : null;
        return new PreparedContext(
                candidates,
                defaultKey,
                accountingAllocation != null
                        && Objects.equals(accountingAllocation.getId(), invoice.getContractorAllocationId())
                        ? invoice.getContractorAllocationId()
                        : null,
                clientType,
                clientProfileId,
                clientName,
                taskRoute.isPresent() ? ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                        : clientType == ContractorRecipientType.OWNER
                        ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE,
                taskRoute.map(ManualPaymentTaskRouteSnapshot::taskId).orElse(null),
                taskRoute.map(ManualPaymentTaskRouteSnapshot::taskGeneration).orElse(null),
                taskRoute.map(ManualPaymentTaskRouteSnapshot::accountingTargetKind).orElse(null),
                currentWorkerId,
                currentManagerId
        );
    }

    private void addUserProfileCandidate(
            Map<String, CandidateState> candidates,
            User user,
            ContractorRole role,
            ContractorRecipientType type,
            ContractorRecipientType clientType,
            Long clientProfileId,
            ContractorAllocationMode mode,
            ContractorPaymentAllocation accountingAllocation
    ) {
        if (user == null || user.getId() == null) {
            return;
        }
        profileRepository.findByUserIdAndRole(user.getId(), role).ifPresent(profile -> addProfileCandidate(
                candidates,
                profile,
                type,
                type == clientType && Objects.equals(profile.getId(), clientProfileId),
                true,
                availableFor(profile, mode, accountingAllocation)
        ));
    }

    private void addProfileCandidate(
            Map<String, CandidateState> candidates,
            ContractorPaymentProfile profile,
            ContractorRecipientType type,
            boolean original,
            boolean current,
            long availableKopecks
    ) {
        if (profile == null || profile.getId() == null || profile.getUser() == null
                || profile.getUser().getId() == null
                || !targetAccessPolicy.canManageUser(profile.getUser().getId())) {
            return;
        }
        String key = profileKey(profile.getId());
        String role = type == ContractorRecipientType.SPECIALIST ? "Специалист" : "Менеджер";
        String name = normalize(profile.getUser().getFio());
        String label = name.isBlank() ? role : role + " · " + name;
        CandidateState existing = candidates.get(key);
        if (existing == null) {
            candidates.put(key, new CandidateState(
                    key,
                    type,
                    profile.getId(),
                    profile.getUser().getId(),
                    label,
                    original,
                    current,
                    profile.isEnabled(),
                    availableKopecks
            ));
        } else {
            existing.originalRecipient |= original;
            existing.currentParticipant |= current;
        }
    }

    private long availableFor(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            ContractorPaymentAllocation accountingAllocation
    ) {
        long base = taskCapacityService.ordinaryAvailable(profile, mode);
        if (accountingAllocation == null
                || accountingAllocation.getRecipientProfile() == null
                || !Objects.equals(accountingAllocation.getRecipientProfile().getId(), profile.getId())
                || !OUTSTANDING.contains(accountingAllocation.getStatus())) {
            return base;
        }
        long allocationOutstanding = Math.max(
                0L,
                accountingAllocation.getAmountKopecks()
                        - Math.max(0L, accountingAllocation.getConfirmedKopecks()
                        - accountingAllocation.getReturnedKopecks())
        );
        try {
            return Math.addExact(base, allocationOutstanding);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private Optional<ContractorPaymentAllocation> explicitClientAllocation(CommonInvoice invoice) {
        if (invoice.getContractorAllocationId() == null) {
            return Optional.empty();
        }
        return allocationRepository.findById(invoice.getContractorAllocationId())
                .filter(allocation -> allocation.getSourceType() == ContractorAllocationSourceType.COMMON_INVOICE)
                .filter(allocation -> Objects.equals(allocation.getSourceId(), invoice.getId()))
                .filter(allocation -> Objects.equals(allocation.getCommonInvoiceId(), invoice.getId()));
    }

    private Optional<ContractorPaymentAllocation> currentAccountingAllocation(
            CommonInvoice invoice,
            ContractorAllocationMode mode
    ) {
        Optional<ContractorPaymentAllocation> explicit = explicitClientAllocation(invoice)
                .filter(allocation -> allocation.getMode() == mode);
        if (explicit.isPresent()) {
            return explicit;
        }
        return allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                mode,
                ContractorAllocationSourceType.COMMON_INVOICE,
                invoice.getId()
        );
    }

    private List<CommonManualPaymentAttributionResponse> history(Long invoiceId) {
        return attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                        ContractorActualPaymentSourceKind.COMMON_INVOICE,
                        invoiceId
                ).stream()
                .filter(this::canExposeHistory)
                .map(this::historyItem)
                .toList();
    }

    private boolean canExposeHistory(ContractorActualPaymentAttribution row) {
        return row != null
                && canExposeRecipient(row.getOriginalRecipientType(), row.getOriginalRecipientUserId())
                && canExposeRecipient(row.getActualRecipientType(), row.getActualRecipientUserId());
    }

    private boolean canExposeRecipient(ContractorRecipientType type, Long userId) {
        if (type == ContractorRecipientType.SPECIALIST
                || type == ContractorRecipientType.MANAGER) {
            return userId != null && targetAccessPolicy.canManageUser(userId);
        }
        // OWNER and EXTERNAL_TASK legitimately have no contractor user. If a
        // user snapshot is present, keep the ordinary object-scope check.
        return userId == null || targetAccessPolicy.canManageUser(userId);
    }

    private CommonManualPaymentAttributionResponse historyItem(ContractorActualPaymentAttribution row) {
        return new CommonManualPaymentAttributionResponse(
                row.getId(),
                row.getAttributionKey(),
                row.getAccountingMode(),
                row.getOriginalRecipientType(),
                row.getOriginalRecipientProfileId(),
                immutableLabel(row.getOriginalRecipientType(), row.getOriginalRecipientNameSnapshot()),
                row.getActualRecipientType(),
                row.getActualRecipientProfileId(),
                immutableLabel(row.getActualRecipientType(), row.getActualRecipientNameSnapshot()),
                row.getAmountKopecks(),
                row.getAvailableBeforeKopecks(),
                row.getProjectedOverrunKopecks(),
                row.getEffectiveAt(),
                row.getReason(),
                row.getEvidenceReference(),
                row.getActor(),
                row.getCreatedAt()
                , row.getOriginalCashDestinationKind()
                , row.getOriginalManualPaymentTaskId()
                , row.getOriginalManualPaymentTaskGeneration()
                , row.getOriginalManualPaymentTaskTargetKind()
                , row.getActualCashDestinationKind()
                , row.getActualManualPaymentTaskId()
                , row.getActualManualPaymentTaskGeneration()
                , row.getActualManualPaymentTaskTargetKind()
        );
    }

    private String immutableLabel(ContractorRecipientType type, String snapshot) {
        String role = type == null
                ? "Внешний получатель"
                : type == ContractorRecipientType.OWNER
                ? "Владелец"
                : type == ContractorRecipientType.MANAGER ? "Менеджер" : "Специалист";
        String name = normalize(snapshot);
        return name.isBlank() || name.equalsIgnoreCase(role) ? role : role + " · " + name;
    }

    private void validateRequest(CommonManualPaymentAttributionRequest request) {
        if (request == null
                || !Boolean.TRUE.equals(request.finalAccountingAcknowledged())
                || !Boolean.TRUE.equals(request.paymentReceived())) {
            throw badRequest("Подтвердите фактическое поступление и финальное изменение расчётов");
        }
        cleanKey(request.idempotencyKey(), "ключ операции");
        if (request.effectiveAt() == null) {
            throw badRequest("Укажите время фактического поступления");
        }
        if (request.effectiveAt().isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw badRequest("Время поступления не может быть в будущем");
        }
        String reason = normalize(request.reason());
        if (reason.isBlank()) {
            throw badRequest("Укажите основание ручного подтверждения");
        }
        if (reason.length() > 500) {
            throw badRequest("Основание не должно превышать 500 символов");
        }
        validateReceiptUrl(request.receiptUrl());
        if (request.attributions() == null || request.attributions().isEmpty()) {
            throw badRequest("Добавьте хотя бы одного фактического получателя");
        }
        if (request.attributions().size() > 20) {
            throw badRequest("В одном подтверждении допускается не более 20 получателей");
        }
    }

    private void validateRecipientShape(CommonManualPaymentAttributionRowRequest row) {
        if (row == null || normalize(row.recipientKey()).isBlank() || row.amountKopecks() <= 0) {
            throw badRequest("Получатель и положительная сумма обязательны");
        }
        if (row.recipientKey().startsWith("TASK:")) {
            return;
        }
        boolean owner = row.recipientType() == ContractorRecipientType.OWNER;
        if (owner != (row.recipientProfileId() == null)) {
            throw badRequest(owner
                    ? "Для владельца платежный профиль не указывается"
                    : "Для специалиста или менеджера нужен платежный профиль");
        }
    }

    private String requestedRecipientKey(CommonManualPaymentAttributionRowRequest row) {
        String key = normalize(row == null ? null : row.recipientKey());
        if (key.isBlank()) throw ManualPaymentTaskRouteErrors.actualRecipientRequired();
        return key;
    }

    private String recordedRecipientKey(ContractorActualPaymentAttribution row) {
        return row.getActualCashDestinationKind() == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                ? com.hunt.otziv.payments.service.ManualPaymentTaskLedgerService.candidateKey(
                        row.getActualManualPaymentTaskId(), row.getActualManualPaymentTaskGeneration())
                : recipientKey(row.getActualRecipientType(), row.getActualRecipientProfileId());
    }

    private void settleTaskReceipt(
            CommonInvoice invoice, CommonManualPaymentAttributionRequest request,
            Optional<ManualPaymentTaskRouteSnapshot> taskRoute, Principal principal
    ) {
        if (taskRoute.isEmpty()) return;
        String taskKey = taskRoute.get().candidateKey();
        long taskAmount = 0L;
        for (CommonManualPaymentAttributionRowRequest row : request.attributions()) {
            String key = requestedRecipientKey(row);
            if (key.startsWith("TASK:") && !key.equals(taskKey)) throw ManualPaymentTaskRouteErrors.stale();
            if (key.equals(taskKey)) taskAmount = Math.addExact(taskAmount, row.amountKopecks());
        }
        String selectedKey = taskAmount > 0 ? taskKey : "SPLIT:" + cleanKey(request.idempotencyKey(), "ключ повтора");
        taskReceiptIntegrationService.settle(
                invoice, selectedKey, taskAmount,
                "TASK:SETTLE:COMMON_INVOICE:" + invoice.getId() + ":" + request.idempotencyKey(),
                actor(principal), normalize(request.reason()));
    }

    private void settleTaskReceipt(
            CommonInvoice invoice, CommonManualPaymentAttributionRequest request,
            List<ContractorActualPaymentAttribution> recorded
    ) {
        String recordedActor = exactRecordedActor(recorded);
        long taskAmount = recorded.stream()
                .filter(row -> row.getActualCashDestinationKind()
                        == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK)
                .mapToLong(ContractorActualPaymentAttribution::getAmountKopecks).sum();
        String selectedKey = recorded.stream()
                .filter(row -> row.getActualCashDestinationKind()
                        == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK)
                .map(this::recordedRecipientKey).findFirst()
                .orElse("SPLIT:" + cleanKey(request.idempotencyKey(), "ключ повтора"));
        taskReceiptIntegrationService.settle(
                invoice, selectedKey, taskAmount,
                "TASK:SETTLE:COMMON_INVOICE:" + invoice.getId() + ":" + request.idempotencyKey(),
                recordedActor, normalize(request.reason()));
    }

    private String exactRecordedActor(List<ContractorActualPaymentAttribution> recorded) {
        if (recorded == null || recorded.isEmpty()) {
            throw conflict("Записанная разбивка платежа повреждена и требует сверки");
        }
        String actor = recorded.getFirst().getActor();
        if (normalize(actor).isBlank()
                || recorded.stream().anyMatch(row -> !Objects.equals(actor, row.getActor()))) {
            throw conflict("У записанной разбивки платежа расходятся исполнители; требуется сверка");
        }
        return actor;
    }

    private void validateReceiptUrl(String rawValue) {
        String value = normalize(rawValue);
        if (value.isBlank()) {
            return;
        }
        if (value.length() > 1024) {
            throw badRequest("Ссылка на чек не должна превышать 1024 символа");
        }
        try {
            URI uri = new URI(value);
            String scheme = normalize(uri.getScheme()).toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https"))
                    || normalize(uri.getHost()).isBlank()
                    || uri.getUserInfo() != null) {
                throw badRequest("Ссылка на чек должна быть безопасной ссылкой http или https");
            }
        } catch (URISyntaxException exception) {
            throw badRequest("Ссылка на чек имеет некорректный формат");
        }
    }

    private List<CommonInvoiceOrder> activeItems(List<CommonInvoiceOrder> items) {
        return items == null ? List.of() : items.stream()
                .filter(Objects::nonNull)
                .filter(CommonInvoiceOrder::isActiveMembership)
                .toList();
    }

    private static String cleanKey(String value, String title) {
        String clean = normalize(value);
        if (!CLIENT_KEY.matcher(clean).matches()) {
            throw badRequest("Некорректный " + title);
        }
        return clean.toLowerCase(Locale.ROOT);
    }

    private String attributionKey(Long invoiceId, String operationKey, String rowKey) {
        return KEY_PREFIX + invoiceId + ":" + cleanKey(operationKey, "ключ операции") + ":" + rowKey;
    }

    static String evidenceReference(Long invoiceId, String operationKey) {
        return KEY_PREFIX + invoiceId + ":" + cleanKey(operationKey, "ключ операции");
    }

    private String profileKey(Long profileId) {
        return "PROFILE:" + profileId;
    }

    private String recipientKey(ContractorRecipientType type, Long profileId) {
        return type == ContractorRecipientType.OWNER ? OWNER_KEY : profileKey(profileId);
    }

    private boolean isContractor(ContractorRecipientType type) {
        return type == ContractorRecipientType.SPECIALIST || type == ContractorRecipientType.MANAGER;
    }

    private String actor(Principal principal) {
        String actor = normalize(principal == null ? null : principal.getName());
        if (actor.isBlank()) {
            return "system";
        }
        return actor.length() <= 150 ? actor : actor.substring(0, 150);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private void requireInvoice(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record RecordedBatch(
            List<ContractorActualPaymentAttribution> rows,
            boolean replayed
    ) {
    }

    private record PreparedBatch(
            ContractorActualPaymentSource source,
            List<ContractorActualPaymentRecipientCommand> commands
    ) {
    }

    private record PreparedContext(
            Map<String, CandidateState> candidates,
            String defaultRecipientKey,
            Long explicitOriginalAllocationId,
            ContractorRecipientType clientFacingRecipientType,
            Long clientFacingRecipientProfileId,
            String clientFacingRecipientName,
            ContractorCashDestinationKind clientFacingCashDestinationKind,
            Long clientFacingTaskId,
            Long clientFacingTaskGeneration,
            com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind clientFacingTaskTargetKind,
            Long currentWorkerId,
            Long currentManagerId
    ) {
    }

    private static final class CandidateState {
        private final String key;
        private final ContractorRecipientType recipientType;
        private final Long recipientProfileId;
        private final Long recipientUserId;
        private final String label;
        private boolean originalRecipient;
        private boolean currentParticipant;
        private final boolean profileEnabled;
        private final Long availableKopecks;
        private final ContractorCashDestinationKind cashDestinationKind;
        private final Long taskId;
        private final Long taskGeneration;
        private final com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind taskTargetKind;
        private final String taskRecipientName;
        private final String accountingTargetLabel;
        private final String effectText;

        private CandidateState(
                String key,
                ContractorRecipientType recipientType,
                Long recipientProfileId,
                Long recipientUserId,
                String label,
                boolean originalRecipient,
                boolean currentParticipant,
                boolean profileEnabled,
                Long availableKopecks
        ) {
            this(key, recipientType, recipientProfileId, recipientUserId, label, originalRecipient,
                    currentParticipant, profileEnabled, availableKopecks,
                    recipientType == ContractorRecipientType.OWNER
                            ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE,
                    null, null, null, null, label,
                    recipientType == ContractorRecipientType.OWNER
                            ? "Сумма будет учтена владельцу" : "Сумма будет учтена выбранному работнику");
        }

        private CandidateState(
                String key, ContractorRecipientType recipientType, Long recipientProfileId,
                Long recipientUserId, String label, boolean originalRecipient,
                boolean currentParticipant, boolean profileEnabled, Long availableKopecks,
                ContractorCashDestinationKind cashDestinationKind, Long taskId, Long taskGeneration,
                com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind taskTargetKind,
                String taskRecipientName, String accountingTargetLabel, String effectText
        ) {
            this.key = key;
            this.recipientType = recipientType;
            this.recipientProfileId = recipientProfileId;
            this.recipientUserId = recipientUserId;
            this.label = label;
            this.originalRecipient = originalRecipient;
            this.currentParticipant = currentParticipant;
            this.profileEnabled = profileEnabled;
            this.availableKopecks = availableKopecks;
            this.cashDestinationKind = cashDestinationKind;
            this.taskId = taskId;
            this.taskGeneration = taskGeneration;
            this.taskTargetKind = taskTargetKind;
            this.taskRecipientName = taskRecipientName;
            this.accountingTargetLabel = accountingTargetLabel;
            this.effectText = effectText;
        }

        private CommonManualPaymentRecipientCandidateResponse response() {
            return new CommonManualPaymentRecipientCandidateResponse(
                    key,
                    recipientType,
                    recipientProfileId,
                    recipientUserId,
                    label,
                    originalRecipient,
                    currentParticipant,
                    profileEnabled,
                    availableKopecks, cashDestinationKind, taskId, taskGeneration,
                    taskTargetKind, taskRecipientName, accountingTargetLabel, effectText
            );
        }
    }
}
