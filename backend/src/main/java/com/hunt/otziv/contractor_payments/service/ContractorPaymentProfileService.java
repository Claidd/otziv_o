package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentProfileAdjustmentResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentProfileRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentProfileResponse;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAmountLimits;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfileAdjustment;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileAdjustmentRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardLedgerRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ContractorPaymentProfileService {

    private static final int RECIPIENT_NAME_MAX_LENGTH = 160;
    private static final int PAYMENT_PHONE_MAX_LENGTH = 32;
    private static final int BANK_NAME_MAX_LENGTH = 120;
    private static final int PAYMENT_COMMENT_MAX_LENGTH = 255;

    private static final Set<ContractorAllocationStatus> RESERVED_STATUSES = EnumSet.of(
            ContractorAllocationStatus.RESERVED,
            ContractorAllocationStatus.CLIENT_REPORTED,
            ContractorAllocationStatus.PARTIALLY_CONFIRMED
    );
    private static final Set<ContractorAllocationStatus> PURE_RESERVED_STATUSES = EnumSet.of(
            ContractorAllocationStatus.RESERVED
    );
    private static final Set<ContractorAllocationStatus> CLIENT_REPORTED_STATUSES = EnumSet.of(
            ContractorAllocationStatus.CLIENT_REPORTED
    );
    private static final Set<ContractorAllocationStatus> PARTIALLY_CONFIRMED_STATUSES = EnumSet.of(
            ContractorAllocationStatus.PARTIALLY_CONFIRMED
    );

    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorRewardLedgerRepository rewardLedgerRepository;
    private final ContractorRewardLedgerService ledgerService;
    private final ContractorPaymentAccountingService accountingService;
    private final ContractorPaymentProfileAdjustmentRepository adjustmentRepository;
    private final UserRepository userRepository;
    private final ZpRepository zpRepository;
    private final AppSettingService appSettingService;
    private final ContractorPaymentRuntimeSwitch runtimeSwitch;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final BusinessAuditService businessAuditService;
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    private final ContractorRewardInitialMonthSyncCoordinator initialMonthSyncCoordinator;

    @Value("${otziv.contractor-payments.business-zone:Asia/Irkutsk}")
    private String businessZoneId;

    @Transactional
    public List<ContractorPaymentProfileResponse> getForUser(Long userId) {
        // Authorize before any existence lookup or financial mutex. Otherwise a
        // restricted OWNER can distinguish a missing user from a concealed
        // privileged target and can make unauthorized requests contend on the
        // target's eligibility/profile rows.
        targetAccessPolicy.requireCanManageUser(userId);
        User user = userRepository.findByIdWithAssignments(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        CurrentContractorEligibility eligibility = lockCurrentEligibility(userId);
        ensureProfiles(user, eligibility);
        return profileRepository.findAllByUserIdForUpdate(userId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public void ensureForUser(Long userId) {
        User user = userRepository.findByIdWithAssignments(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        ensureProfiles(user, lockCurrentEligibility(userId));
    }

    @Transactional
    public ContractorPaymentProfileResponse update(Long userId, ContractorPaymentProfileRequest request) {
        targetAccessPolicy.requireCanManageUser(userId);
        User user = userRepository.findByIdWithAssignments(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        CurrentContractorEligibility eligibility = lockCurrentEligibility(userId);
        Optional<ContractorPaymentProfile> existingProfile = profileRepository
                .findByUserIdAndRoleForUpdate(userId, request.role());
        if (existingProfile.isEmpty()) {
            requireRole(eligibility, request.role());
        }
        ContractorPaymentProfile profile = existingProfile.orElseGet(() -> newProfile(user, request.role()));
        boolean liveRoutingBeingDisabled = existingProfile.isPresent()
                && profile.isLiveEnabled()
                && !request.liveEnabled()
                // Do not let a corrupt historical state use the OFF path to
                // enable the base profile at the same time.
                && (!request.enabled() || profile.isEnabled());
        if ((request.enabled() || request.liveEnabled())
                && !liveRoutingBeingDisabled
                && !eligibility.active()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя включить платёжный профиль неактивного пользователя"
            );
        }
        if ((request.enabled() || request.liveEnabled())
                && !liveRoutingBeingDisabled
                && !eligibility.roles().contains(request.role())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя включить платёжный профиль без текущей роли пользователя"
            );
        }
        if (profile.getRowVersion() != request.expectedVersion()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платёжный профиль уже изменён другим пользователем. Данные обновлены — повторите изменение"
            );
        }
        long oldOpeningBalance = profile.getOpeningBalanceKopecks();
        boolean openingChanged = oldOpeningBalance != request.openingBalanceKopecks();
        boolean profileEligibilityBeingReduced = liveRoutingBeingDisabled
                || (existingProfile.isPresent() && profile.isEnabled() && !request.enabled());
        boolean openingBalanceInvalid = request.openingBalanceKopecks() < 0L
                || request.openingBalanceKopecks() > ContractorPaymentAmountLimits.MAX_AMOUNT_KOPECKS;
        if (openingBalanceInvalid && (!profileEligibilityBeingReduced || openingChanged)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Остаток превышает допустимый предел");
        }
        String recipientName = normalize(request.recipientName());
        String paymentPhone = ContractorPaymentTransferNumber.normalize(request.paymentPhone());
        String bankName = normalize(request.bankName());
        String paymentComment = normalize(request.paymentComment());
        validateRequisiteLength(
                "ФИО получателя",
                profile.getRecipientName(),
                recipientName,
                RECIPIENT_NAME_MAX_LENGTH,
                profileEligibilityBeingReduced
        );
        validateRequisiteLength(
                "Номер телефона или карты получателя",
                ContractorPaymentTransferNumber.normalize(profile.getPaymentPhone()),
                paymentPhone,
                PAYMENT_PHONE_MAX_LENGTH,
                profileEligibilityBeingReduced
        );
        validateRequisiteLength(
                "Название банка",
                profile.getBankName(),
                bankName,
                BANK_NAME_MAX_LENGTH,
                profileEligibilityBeingReduced
        );
        validateRequisiteLength(
                "Комментарий к платежу",
                profile.getPaymentComment(),
                paymentComment,
                PAYMENT_COMMENT_MAX_LENGTH,
                profileEligibilityBeingReduced
        );
        if (request.enabled()
                && !liveRoutingBeingDisabled
                && (recipientName.isBlank() || paymentPhone.isBlank() || bankName.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Для включения платёжного профиля укажите ФИО получателя, номер телефона или карты и банк"
            );
        }
        if (request.enabled()
                && !liveRoutingBeingDisabled
                && !ContractorPaymentTransferNumber.isValid(paymentPhone)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Номер для перевода должен быть телефоном (10–15 цифр) или картой (16–19 цифр)"
            );
        }
        if (request.liveEnabled() && !request.enabled()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Допуск к реальным счетам можно включить только для активного платёжного профиля"
            );
        }
        Map<String, Object> oldAudit = new LinkedHashMap<>();
        Map<String, Object> newAudit = new LinkedHashMap<>();
        List<String> changedFields = new ArrayList<>();
        collectChange(changedFields, oldAudit, newAudit, "enabled", profile.isEnabled(), request.enabled());
        collectChange(
                changedFields,
                oldAudit,
                newAudit,
                "liveEnabled",
                profile.isLiveEnabled(),
                request.liveEnabled()
        );
        collectSecretChange(changedFields, "recipientName", profile.getRecipientName(), recipientName);
        collectSecretChange(
                changedFields,
                "paymentPhone",
                ContractorPaymentTransferNumber.normalize(profile.getPaymentPhone()),
                paymentPhone
        );
        collectSecretChange(changedFields, "bankName", profile.getBankName(), bankName);
        collectSecretChange(changedFields, "paymentComment", profile.getPaymentComment(), paymentComment);
        collectChange(changedFields, oldAudit, newAudit, "openingBalanceKopecks",
                oldOpeningBalance, request.openingBalanceKopecks());

        profile.setEnabled(request.enabled());
        profile.setLiveEnabled(request.liveEnabled());
        profile.setRecipientName(recipientName);
        profile.setPaymentPhone(paymentPhone);
        profile.setBankName(bankName);
        profile.setPaymentComment(paymentComment);
        profile.setOpeningBalanceKopecks(request.openingBalanceKopecks());
        ContractorPaymentProfile saved = profileRepository.saveAndFlush(profile);
        if (openingChanged) {
            recordOpeningAdjustment(saved, oldOpeningBalance, request);
        }
        if (!changedFields.isEmpty()) {
            businessAuditService.recordRequiredInCurrentTransaction(
                    "UPDATE_CONTRACTOR_PAYMENT_PROFILE",
                    "CONTRACTOR_PAYMENT_PROFILE",
                    saved.getId(),
                    null,
                    null,
                    oldAudit,
                    newAudit,
                    "userId=" + userId + ", role=" + saved.getRole()
                            + ", changedFields=" + String.join(",", changedFields)
            );
        }
        if (saved.isEnabled()) {
            // The import acquires ZP locks before the profile lock. Schedule it
            // after this profile transaction commits to preserve that order.
            initialMonthSyncCoordinator.request(saved.getId());
        }
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ContractorPaymentProfileAdjustmentResponse> openingBalanceHistory(
            Long userId,
            Long profileId
    ) {
        targetAccessPolicy.requireCanManageUser(userId);
        ContractorPaymentProfile profile = profileRepository.findById(profileId)
                .filter(value -> value.getUser() != null && Objects.equals(value.getUser().getId(), userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платёжный профиль не найден"));
        return adjustmentRepository.findAllByProfileIdOrderByEffectiveAtDescIdDesc(profile.getId()).stream()
                .map(this::toAdjustmentResponse)
                .toList();
    }

    public long reserved(ContractorPaymentProfile profile, ContractorAllocationMode mode) {
        return allocationRepository.sumOutstandingExposure(profile.getId(), mode, RESERVED_STATUSES);
    }

    public long received(ContractorPaymentProfile profile) {
        return accountingService.netReceived(profile, ContractorAllocationMode.LIVE);
    }

    public long simulatedReceived(ContractorPaymentProfile profile) {
        return accountingService.netReceived(profile, ContractorAllocationMode.SHADOW);
    }

    public long available(ContractorPaymentProfile profile, ContractorAllocationMode mode) {
        if (profile == null || profile.getId() == null || mode == null) {
            return 0L;
        }
        // Routing callers hold the profile PESSIMISTIC_WRITE mutex before
        // entering here. Plain aggregate queries could still use an older
        // REPEATABLE READ snapshot created earlier in the transaction. Entity
        // locking reads are current reads, so a transaction that waited for
        // this profile sees every committed accrual, confirmation, return and
        // reservation before deciding whether another invoice fits.
        long accrued = Math.addExact(
                profile.getOpeningBalanceKopecks(),
                rewardLedgerRepository.sumActiveForCapacityUpdate(profile.getId())
        );
        ContractorPaymentAllocationRepository.CapacityTotals totals = allocationRepository
                .capacityTotalsForUpdate(profile.getId(), mode.name());
        long confirmed = totals == null ? 0L : totals.safeConfirmedKopecks();
        long returned = totals == null ? 0L : totals.safeReturnedKopecks();
        long outstanding = totals == null ? 0L : totals.safeOutstandingKopecks();
        long paid = Math.max(0L, Math.subtractExact(confirmed, returned));
        long debt = Math.max(0L, Math.subtractExact(accrued, paid));
        return Math.max(0L, Math.subtractExact(debt, outstanding));
    }

    private ContractorPaymentProfileResponse toResponse(ContractorPaymentProfile profile) {
        LocalDate today = LocalDate.now(businessZone());
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate nextMonth = monthStart.plusMonths(1);
        LocalDateTime monthStartTime = monthStart.atStartOfDay();
        LocalDateTime nextMonthTime = nextMonth.atStartOfDay();
        long accruedTotal = ledgerService.totalAccrued(profile);
        boolean shadowMode = appSettingService.getBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED,
                true
        );
        boolean liveRouting = runtimeSwitch.status().liveRoutingEnabled();
        ContractorAllocationMode balanceMode = accountingPhaseService.current();
        long reserved = allocationRepository.sumOutstandingExposure(
                profile.getId(), balanceMode, PURE_RESERVED_STATUSES
        );
        long clientReported = allocationRepository.sumOutstandingExposure(
                profile.getId(), balanceMode, CLIENT_REPORTED_STATUSES
        );
        long partiallyConfirmedOutstanding = allocationRepository.sumOutstandingExposure(
                profile.getId(), balanceMode, PARTIALLY_CONFIRMED_STATUSES
        );
        long grossConfirmedMonth = accountingService.confirmedGrossInPeriod(
                profile, balanceMode, monthStartTime, nextMonthTime
        );
        long grossConfirmedTotal = accountingService.confirmedGross(profile, balanceMode);
        long returnedMonth = accountingService.returnedInPeriod(
                profile, balanceMode, monthStartTime, nextMonthTime
        );
        long returnedTotal = accountingService.returned(profile, balanceMode);
        long closedWithoutPaymentMonth = accountingService.closedWithoutPaymentInPeriod(
                profile, balanceMode, monthStartTime, nextMonthTime
        );
        long closedWithoutPaymentTotal = accountingService.closedWithoutPayment(profile, balanceMode);
        // A return in the current month may relate to a confirmation from an
        // earlier month. Keep the signed period delta; clamping it would hide
        // that accounting fact.
        long netReceivedMonth = Math.subtractExact(grossConfirmedMonth, returnedMonth);
        long netReceivedTotal = Math.subtractExact(grossConfirmedTotal, returnedTotal);
        long balancePaidForCapacity = Math.max(0L, netReceivedTotal);
        long outstanding = Math.addExact(
                Math.addExact(reserved, clientReported),
                partiallyConfirmedOutstanding
        );
        long available = Math.max(0L, accruedTotal - balancePaidForCapacity - outstanding);
        long exposureOverrun = Math.max(
                0L,
                Math.addExact(balancePaidForCapacity, outstanding) - accruedTotal
        );
        return new ContractorPaymentProfileResponse(
                profile.getId(),
                profile.getUser().getId(),
                profile.getRole(),
                profile.getRowVersion(),
                profile.isEnabled(),
                profile.isLiveEnabled(),
                profile.getRecipientName(),
                profile.getPaymentPhone(),
                profile.getBankName(),
                profile.getPaymentComment(),
                profile.getOpeningBalanceKopecks(),
                profile.getTrackingStartedAt(),
                ledgerService.accruedInPeriod(profile, monthStart, nextMonth),
                accruedTotal,
                reserved,
                clientReported,
                partiallyConfirmedOutstanding,
                grossConfirmedMonth,
                grossConfirmedTotal,
                returnedMonth,
                returnedTotal,
                closedWithoutPaymentMonth,
                closedWithoutPaymentTotal,
                netReceivedMonth,
                netReceivedTotal,
                available,
                exposureOverrun,
                balanceMode == ContractorAllocationMode.LIVE,
                shadowMode,
                liveRouting
        );
    }

    private void ensureProfiles(User user, CurrentContractorEligibility eligibility) {
        List<ContractorPaymentProfile> existing = profileRepository.findAllByUserIdForUpdate(user.getId());
        List<ContractorRole> required = new ArrayList<>(eligibility.roles());
        for (ContractorRole role : required) {
            if (existing.stream().noneMatch(profile -> profile.getRole() == role)) {
                profileRepository.save(newProfile(user, role));
            }
        }
        for (ContractorPaymentProfile profile : existing) {
            boolean eligibleNow = eligibility.active() && required.contains(profile.getRole());
            if (eligibleNow
                    || (!profile.isEnabled() && !profile.isLiveEnabled())) {
                continue;
            }
            boolean wasEnabled = profile.isEnabled();
            boolean wasLiveEnabled = profile.isLiveEnabled();
            profile.setEnabled(false);
            profile.setLiveEnabled(false);
            profileRepository.save(profile);
            String action = eligibility.active()
                    ? "AUTO_DISABLE_CONTRACTOR_PAYMENT_PROFILE_ROLE_REMOVED"
                    : "AUTO_DISABLE_CONTRACTOR_PAYMENT_PROFILE_USER_DEACTIVATED";
            businessAuditService.recordSafely(
                    action,
                    "CONTRACTOR_PAYMENT_PROFILE",
                    profile.getId(),
                    null,
                    null,
                    Map.of("enabled", wasEnabled, "liveEnabled", wasLiveEnabled),
                    Map.of("enabled", false, "liveEnabled", false),
                    "userId=" + user.getId() + ", role=" + profile.getRole()
            );
        }
    }

    private ContractorPaymentProfile newProfile(User user, ContractorRole role) {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setUser(user);
        profile.setRole(role);
        profile.setEnabled(false);
        profile.setLiveEnabled(false);
        profile.setOpeningBalanceKopecks(0L);
        profile.setTrackingStartedAt(LocalDateTime.now());
        profile.setTrackingStartZpId(zpRepository.findCurrentMaxId());
        profile.setLedgerSyncZpId(profile.getTrackingStartZpId());
        profile.setLedgerSyncAt(profile.getTrackingStartedAt());
        return profile;
    }

    private void recordOpeningAdjustment(
            ContractorPaymentProfile profile,
            long oldBalance,
            ContractorPaymentProfileRequest request
    ) {
        String requestedReason = normalize(request.openingBalanceReason());
        if (requestedReason.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Для изменения переходящего остатка укажите источник и причину"
            );
        }
        ContractorPaymentProfileAdjustment adjustment = new ContractorPaymentProfileAdjustment();
        adjustment.setProfile(profile);
        adjustment.setOldBalanceKopecks(oldBalance);
        adjustment.setNewBalanceKopecks(request.openingBalanceKopecks());
        adjustment.setDeltaKopecks(Math.subtractExact(request.openingBalanceKopecks(), oldBalance));
        adjustment.setReason(requestedReason);
        adjustment.setChangedBy(currentActor());
        adjustment.setEffectiveAt(LocalDateTime.now());
        adjustmentRepository.save(adjustment);
    }

    private ContractorPaymentProfileAdjustmentResponse toAdjustmentResponse(
            ContractorPaymentProfileAdjustment adjustment
    ) {
        return new ContractorPaymentProfileAdjustmentResponse(
                adjustment.getId(),
                adjustment.getProfile().getId(),
                adjustment.getOldBalanceKopecks(),
                adjustment.getNewBalanceKopecks(),
                adjustment.getDeltaKopecks(),
                adjustment.getReason(),
                adjustment.getChangedBy(),
                adjustment.getEffectiveAt(),
                adjustment.getCreatedAt()
        );
    }

    private void collectChange(
            List<String> fields,
            Map<String, Object> oldAudit,
            Map<String, Object> newAudit,
            String field,
            Object before,
            Object after
    ) {
        if (Objects.equals(before, after)) {
            return;
        }
        fields.add(field);
        oldAudit.put(field, before);
        newAudit.put(field, after);
    }

    private void collectSecretChange(List<String> fields, String field, String before, String after) {
        if (!Objects.equals(normalize(before), normalize(after))) {
            fields.add(field);
        }
    }

    private void validateRequisiteLength(
            String fieldLabel,
            String currentValue,
            String requestedValue,
            int maxLength,
            boolean profileEligibilityBeingReduced
    ) {
        if (requestedValue.length() <= maxLength) {
            return;
        }
        if (profileEligibilityBeingReduced
                && Objects.equals(normalize(currentValue), requestedValue)) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                fieldLabel + " не должно превышать " + maxLength + " символов"
        );
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String name = authentication == null ? "" : normalize(authentication.getName());
        return name.isBlank() ? "system" : name.length() <= 160 ? name : name.substring(0, 160);
    }

    private void requireRole(CurrentContractorEligibility eligibility, ContractorRole role) {
        if (!eligibility.roles().contains(role)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У пользователя нет подходящей роли");
        }
    }

    /**
     * Locks the current user row and both contractor role links before any
     * profile row. This bypasses an already-managed User graph that may have
     * been loaded before a concurrent deactivation or role removal committed.
     */
    private CurrentContractorEligibility lockCurrentEligibility(Long userId) {
        boolean active = userRepository.lockContractorActiveFlag(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        Set<ContractorRole> roles = EnumSet.noneOf(ContractorRole.class);
        if (!userRepository.lockContractorRoleIds(userId, "ROLE_WORKER").isEmpty()) {
            roles.add(ContractorRole.SPECIALIST);
        }
        if (!userRepository.lockContractorRoleIds(userId, "ROLE_MANAGER").isEmpty()) {
            roles.add(ContractorRole.MANAGER);
        }
        return new CurrentContractorEligibility(active, roles);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private ZoneId businessZone() {
        try {
            return ZoneId.of(normalize(businessZoneId));
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Irkutsk");
        }
    }

    private record CurrentContractorEligibility(boolean active, Set<ContractorRole> roles) {
    }
}
