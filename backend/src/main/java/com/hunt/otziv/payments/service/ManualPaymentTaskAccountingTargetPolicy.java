package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentProfileService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentAccountingPhaseService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.payments.dto.ManualPaymentTaskAccountingTargetOption;
import com.hunt.otziv.payments.dto.ManualPaymentTaskBalance;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Authorization and limit preview for typed task accounting targets. */
@Service
@RequiredArgsConstructor
public class ManualPaymentTaskAccountingTargetPolicy {

    private final ContractorPaymentProfileRepository profileRepository;
    private final ManagerRepository managerRepository;
    private final WorkerRepository workerRepository;
    private final ManualPaymentTaskRepository taskRepository;
    private final ManualPaymentTaskLedgerService ledgerService;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    private final ManualPaymentTaskContractorCapacityService capacityService;

    @Transactional(readOnly = true)
    public List<ManualPaymentTaskAccountingTargetOption> managementOptions(
            Long managerId,
            Long targetAmountKopecks,
            Long taskId
    ) {
        long target = nonNegative(targetAmountKopecks);
        boolean legacyRemediation = legacyRemediationPreview(taskId);
        List<ContractorPaymentProfile> profiles = (legacyRemediation
                ? profileRepository.findAllWithUser()
                : profileRepository.findAllEnabledWithUser()).stream()
                .filter(profile -> profile.getUser() != null
                        && targetAccessPolicy.canManageUser(profile.getUser().getId()))
                .toList();
        return options(profiles, target, taskId, legacyRemediation, recommendedManagerUserId(managerId));
    }

    @Transactional(readOnly = true)
    public List<ManualPaymentTaskAccountingTargetOption> managerOptions(
            Manager manager,
            Long targetAmountKopecks,
            Long taskId
    ) {
        if (manager == null || manager.getId() == null || manager.getUser() == null
                || manager.getUser().getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Профиль менеджера не найден");
        }
        ManualPaymentTask existingTask = null;
        if (taskId != null) {
            existingTask = taskRepository.findByIdWithDetails(taskId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Платёжное задание не найдено"));
            if (existingTask.getManager() == null
                    || !manager.getId().equals(existingTask.getManager().getId())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Можно просматривать только свои платёжные задания");
            }
        }
        boolean legacyRemediation = legacyRemediationPreview(existingTask);
        Set<Long> allowedUserIds = new HashSet<>();
        allowedUserIds.add(manager.getUser().getId());
        allowedUserIds.addAll(workerRepository.findUserIdsByManagerIds(Set.of(manager.getId())));
        List<ContractorPaymentProfile> profiles = (legacyRemediation
                ? profileRepository.findAllByUserIds(allowedUserIds)
                : profileRepository.findAllEnabledByUserIds(allowedUserIds))
                .stream()
                .filter(profile -> allowedForManager(profile, manager, allowedUserIds))
                .toList();
        return options(profiles, nonNegative(targetAmountKopecks), taskId, legacyRemediation,
                manager.getUser().getId());
    }

    @Transactional
    public TargetResolution resolveForManagement(
            String kindValue,
            Long profileId,
            long targetAmountKopecks,
            boolean overrunAcknowledged,
            Long existingTaskId
    ) {
        return resolveForManagement(kindValue, profileId, targetAmountKopecks,
                overrunAcknowledged, existingTaskId, false);
    }

    @Transactional
    public TargetResolution resolveForManagement(
            String kindValue,
            Long profileId,
            long targetAmountKopecks,
            boolean overrunAcknowledged,
            Long existingTaskId,
            boolean legacyRemediation
    ) {
        return resolve(null, null, kindValue, profileId, targetAmountKopecks,
                overrunAcknowledged, existingTaskId, legacyRemediation);
    }

    @Transactional
    public TargetResolution resolveForManager(
            Manager manager,
            String kindValue,
            Long profileId,
            long targetAmountKopecks,
            boolean overrunAcknowledged,
            Long existingTaskId
    ) {
        return resolveForManager(manager, kindValue, profileId, targetAmountKopecks,
                overrunAcknowledged, existingTaskId, false);
    }

    @Transactional
    public TargetResolution resolveForManager(
            Manager manager,
            String kindValue,
            Long profileId,
            long targetAmountKopecks,
            boolean overrunAcknowledged,
            Long existingTaskId,
            boolean legacyRemediation
    ) {
        if (manager == null || manager.getId() == null || manager.getUser() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Профиль менеджера не найден");
        }
        Set<Long> allowed = new HashSet<>();
        allowed.add(manager.getUser().getId());
        allowed.addAll(workerRepository.findUserIdsByManagerIds(Set.of(manager.getId())));
        return resolve(manager, allowed, kindValue, profileId, targetAmountKopecks,
                overrunAcknowledged, existingTaskId, legacyRemediation);
    }

    private TargetResolution resolve(
            Manager manager,
            Set<Long> allowedUserIds,
            String kindValue,
            Long profileId,
            long targetAmountKopecks,
            boolean overrunAcknowledged,
            Long existingTaskId,
            boolean legacyRemediation
    ) {
        ManualPaymentTaskAccountingTargetKind kind = parse(kindValue);
        if (kind == ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
            throw badRequest("Выберите, кому учитывать оплату задания");
        }
        if (kind == ManualPaymentTaskAccountingTargetKind.OWNER
                || kind == ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK) {
            if (profileId != null) {
                throw badRequest("Для этого получателя профиль сотрудника не указывается");
            }
            return new TargetResolution(kind, null, targetLabel(kind, null),
                    0, 0, false, false);
        }
        if (profileId == null || profileId <= 0) {
            throw badRequest("Выберите точный профиль менеджера или специалиста");
        }
        ContractorAllocationMode accountingMode = accountingPhaseService.lockCurrent();
        ContractorPaymentProfile profile = profileRepository.findByIdForUpdate(profileId)
                .orElseThrow(() -> badRequest("Профиль получателя не найден"));
        targetAccessPolicy.requireCanManageUser(
                profile.getUser() == null ? null : profile.getUser().getId());
        boolean historicalProfile = !eligible(profile, accountingMode);
        if ((!legacyRemediation && historicalProfile)
                || profile.getUser() == null || profile.getUser().getId() == null) {
            throw conflict("Профиль получателя отключён");
        }
        requireRole(kind, profile);
        if (manager != null && !allowedForManager(profile, manager, allowedUserIds)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Этот получатель не входит в доступную команду менеджера");
        }
        ExistingTaskExposure exposure = existingTaskExposure(existingTaskId);
        boolean pendingAlreadyBacked = Objects.equals(profile.getId(), exposure.profileId())
                && exposure.commitmentSnapshot().profileId() != null;
        ManualPaymentTaskContractorCapacityService.TargetCapacity capacity =
                capacityService.evaluateTarget(
                        profile,
                        accountingMode,
                        exposure.commitmentSnapshot(),
                        targetAmountKopecks,
                        exposure.netConfirmedAmountKopecks(),
                        exposure.pendingAmountKopecks(),
                        pendingAlreadyBacked
                );
        long overrun = capacity.projectedOverrunKopecks();
        boolean exactPersistedAcknowledgement = exposure.sameExactTarget(
                kind, profile.getId(), targetAmountKopecks)
                && exposure.commitmentSnapshot().acknowledgedOverrunKopecks() >= overrun;
        if (overrun > 0 && !overrunAcknowledged && !exactPersistedAcknowledgement) {
            throw conflict("Задание превысит доступный лимит получателя на "
                    + String.format(Locale.ROOT, "%.2f руб.", overrun / 100.0));
        }
        return new TargetResolution(
                kind,
                profile,
                targetLabel(kind, profile, historicalProfile),
                capacity.currentAvailableKopecks(),
                overrun,
                overrun > 0,
                overrun > 0 && overrunAcknowledged
        );
    }

    private List<ManualPaymentTaskAccountingTargetOption> options(
            List<ContractorPaymentProfile> profiles,
            long target,
            Long taskId,
            boolean legacyRemediation,
            Long recommendedManagerUserId
    ) {
        List<ManualPaymentTaskAccountingTargetOption> result = new ArrayList<>();
        result.add(option(ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK, null,
                target, 0, 0, true, false, false));
        result.add(option(ManualPaymentTaskAccountingTargetKind.OWNER, null,
                target, 0, 0, true, false, false));
        ContractorAllocationMode accountingMode = accountingPhaseService.current();
        ExistingTaskExposure exposure = existingTaskExposure(taskId);
        for (ContractorPaymentProfile profile : profiles) {
            ManualPaymentTaskAccountingTargetKind kind = profile.getRole() == ContractorRole.SPECIALIST
                    ? ManualPaymentTaskAccountingTargetKind.SPECIALIST
                    : ManualPaymentTaskAccountingTargetKind.MANAGER;
            boolean pendingAlreadyBacked = Objects.equals(profile.getId(), exposure.profileId())
                    && exposure.commitmentSnapshot().profileId() != null;
            ManualPaymentTaskContractorCapacityService.TargetCapacity capacity =
                    capacityService.evaluateTargetSnapshot(
                            profile,
                            accountingMode,
                            exposure.commitmentSnapshot(),
                            target,
                            exposure.netConfirmedAmountKopecks(),
                            exposure.pendingAmountKopecks(),
                            pendingAlreadyBacked
                    );
            boolean exactPersisted = exposure.sameExactTarget(kind, profile.getId(), target)
                    && exposure.commitmentSnapshot().acknowledgedOverrunKopecks()
                    >= capacity.projectedOverrunKopecks();
            boolean historicalProfile = !eligible(profile, accountingMode);
            result.add(option(
                    kind,
                    profile,
                    target,
                    capacity.currentAvailableKopecks(),
                    capacity.projectedOverrunKopecks(),
                    !historicalProfile || legacyRemediation,
                    capacity.projectedOverrunKopecks() > 0 && !exactPersisted,
                    historicalProfile,
                    recommendedManagerProfile(kind, profile, recommendedManagerUserId)
            ));
        }
        return List.copyOf(result);
    }

    private Long recommendedManagerUserId(Long managerId) {
        if (managerId == null || managerId <= 0) {
            return null;
        }
        return managerRepository.findByIdWithUser(managerId)
                .map(Manager::getUser)
                .map(com.hunt.otziv.u_users.model.User::getId)
                .orElse(null);
    }

    private boolean recommendedManagerProfile(
            ManualPaymentTaskAccountingTargetKind kind,
            ContractorPaymentProfile profile,
            Long managerUserId
    ) {
        return kind == ManualPaymentTaskAccountingTargetKind.MANAGER
                && managerUserId != null
                && profile != null
                && profile.getUser() != null
                && managerUserId.equals(profile.getUser().getId());
    }

    private ExistingTaskExposure existingTaskExposure(Long taskId) {
        if (taskId == null || taskId <= 0) {
            return ExistingTaskExposure.NONE;
        }
        ManualPaymentTask task = taskRepository.findByIdWithDetails(taskId).orElse(null);
        if (task == null) {
            return ExistingTaskExposure.NONE;
        }
        ManualPaymentTaskBalance balance = ledgerService.balance(taskId);
        Long profileId = task.getAccountingTargetProfile() == null
                ? null : task.getAccountingTargetProfile().getId();
        return new ExistingTaskExposure(
                profileId,
                Math.max(0, balance.pendingAmountKopecks()),
                Math.max(0, balance.netConfirmedAmountKopecks()),
                task.getAccountingTargetKind(),
                task.getTargetAmountKopecks(),
                capacityService.snapshot(task, balance)
        );
    }

    private boolean legacyRemediationPreview(Long taskId) {
        if (taskId == null || taskId <= 0) {
            return false;
        }
        return legacyRemediationPreview(taskRepository.findByIdWithDetails(taskId).orElse(null));
    }

    private boolean legacyRemediationPreview(ManualPaymentTask task) {
        if (task == null || task.getId() == null
                || task.getAccountingTargetKind()
                != ManualPaymentTaskAccountingTargetKind.UNRESOLVED) {
            return false;
        }
        List<com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef> sources =
                ledgerService.pendingUnresolvedSources(task.getId());
        return !sources.isEmpty() && sources.stream().allMatch(source -> source != null
                && source.sourceKind() != null
                && source.sourceId() != null
                && Objects.equals(source.sourceGeneration(), "LEGACY-" + source.sourceId()));
    }

    private boolean eligible(ContractorPaymentProfile profile, ContractorAllocationMode mode) {
        return profile != null
                && profile.isEnabled()
                && (mode != ContractorAllocationMode.LIVE || profile.isLiveEnabled());
    }

    private ManualPaymentTaskAccountingTargetOption option(
            ManualPaymentTaskAccountingTargetKind kind,
            ContractorPaymentProfile profile,
            long target,
            long available,
            long overrun,
            boolean enabled,
            boolean needsAcknowledgement,
            boolean recommended
    ) {
        return option(kind, profile, target, available, overrun, enabled,
                needsAcknowledgement, false, recommended);
    }

    private ManualPaymentTaskAccountingTargetOption option(
            ManualPaymentTaskAccountingTargetKind kind,
            ContractorPaymentProfile profile,
            long target,
            long available,
            long overrun,
            boolean enabled,
            boolean needsAcknowledgement,
            boolean historicalProfile,
            boolean recommended
    ) {
        Long profileId = profile == null ? null : profile.getId();
        Long userId = profile == null || profile.getUser() == null ? null : profile.getUser().getId();
        return new ManualPaymentTaskAccountingTargetOption(
                optionKey(kind, profileId),
                kind,
                profileId,
                userId,
                profile == null || profile.getRole() == null ? "" : profile.getRole().name(),
                targetLabel(kind, profile, historicalProfile),
                enabled,
                available,
                target,
                overrun,
                needsAcknowledgement,
                recommended
        );
    }

    private boolean allowedForManager(
            ContractorPaymentProfile profile,
            Manager manager,
            Set<Long> allowedUserIds
    ) {
        if (profile == null || profile.getUser() == null || profile.getUser().getId() == null
                || !allowedUserIds.contains(profile.getUser().getId())) {
            return false;
        }
        if (profile.getRole() == ContractorRole.MANAGER) {
            return manager.getUser().getId().equals(profile.getUser().getId());
        }
        return profile.getRole() == ContractorRole.SPECIALIST
                && !manager.getUser().getId().equals(profile.getUser().getId());
    }

    private void requireRole(
            ManualPaymentTaskAccountingTargetKind kind,
            ContractorPaymentProfile profile
    ) {
        ContractorRole expected = kind == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                ? ContractorRole.SPECIALIST : ContractorRole.MANAGER;
        if (profile.getRole() != expected) {
            throw badRequest("Роль выбранного профиля не совпадает с типом получателя");
        }
    }

    private ManualPaymentTaskAccountingTargetKind parse(String value) {
        String clean = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (clean.isBlank()) {
            throw badRequest("Выберите, кому учитывать оплату задания");
        }
        try {
            return ManualPaymentTaskAccountingTargetKind.valueOf(clean);
        } catch (IllegalArgumentException exception) {
            throw badRequest("Некорректный тип получателя задания");
        }
    }

    private String optionKey(ManualPaymentTaskAccountingTargetKind kind, Long profileId) {
        return profileId == null ? "TASK_TARGET:" + kind.name()
                : "TASK_TARGET:" + kind.name() + ":" + profileId;
    }

    private String targetLabel(
            ManualPaymentTaskAccountingTargetKind kind,
            ContractorPaymentProfile profile
    ) {
        return targetLabel(kind, profile, false);
    }

    private String targetLabel(
            ManualPaymentTaskAccountingTargetKind kind,
            ContractorPaymentProfile profile,
            boolean historicalProfile
    ) {
        String label = switch (kind) {
            case UNRESOLVED -> "Получатель задания не привязан";
            case EXTERNAL_TASK -> "Получатель задания (учёт только в задании)";
            case OWNER -> "Владелец";
            case SPECIALIST, MANAGER -> {
                String fio = profile == null || profile.getUser() == null
                        ? "" : normalize(profile.getUser().getFio());
                yield fio.isBlank() && profile != null && profile.getUser() != null
                        ? normalize(profile.getUser().getUsername()) : fio;
            }
        };
        return historicalProfile && profile != null
                ? label + " (исторический/отключённый профиль)" : label;
    }

    private long nonNegative(Long value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private long subtractSaturated(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            return left >= 0 && right < 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
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

    public record TargetResolution(
            ManualPaymentTaskAccountingTargetKind kind,
            ContractorPaymentProfile profile,
            String label,
            long currentAvailableKopecks,
            long projectedOverrunKopecks,
            boolean acknowledgementUsed,
            boolean acknowledgementRefreshed
    ) {
    }

    private record ExistingTaskExposure(
            Long profileId,
            long pendingAmountKopecks,
            long netConfirmedAmountKopecks,
            ManualPaymentTaskAccountingTargetKind kind,
            long targetAmountKopecks,
            ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot commitmentSnapshot
    ) {
        private static final ExistingTaskExposure NONE = new ExistingTaskExposure(
                null,
                0,
                0,
                ManualPaymentTaskAccountingTargetKind.UNRESOLVED,
                0,
                ManualPaymentTaskContractorCapacityService.TaskCommitmentSnapshot.NONE
        );

        private boolean sameExactTarget(
                ManualPaymentTaskAccountingTargetKind proposedKind,
                Long proposedProfileId,
                long proposedTargetAmountKopecks
        ) {
            return kind == proposedKind
                    && Objects.equals(profileId, proposedProfileId)
                    && targetAmountKopecks == proposedTargetAmountKopecks;
        }
    }
}
