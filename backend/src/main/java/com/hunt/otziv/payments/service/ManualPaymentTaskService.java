package com.hunt.otziv.payments.service;

import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentProfileService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentAccountingPhaseService;
import com.hunt.otziv.payments.dto.CreateManualPaymentTaskRequest;
import com.hunt.otziv.payments.dto.ManualPaymentTaskAccountingTargetOption;
import com.hunt.otziv.payments.dto.ManualPaymentTaskBalance;
import com.hunt.otziv.payments.dto.ManualPaymentRecipientMonthlySummaryItem;
import com.hunt.otziv.payments.dto.ManualPaymentRecipientMonthlySummaryResponse;
import com.hunt.otziv.payments.dto.ManualPaymentTaskResponse;
import com.hunt.otziv.payments.dto.UpdateManualPaymentTaskRequest;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskCreationRequest;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskStatus;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.ManualPaymentTaskCreationRequestRepository;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskAccountingTargetPolicy.TargetResolution;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ManualPaymentTaskService {

    private static final Set<PaymentLinkStatus> RESERVED_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED,
            PaymentLinkStatus.CONFIRMED
    );
    private static final Set<PaymentLinkStatus> CONFIRMED_STATUSES = Set.of(PaymentLinkStatus.CONFIRMED);
    private static final Set<PaymentLinkStatus> PENDING_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED
    );
    private static final Set<PaymentMethod> MANUAL_PAYMENT_METHODS = Set.of(
            PaymentMethod.MANUAL_MOBILE_BANK,
            PaymentMethod.MANUAL_EXTERNAL_LINK
    );
    private static final Set<CommonInvoiceStatus> ACTIVE_COMMON_ROUTE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID,
            CommonInvoiceStatus.NEEDS_ATTENTION
    );

    private final ManualPaymentTaskRepository manualPaymentTaskRepository;
    private final ManualPaymentTaskCreationRequestRepository taskCreationRequestRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final ManagerRepository managerRepository;
    private final PaymentProfileService paymentProfileService;
    private final ManualPaymentTaskLedgerService taskLedgerService;
    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;
    private final ManualPaymentTaskAccountingTargetPolicy accountingTargetPolicy;
    private final ManualPaymentTaskContractorCapacityService contractorCapacityService;
    private final ContractorPaymentAccountingPhaseService contractorPaymentAccountingPhaseService;
    private final ManualPaymentRecipientMonthlySummaryService recipientMonthlySummaryService;

    @Transactional(readOnly = true)
    public List<ManualPaymentTaskResponse> managerTasks(Long userId) {
        return manualPaymentTaskRepository.findAllByManagerUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ManualPaymentTaskResponse createManagerTask(
            Long userId,
            CreateManualPaymentTaskRequest request,
            String actor
    ) {
        Manager manager = managerByUserId(userId);
        return createTask(manager, request, actor, true);
    }

    @Transactional
    public ManualPaymentTaskResponse createManagementTask(
            CreateManualPaymentTaskRequest request,
            String actor
    ) {
        Manager manager = managerById(request == null ? null : request.managerId());
        return createTask(manager, request, actor, false);
    }

    private ManualPaymentTaskResponse createTask(
            Manager manager,
            CreateManualPaymentTaskRequest request,
            String actor,
            boolean managerScoped
    ) {
        String operationKey = requiredCreationOperationKey(request == null ? null : request.operationKey());
        String payloadHash = creationPayloadHash(manager, request, managerScoped);
        taskCreationRequestRepository.insertIfAbsent(operationKey, payloadHash);
        ManualPaymentTaskCreationRequest creation = taskCreationRequestRepository
                .findByOperationKeyForUpdate(operationKey)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Ключ создания задания не удалось зафиксировать"
                ));
        if (!payloadHash.equals(creation.getPayloadHash())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ключ создания задания уже использован с другими данными"
            );
        }
        if (creation.getTaskId() != null) {
            ManualPaymentTask replay = manualPaymentTaskRepository
                    .findByIdWithDetails(creation.getTaskId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Созданное ранее платёжное задание недоступно; нужна сверка"
                    ));
            return toResponse(replay);
        }
        // Exact creation replays above remain write-free and need no routing
        // locks. New tasks use the same Manager -> PaymentProfile order as
        // ordinary route creation; the accounting-phase mutex is then taken
        // before any contractor target/profile resolution or task write.
        manager = paymentProfileService.lockManagerForRouting(manager);
        PaymentProfile profile = paymentProfileService.lockForRouting(
                paymentProfileService.selectForManager(manager)
        );
        contractorPaymentAccountingPhaseService.lockCurrent();
        long targetAmountKopecks = requiredPositive(request == null ? null : request.targetAmountKopecks());
        TargetResolution target = managerScoped
                ? accountingTargetPolicy.resolveForManager(
                        manager,
                        request == null ? null : request.accountingTargetKind(),
                        request == null ? null : request.accountingTargetProfileId(),
                        targetAmountKopecks,
                        request != null && Boolean.TRUE.equals(request.accountingTargetOverrunAcknowledged()),
                        null)
                : accountingTargetPolicy.resolveForManagement(
                        request == null ? null : request.accountingTargetKind(),
                        request == null ? null : request.accountingTargetProfileId(),
                        targetAmountKopecks,
                        request != null && Boolean.TRUE.equals(request.accountingTargetOverrunAcknowledged()),
                        null);
        ManualPaymentTask task = new ManualPaymentTask();
        task.setManager(manager);
        task.setPaymentProfile(profile);
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        ManualPaymentType type = parseManualPaymentType(request == null ? null : request.manualPaymentType());
        task.setManualPaymentType(type);
        task.setManualPhone(limit(request == null ? null : request.manualPhone(), 32));
        task.setManualRecipientName(recipientOrDefault(request == null ? null : request.manualRecipientName()));
        task.setManualBankName(limit(request == null ? null : request.manualBankName(), 120));
        task.setManualPaymentUrl(paymentUrlOrDefault(request == null ? null : request.manualPaymentUrl()));
        task.setManualPaymentButtonLabel(buttonLabelOrDefault(request == null ? null : request.manualPaymentButtonLabel()));
        validatePaymentTarget(task);
        task.setTargetAmountKopecks(targetAmountKopecks);
        applyTarget(task, target, actor);
        task.setGeneration(1);
        task.setNeedsReconciliation(false);
        task.setComment(limit(request == null ? null : request.comment(), 255));
        task.setCreatedBy(limit(actor, 160));
        task.setUpdatedBy(limit(actor, 160));
        ManualPaymentTask saved = manualPaymentTaskRepository.save(task);
        if (saved.getId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не удалось зафиксировать созданное платёжное задание"
            );
        }
        contractorCapacityService.synchronize(
                ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot.NONE,
                saved,
                taskLedgerService.balance(saved.getId())
        );
        creation.setTaskId(saved.getId());
        creation.setCompletedAt(LocalDateTime.now());
        taskCreationRequestRepository.save(creation);
        return toResponse(saved);
    }

    @Transactional
    public ManualPaymentTaskResponse updateManagerTask(
            Long userId,
            Long taskId,
            UpdateManualPaymentTaskRequest request,
            String actor
    ) {
        ManualPaymentTaskReceiptIntegrationService.LegacySourceLocks legacyLocks =
                taskReceiptIntegrationService.lockLegacySourcesThenAccountingMode(taskId);
        ManualPaymentTask task = taskByIdForUpdate(taskId);
        assertTaskOwner(userId, task);
        return updateTask(task, request, actor, true, legacyLocks);
    }

    @Transactional
    public ManualPaymentTaskResponse updateManagementTask(
            Long taskId,
            UpdateManualPaymentTaskRequest request,
            String actor
    ) {
        ManualPaymentTaskReceiptIntegrationService.LegacySourceLocks legacyLocks =
                taskReceiptIntegrationService.lockLegacySourcesThenAccountingMode(taskId);
        return updateTask(taskByIdForUpdate(taskId), request, actor, false, legacyLocks);
    }

    @Transactional(readOnly = true)
    public List<ManualPaymentTaskResponse> managementTasks() {
        return manualPaymentTaskRepository.findAllForManagement().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ManualPaymentTaskAccountingTargetOption> managerAccountingTargetOptions(
            Long userId,
            Long targetAmountKopecks,
            Long taskId
    ) {
        return accountingTargetPolicy.managerOptions(
                managerByUserId(userId), targetAmountKopecks, taskId);
    }

    @Transactional(readOnly = true)
    public List<ManualPaymentTaskAccountingTargetOption> managementAccountingTargetOptions(
            Long managerId,
            Long targetAmountKopecks,
            Long taskId
    ) {
        return accountingTargetPolicy.managementOptions(managerId, targetAmountKopecks, taskId);
    }

    @Transactional(readOnly = true)
    public ManualPaymentRecipientMonthlySummaryResponse recipientMonthlySummary(String month) {
        return recipientMonthlySummaryService.summary(month);
    }

    @Transactional
    public ManualPaymentTaskResponse updateManagerTaskStatus(
            Long userId,
            Long taskId,
            String status,
            String actor
    ) {
        ContractorAllocationMode accountingMode = contractorPaymentAccountingPhaseService.lockCurrent();
        ManualPaymentTask task = taskByIdForUpdate(taskId);
        assertTaskOwner(userId, task);
        return updateStatus(task, status, actor, accountingMode);
    }

    @Transactional
    public ManualPaymentTaskResponse updateManagementTaskStatus(
            Long taskId,
            String status,
            String actor
    ) {
        ContractorAllocationMode accountingMode = contractorPaymentAccountingPhaseService.lockCurrent();
        return updateStatus(taskByIdForUpdate(taskId), status, actor, accountingMode);
    }

    @Transactional
    public Optional<ManualPaymentTask> findRoutableTask(
            Manager manager,
            PaymentProfile profile,
            long amountKopecks,
            Long excludedLinkId
    ) {
        if (manager == null
                || manager.getId() == null
                || profile == null
                || profile.getId() == null
                || amountKopecks <= 0) {
            return Optional.empty();
        }
        ContractorAllocationMode accountingMode = contractorPaymentAccountingPhaseService.lockCurrent();

        return manualPaymentTaskRepository
                .findActiveForRouting(manager.getId(), profile.getId(), ManualPaymentTaskStatus.ACTIVE)
                .stream()
                .filter(task -> isRoutable(task, amountKopecks, excludedLinkId, accountingMode))
                .findFirst();
    }

    @Transactional
    public void completeIfConfirmedTargetReached(ManualPaymentTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        manualPaymentTaskRepository.findByIdWithDetailsForUpdate(task.getId())
                .ifPresent(this::completeLockedIfConfirmedTargetReached);
    }

    private void completeLockedIfConfirmedTargetReached(ManualPaymentTask task) {
        if (task.getStatus() != ManualPaymentTaskStatus.ACTIVE
                && task.getStatus() != ManualPaymentTaskStatus.NEEDS_ATTENTION) {
            return;
        }
        ManualPaymentTaskBalance balance = taskLedgerService.balance(task.getId());
        ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot beforeCommitment =
                contractorCapacityService.snapshot(task, balance);
        long netConfirmed = Math.max(0, balance.netConfirmedAmountKopecks());
        if (netConfirmed < task.getTargetAmountKopecks()) {
            return;
        }
        task.setStatus(ManualPaymentTaskStatus.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        ManualPaymentTask saved = manualPaymentTaskRepository.save(task);
        contractorCapacityService.synchronize(beforeCommitment, saved, balance);
    }

    @Transactional
    public void completeCommonInvoiceTaskIfTargetReached(Long taskId) {
        if (taskId == null) {
            return;
        }
        manualPaymentTaskRepository.findByIdWithDetailsForUpdate(taskId)
                .ifPresent(this::completeLockedIfConfirmedTargetReached);
    }

    private ManualPaymentTaskResponse updateTask(
            ManualPaymentTask task,
            UpdateManualPaymentTaskRequest request,
            String actor,
            boolean managerScoped,
            ManualPaymentTaskReceiptIntegrationService.LegacySourceLocks legacyLocks
    ) {
        if (task.getStatus() == ManualPaymentTaskStatus.COMPLETED
                || task.getStatus() == ManualPaymentTaskStatus.CANCELED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Закрытое платежное задание нельзя редактировать");
        }
        if (request != null && request.expectedGeneration() != null
                && request.expectedGeneration() != task.getGeneration()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платёжное задание уже изменилось; обновите данные"
            );
        }

        ManualPaymentType type = parseManualPaymentType(request == null ? null : request.manualPaymentType());
        long targetAmountKopecks = requiredPositive(request == null ? null : request.targetAmountKopecks());
        ManualPaymentTaskBalance balance = taskLedgerService.balance(task.getId());
        ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot beforeCommitment =
                contractorCapacityService.snapshot(task, balance);
        long reserved = balance.occupiedAmountKopecks();
        if (targetAmountKopecks < reserved) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сумма задания меньше уже занятой суммы: " + moneyRubles(reserved)
            );
        }

        task.setManualPaymentType(type);
        task.setManualPhone(limit(request == null ? null : request.manualPhone(), 32));
        task.setManualRecipientName(recipientOrDefault(request == null ? null : request.manualRecipientName()));
        task.setManualBankName(limit(request == null ? null : request.manualBankName(), 120));
        boolean quarantinedPaymentUrlPreserved = applyManualPaymentUrlUpdate(task, request);
        task.setManualPaymentButtonLabel(buttonLabelOrDefault(request == null ? null : request.manualPaymentButtonLabel()));
        validatePaymentTarget(task, quarantinedPaymentUrlPreserved);
        String requestedKind = request == null ? null : request.accountingTargetKind();
        boolean preserveKind = normalize(requestedKind).isBlank();
        String effectiveKind = preserveKind ? targetKind(task).name() : requestedKind;
        Long effectiveProfileId = preserveKind
                ? profileId(task.getAccountingTargetProfile())
                : request.accountingTargetProfileId();
        boolean targetChanged = !effectiveKind.equalsIgnoreCase(targetKind(task).name())
                || !java.util.Objects.equals(effectiveProfileId, profileId(task.getAccountingTargetProfile()))
                || targetAmountKopecks != task.getTargetAmountKopecks();
        boolean acknowledged = request != null
                && Boolean.TRUE.equals(request.accountingTargetOverrunAcknowledged());
        ManualPaymentTaskAccountingTargetKind previousKind = targetKind(task);
        boolean legacyRemediation = previousKind
                == ManualPaymentTaskAccountingTargetKind.UNRESOLVED
                && legacyLocks != null
                && !legacyLocks.sources().isEmpty();
        Long previousProfileId = profileId(task.getAccountingTargetProfile());
        contractorCapacityService.lockProfilesForChange(previousProfileId, effectiveProfileId);
        TargetResolution target = managerScoped
                ? accountingTargetPolicy.resolveForManager(
                        task.getManager(), effectiveKind, effectiveProfileId,
                        targetAmountKopecks, acknowledged, task.getId(), legacyRemediation)
                : accountingTargetPolicy.resolveForManagement(
                        effectiveKind, effectiveProfileId, targetAmountKopecks,
                        acknowledged, task.getId(), legacyRemediation);
        Long targetProfileId = profileId(target.profile());
        boolean historicalProfile = legacyRemediation && target.profile() != null
                && (!target.profile().isEnabled()
                    || (legacyLocks.accountingMode() == ContractorAllocationMode.LIVE
                        && !target.profile().isLiveEnabled()));
        boolean destinationChanged = previousKind != target.kind()
                || !java.util.Objects.equals(previousProfileId, targetProfileId);
        if (previousKind != ManualPaymentTaskAccountingTargetKind.UNRESOLVED
                && destinationChanged
                && (balance.pendingAmountKopecks() > 0 || balance.netConfirmedAmountKopecks() > 0)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У задания уже есть резервы или подтверждённые оплаты; создайте новое платёжное задание"
            );
        }
        if (targetChanged) {
            task.setTargetOverrunAcknowledgedAt(null);
            task.setTargetOverrunAcknowledgedBy(null);
            task.setTargetOverrunAcknowledgedKopecks(null);
            task.setTargetCapacityAvailableSnapshotKopecks(null);
        }
        task.setTargetAmountKopecks(targetAmountKopecks);
        applyTarget(task, target, actor);
        task.setGeneration(nextGeneration(task.getGeneration()));
        task.setComment(limit(request == null ? null : request.comment(), 255));
        task.setUpdatedBy(limit(actor, 160));
        ManualPaymentTask saved = manualPaymentTaskRepository.save(task);
        contractorCapacityService.synchronize(beforeCommitment, saved, balance);
        if (previousKind == ManualPaymentTaskAccountingTargetKind.UNRESOLVED
                && target.kind() != ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
            taskReceiptIntegrationService.bindPendingLegacyReservations(saved, actor, legacyLocks);
            if (historicalProfile) {
                saved.setNeedsReconciliation(true);
                manualPaymentTaskRepository.save(saved);
            }
        }
        return toResponse(saved);
    }

    private ManualPaymentTaskResponse updateStatus(
            ManualPaymentTask task,
            String status,
            String actor,
            ContractorAllocationMode accountingMode
    ) {
        ManualPaymentTaskStatus newStatus = parseStatus(status);
        ManualPaymentTaskBalance balance = taskLedgerService.balance(task.getId());
        ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot beforeCommitment =
                contractorCapacityService.snapshot(task, balance);
        if (newStatus == ManualPaymentTaskStatus.CANCELED
                || newStatus == ManualPaymentTaskStatus.COMPLETED) {
            if (balance.pendingAmountKopecks() > 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Нельзя закрыть задание, пока по нему есть активные резервы"
                );
            }
            if (newStatus == ManualPaymentTaskStatus.COMPLETED
                    && balance.netConfirmedAmountKopecks() < task.getTargetAmountKopecks()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Нельзя завершить задание до подтверждения полной суммы"
                );
            }
        }
        if (newStatus == ManualPaymentTaskStatus.ACTIVE) {
            validatePaymentTarget(task);
            validateAccountingTargetForActivation(task, accountingMode);
            contractorCapacityService.requireActivationCovered(task, balance, accountingMode);
        }
        task.setStatus(newStatus);
        task.setUpdatedBy(limit(actor, 160));
        task.setGeneration(nextGeneration(task.getGeneration()));
        if (newStatus == ManualPaymentTaskStatus.COMPLETED) {
            task.setCompletedAt(task.getCompletedAt() == null ? LocalDateTime.now() : task.getCompletedAt());
        } else if (newStatus == ManualPaymentTaskStatus.ACTIVE) {
            task.setCompletedAt(null);
        }
        ManualPaymentTask saved = manualPaymentTaskRepository.save(task);
        contractorCapacityService.synchronize(beforeCommitment, saved, balance);
        return toResponse(saved);
    }

    private void assertTaskOwner(Long userId, ManualPaymentTask task) {
        Manager manager = task.getManager();
        Long ownerUserId = manager == null || manager.getUser() == null ? null : manager.getUser().getId();
        if (userId == null || !userId.equals(ownerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Можно менять только свои платежные задания");
        }
    }

    private ManualPaymentTask taskById(Long taskId) {
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежное задание не найдено");
        }
        return manualPaymentTaskRepository.findByIdWithDetails(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежное задание не найдено"));
    }

    private ManualPaymentTask taskByIdForUpdate(Long taskId) {
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежное задание не найдено");
        }
        return manualPaymentTaskRepository.findByIdWithDetailsForUpdate(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Платежное задание не найдено"));
    }

    private Manager managerByUserId(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден");
        }
        return managerRepository.findByUserIdWithPaymentProfile(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Профиль менеджера не найден"));
    }

    private Manager managerById(Long managerId) {
        if (managerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите менеджера для задания");
        }
        return managerRepository.findByIdWithPaymentProfile(managerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден"));
    }

    private boolean isRoutable(
            ManualPaymentTask task,
            long amountKopecks,
            Long excludedLinkId,
            ContractorAllocationMode accountingMode
    ) {
        if (task.getStatus() != ManualPaymentTaskStatus.ACTIVE
                || !hasPaymentTarget(task)
                || !hasResolvedAccountingTarget(task, accountingMode)
                || task.getTargetAmountKopecks() <= 0) {
            return false;
        }
        long occupied = taskLedgerService.balance(task.getId()).occupiedAmountKopecks();
        try {
            return Math.addExact(occupied, amountKopecks) <= task.getTargetAmountKopecks();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private ManualPaymentTaskResponse toResponse(ManualPaymentTask task) {
        Long taskId = task.getId();
        ManualPaymentTaskBalance balance = taskLedgerService.balance(taskId);
        long occupied = balance.occupiedAmountKopecks();
        long confirmed = balance.netConfirmedAmountKopecks();
        long pendingAmount = balance.pendingAmountKopecks();
        long remaining = Math.max(0, task.getTargetAmountKopecks() - occupied);
        Manager manager = task.getManager();
        User user = manager == null ? null : manager.getUser();
        PaymentProfile profile = task.getPaymentProfile();
        ManualPaymentTaskAccountingTargetKind accountingKind = targetKind(task);
        ContractorPaymentProfile targetProfile = task.getAccountingTargetProfile();
        ContractorAllocationMode accountingMode = contractorPaymentAccountingPhaseService.current();
        ManualPaymentTaskContractorCapacityService.TargetCapacity targetCapacity = targetProfile == null
                ? null
                : contractorCapacityService.evaluateTargetSnapshot(
                        targetProfile,
                        accountingMode,
                        contractorCapacityService.snapshot(task, balance),
                        task.getTargetAmountKopecks(),
                        balance.netConfirmedAmountKopecks(),
                        balance.pendingAmountKopecks(),
                        true
                );
        long targetAvailable = targetCapacity == null ? 0L
                : targetCapacity.currentAvailableKopecks();
        long targetOverrun = targetCapacity == null ? 0L
                : targetCapacity.projectedOverrunKopecks();
        return new ManualPaymentTaskResponse(
                task.getId(),
                manager == null ? null : manager.getId(),
                managerTitle(user),
                user == null ? "" : normalize(user.getUsername()),
                profile == null ? null : profile.getId(),
                profile == null ? "" : normalize(profile.getName()),
                task.getStatus() == null ? ManualPaymentTaskStatus.ACTIVE.name() : task.getStatus().name(),
                manualPaymentType(task).name(),
                normalize(task.getManualPhone()),
                recipientOrDefault(task.getManualRecipientName()),
                normalize(task.getManualBankName()),
                paymentUrlForRead(task.getManualPaymentUrl()),
                buttonLabelOrDefault(task.getManualPaymentButtonLabel()),
                task.getTargetAmountKopecks(),
                occupied,
                confirmed,
                pendingAmount,
                remaining,
                balance.pendingCount(),
                normalize(task.getComment()),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt(),
                isRoutableForAnyAmount(task, remaining, accountingMode),
                accountingKind.name(),
                profileId(targetProfile),
                accountingTargetLabel(task),
                accountingKind != ManualPaymentTaskAccountingTargetKind.UNRESOLVED,
                task.getGeneration(),
                task.getRowVersion(),
                balance.redirectedAmountKopecks(),
                balance.releasedAmountKopecks(),
                balance.returnedAmountKopecks(),
                balance.unverifiedConfirmedAmountKopecks(),
                balance.needsReconciliationCount(),
                balance.needsReconciliation(),
                targetAvailable,
                targetOverrun,
                task.getTargetOverrunAcknowledgedAt(),
                normalize(task.getTargetOverrunAcknowledgedBy())
        );
    }

    private boolean isRoutableForAnyAmount(
            ManualPaymentTask task,
            long remaining,
            ContractorAllocationMode accountingMode
    ) {
        return task.getStatus() == ManualPaymentTaskStatus.ACTIVE
                && remaining > 0
                && hasPaymentTarget(task)
                && hasResolvedAccountingTarget(task, accountingMode);
    }

    private long taskAmount(Long taskId, Collection<PaymentLinkStatus> statuses) {
        return taskAmount(taskId, statuses, null);
    }

    private long taskAmount(Long taskId, Collection<PaymentLinkStatus> statuses, Long excludedLinkId) {
        if (taskId == null) {
            return 0;
        }
        long standalone = paymentLinkRepository.sumManualReservedAndConfirmedForTask(
                taskId,
                MANUAL_PAYMENT_METHODS,
                statuses,
                LocalDateTime.now(),
                PaymentLinkStatus.CONFIRMED,
                excludedLinkId
        );
        boolean confirmedOnly = statuses.size() == 1 && statuses.contains(PaymentLinkStatus.CONFIRMED);
        long common = confirmedOnly
                ? commonInvoiceRepository.sumConfirmedPaymentRouteForTask(taskId)
                : commonInvoiceRepository.sumReservedAndConfirmedPaymentRouteForTask(
                        taskId,
                        ACTIVE_COMMON_ROUTE_STATUSES,
                        CommonInvoiceStatus.PAID
                );
        return standalone + common;
    }

    @Transactional(readOnly = true)
    public long commonInvoiceProfileUsageForPeriod(
            Long profileId,
            LocalDateTime from,
            LocalDateTime to
    ) {
        if (profileId == null || from == null || to == null || !from.isBefore(to)) {
            return 0;
        }
        return commonInvoiceRepository.sumReservedAndConfirmedProfilePaymentRoutesForPeriod(
                profileId,
                com.hunt.otziv.payments.model.ManualPaymentSource.PROFILE_MONTHLY_LIMIT,
                from,
                to,
                ACTIVE_COMMON_ROUTE_STATUSES,
                CommonInvoiceStatus.PAID
        );
    }

    private ManualPaymentType manualPaymentType(ManualPaymentTask task) {
        return task.getManualPaymentType() == null ? ManualPaymentType.MOBILE_BANK : task.getManualPaymentType();
    }

    private boolean hasPaymentTarget(ManualPaymentTask task) {
        if (manualPaymentType(task) == ManualPaymentType.MOBILE_BANK) {
            return !normalize(task.getManualPhone()).isBlank()
                    && !normalize(task.getManualRecipientName()).isBlank();
        }
        return !paymentUrlForRead(task.getManualPaymentUrl()).isBlank();
    }

    private void validatePaymentTarget(ManualPaymentTask task) {
        validatePaymentTarget(task, false);
    }

    private void validatePaymentTarget(ManualPaymentTask task, boolean allowPersistedQuarantine) {
        if (manualPaymentType(task) == ManualPaymentType.MOBILE_BANK) {
            if (normalize(task.getManualPhone()).isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите телефон");
            }
            if (normalize(task.getManualRecipientName()).isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите получателя");
            }
            return;
        }
        if (paymentUrlForRead(task.getManualPaymentUrl()).isBlank() && !allowPersistedQuarantine) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите ссылку Альфа-Банка");
        }
    }

    private boolean applyManualPaymentUrlUpdate(
            ManualPaymentTask task,
            UpdateManualPaymentTaskRequest request
    ) {
        String persisted = task.getManualPaymentUrl();
        boolean quarantined = PaymentUrlPolicy.isUnsafeConfigured(
                persisted,
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
        );
        boolean replacementConfirmed = request != null
                && Boolean.TRUE.equals(request.manualPaymentUrlReplacementConfirmed());
        if (quarantined && !replacementConfirmed) {
            // Old clients submit whatever fallback they displayed. Keeping the
            // raw value quarantined prevents a silent recipient replacement.
            return true;
        }
        if (replacementConfirmed) {
            task.setManualPaymentUrl(PaymentUrlPolicy.require(
                    request.manualPaymentUrl(),
                    PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL,
                    HttpStatus.BAD_REQUEST,
                    "Укажите безопасную ссылку ручной оплаты для замены"
            ));
            return false;
        }
        task.setManualPaymentUrl(paymentUrlOrDefault(request == null ? null : request.manualPaymentUrl()));
        return false;
    }

    private void applyTarget(ManualPaymentTask task, TargetResolution target, String actor) {
        task.setAccountingTargetKind(target.kind());
        task.setAccountingTargetProfile(target.profile());
        if (target.acknowledgementUsed()) {
            if (target.acknowledgementRefreshed()) {
                task.setTargetOverrunAcknowledgedAt(LocalDateTime.now());
                task.setTargetOverrunAcknowledgedBy(limit(actor, 160));
                task.setTargetOverrunAcknowledgedKopecks(target.projectedOverrunKopecks());
                task.setTargetCapacityAvailableSnapshotKopecks(target.currentAvailableKopecks());
            }
        } else {
            task.setTargetOverrunAcknowledgedAt(null);
            task.setTargetOverrunAcknowledgedBy(null);
            task.setTargetOverrunAcknowledgedKopecks(null);
            task.setTargetCapacityAvailableSnapshotKopecks(null);
        }
    }

    private void validateAccountingTargetForActivation(
            ManualPaymentTask task,
            ContractorAllocationMode accountingMode
    ) {
        if (!hasResolvedAccountingTarget(task, accountingMode)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Привяжите точного получателя платёжного задания"
            );
        }
    }

    private boolean hasResolvedAccountingTarget(
            ManualPaymentTask task,
            ContractorAllocationMode accountingMode
    ) {
        ManualPaymentTaskAccountingTargetKind kind = targetKind(task);
        if (kind == ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
            return false;
        }
        if (kind == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                || kind == ManualPaymentTaskAccountingTargetKind.MANAGER) {
            ContractorPaymentProfile profile = task.getAccountingTargetProfile();
            ContractorRole expected = kind == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                    ? ContractorRole.SPECIALIST : ContractorRole.MANAGER;
            return profile != null
                    && profile.isEnabled()
                    && (accountingMode != ContractorAllocationMode.LIVE || profile.isLiveEnabled())
                    && profile.getUser() != null
                    && profile.getUser().getId() != null
                    && profile.getRole() == expected;
        }
        return task.getAccountingTargetProfile() == null;
    }

    private ManualPaymentTaskAccountingTargetKind targetKind(ManualPaymentTask task) {
        return task.getAccountingTargetKind() == null
                ? ManualPaymentTaskAccountingTargetKind.UNRESOLVED
                : task.getAccountingTargetKind();
    }

    private String accountingTargetLabel(ManualPaymentTask task) {
        return switch (targetKind(task)) {
            case UNRESOLVED -> "Получатель задания не привязан";
            case EXTERNAL_TASK -> recipientOrDefault(task.getManualRecipientName());
            case OWNER -> "Владелец";
            case SPECIALIST, MANAGER -> {
                ContractorPaymentProfile profile = task.getAccountingTargetProfile();
                User targetUser = profile == null ? null : profile.getUser();
                String fio = targetUser == null ? "" : normalize(targetUser.getFio());
                yield fio.isBlank() && targetUser != null ? normalize(targetUser.getUsername()) : fio;
            }
        };
    }

    private long saturatedSubtract(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            return left >= 0 && right < 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private Long profileId(ContractorPaymentProfile profile) {
        return profile == null ? null : profile.getId();
    }

    private long nextGeneration(long current) {
        try {
            return Math.addExact(current, 1);
        } catch (ArithmeticException overflow) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Исчерпан номер поколения платёжного задания"
            );
        }
    }

    private ManualPaymentTaskStatus parseStatus(String value) {
        String clean = normalize(value).toUpperCase(Locale.ROOT);
        if (clean.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите статус задания");
        }
        try {
            return ManualPaymentTaskStatus.valueOf(clean);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный статус задания", e);
        }
    }

    private long requiredPositive(Long value) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите сумму задания");
        }
        return value;
    }

    private String requiredCreationOperationKey(String value) {
        String clean = normalize(value);
        if (clean.isBlank()
                || clean.length() > 160
                || !clean.matches("[A-Za-z0-9:._-]+")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Обновите приложение и повторите создание платёжного задания"
            );
        }
        return clean.toLowerCase(Locale.ROOT);
    }

    private String creationPayloadHash(
            Manager manager,
            CreateManualPaymentTaskRequest request,
            boolean managerScoped
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, managerScoped);
            updateDigest(digest, manager == null ? null : manager.getId());
            updateDigest(digest, request == null ? null : normalize(request.manualPaymentType()).toUpperCase(Locale.ROOT));
            updateDigest(digest, request == null ? null : normalize(request.manualPhone()));
            updateDigest(digest, request == null ? null : normalize(request.manualRecipientName()));
            updateDigest(digest, request == null ? null : normalize(request.manualBankName()));
            updateDigest(digest, request == null ? null : normalize(request.manualPaymentUrl()));
            updateDigest(digest, request == null ? null : normalize(request.manualPaymentButtonLabel()));
            updateDigest(digest, request == null ? null : request.targetAmountKopecks());
            updateDigest(digest, request == null ? null : normalize(request.comment()));
            updateDigest(digest, request == null ? null : normalize(request.accountingTargetKind()).toUpperCase(Locale.ROOT));
            updateDigest(digest, request == null ? null : request.accountingTargetProfileId());
            updateDigest(digest, request != null && Boolean.TRUE.equals(
                    request.accountingTargetOverrunAcknowledged()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 недоступен", impossible);
        }
    }

    private void updateDigest(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private YearMonth parseMonth(String value) {
        String clean = normalize(value);
        if (clean.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(clean);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите месяц в формате YYYY-MM", e);
        }
    }

    private BigDecimal amountRubles(long amountKopecks) {
        return BigDecimal.valueOf(amountKopecks, 2);
    }

    private String moneyRubles(long kopecks) {
        return String.format(Locale.ROOT, "%.2f руб.", kopecks / 100.0);
    }

    private ManualPaymentType parseManualPaymentType(String value) {
        String clean = normalize(value).toUpperCase(Locale.ROOT);
        if (clean.isBlank()) {
            return ManualPaymentType.MOBILE_BANK;
        }
        try {
            return ManualPaymentType.valueOf(clean);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный тип ручной оплаты", e);
        }
    }

    private String paymentUrlOrDefault(String value) {
        return PaymentUrlPolicy.requireOrDefault(
                value,
                ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL,
                HttpStatus.BAD_REQUEST,
                "Ссылка ручной оплаты должна использовать http или https"
        );
    }

    private String paymentUrlForRead(String value) {
        return PaymentUrlPolicy.safeOrDefault(
                value,
                ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
        );
    }

    private String buttonLabelOrDefault(String value) {
        String clean = limit(value, 80);
        return clean.isBlank() ? ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_BUTTON_LABEL : clean;
    }

    private String recipientOrDefault(String value) {
        String clean = limit(value, 160);
        return clean.isBlank() || ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_BUTTON_LABEL.equals(clean)
                ? ManualPaymentType.DEFAULT_MANUAL_RECIPIENT_NAME
                : clean;
    }

    private String managerTitle(User user) {
        String fio = user == null ? "" : normalize(user.getFio());
        return fio.isBlank() ? (user == null ? "" : normalize(user.getUsername())) : fio;
    }

    private String limit(String value, int maxLength) {
        String clean = normalize(value);
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
