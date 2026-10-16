package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.analytics.service.ContractorAccrualAnalyticsRefreshService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.contractor_payments.dto.ContractorAccrualAdjustmentPreviewResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorAccrualAdjustmentRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorAccrualAdjustmentResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorAccrualAdjustmentReversalRequest;
import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustment;
import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustmentKind;
import com.hunt.otziv.contractor_payments.model.ContractorAccrualBucket;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAmountLimits;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.repository.ContractorAccrualAdjustmentRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardLedgerRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Audited manual compensation, separate from order rewards and opening balances. */
@Service
@RequiredArgsConstructor
public class ContractorAccrualAdjustmentService {

    private static final Set<ContractorAllocationStatus> OUTSTANDING = EnumSet.of(
            ContractorAllocationStatus.RESERVED,
            ContractorAllocationStatus.CLIENT_REPORTED,
            ContractorAllocationStatus.PARTIALLY_CONFIRMED
    );

    private final ContractorAccrualAdjustmentRepository adjustmentRepository;
    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorRewardLedgerRepository rewardLedgerRepository;
    private final ContractorRewardLedgerService rewardLedgerService;
    private final ContractorPaymentProfileService profileService;
    private final ContractorPaymentAccountingPhaseService phaseService;
    private final ContractorPaymentAccountingService accountingService;
    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ManualPaymentTaskContractorCapacityService taskCapacityService;
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    private final ContractorPaymentBusinessClock businessClock;
    private final BusinessAuditService businessAuditService;
    private final ContractorAccrualAnalyticsRefreshService analyticsRefreshService;

    @Transactional(readOnly = true)
    public List<ContractorAccrualAdjustmentResponse> history(Long userId, Long profileId) {
        targetAccessPolicy.requireCanManageUser(userId);
        requireProfile(userId, profileId);
        return adjustmentRepository.findAllByProfileIdOrderByCreatedAtDescIdDesc(
                profileId, PageRequest.of(0, 100)
        ).stream().map(this::response).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContractorAccrualAdjustmentPreviewResponse preview(
            Long userId, Long profileId, ContractorAccrualAdjustmentRequest request
    ) {
        targetAccessPolicy.requireCanManageUser(userId);
        ValidatedRequest validated = validate(request, false);
        // Financial phase mutex precedes profile mutex everywhere that changes capacity.
        ContractorAllocationMode mode = phaseService.lockCurrent();
        ContractorPaymentProfile profile = lockProfile(userId, profileId);
        requireCapacity(profile, mode, validated.signedAmountKopecks());
        return preview(profile, mode, validated.signedAmountKopecks());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContractorAccrualAdjustmentResponse create(
            Long userId, Long profileId, ContractorAccrualAdjustmentRequest request
    ) {
        targetAccessPolicy.requireCanManageUser(userId);
        ValidatedRequest validated = validate(request, true);
        ContractorAllocationMode mode = phaseService.lockCurrent();
        ContractorPaymentProfile profile = lockProfile(userId, profileId);
        ContractorAccrualAdjustment replay = adjustmentRepository.findByProfileIdAndIdempotencyKey(
                profileId, validated.idempotencyKey()
        ).orElse(null);
        if (replay != null) {
            requireSameRequest(replay, validated);
            return response(replay);
        }

        requireCapacity(profile, mode, validated.signedAmountKopecks());
        ContractorAccrualAdjustment entry = new ContractorAccrualAdjustment();
        entry.setProfile(profile);
        entry.setKind(validated.kind());
        entry.setBucket(validated.bucket());
        entry.setSignedAmountKopecks(validated.signedAmountKopecks());
        entry.setEffectiveOn(businessClock.today());
        entry.setReferenceMonth(validated.referenceMonth());
        entry.setReason(validated.reason());
        entry.setEvidenceReference(validated.evidenceReference());
        entry.setCreatedBy(currentActor());
        entry.setCreatedAt(businessClock.now());
        entry.setIdempotencyKey(validated.idempotencyKey());
        ContractorAccrualAdjustment saved = adjustmentRepository.saveAndFlush(entry);
        audit(saved, "CREATE_CONTRACTOR_ACCRUAL_ADJUSTMENT");
        analyticsRefreshService.refreshAfterCommit(saved.getEffectiveOn());
        return response(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContractorAccrualAdjustmentResponse reverse(
            Long userId, Long profileId, Long adjustmentId,
            ContractorAccrualAdjustmentReversalRequest request
    ) {
        targetAccessPolicy.requireCanManageUser(userId);
        String reason = requireText(request == null ? null : request.reason(), 255, "Укажите причину отмены");
        String key = requireText(request == null ? null : request.idempotencyKey(), 120,
                "Укажите ключ операции");
        ContractorAllocationMode mode = phaseService.lockCurrent();
        ContractorPaymentProfile profile = lockProfile(userId, profileId);
        ContractorAccrualAdjustment replay = adjustmentRepository.findByProfileIdAndIdempotencyKey(
                profileId, key
        ).orElse(null);
        if (replay != null) {
            if (replay.getKind() != ContractorAccrualAdjustmentKind.REVERSAL
                    || replay.getReversalOf() == null
                    || !Objects.equals(replay.getReversalOf().getId(), adjustmentId)
                    || !Objects.equals(replay.getReason(), reason)) {
                throw conflict("Ключ операции уже использован для другого действия");
            }
            return response(replay);
        }
        ContractorAccrualAdjustment original = adjustmentRepository.findByIdAndProfileId(
                adjustmentId, profileId
        ).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Корректировка не найдена"));
        if (original.getKind() == ContractorAccrualAdjustmentKind.REVERSAL) {
            throw conflict("Отменять можно только исходную премию или удержание");
        }
        if (adjustmentRepository.existsByReversalOfId(original.getId())) {
            throw conflict("Корректировка уже отменена");
        }
        long reversalAmount = Math.negateExact(original.getSignedAmountKopecks());
        requireCapacity(profile, mode, reversalAmount);
        ContractorAccrualAdjustment reversal = new ContractorAccrualAdjustment();
        reversal.setProfile(profile);
        reversal.setKind(ContractorAccrualAdjustmentKind.REVERSAL);
        reversal.setBucket(original.getBucket());
        reversal.setSignedAmountKopecks(reversalAmount);
        reversal.setEffectiveOn(businessClock.today());
        reversal.setReferenceMonth(original.getReferenceMonth());
        reversal.setReason(reason);
        reversal.setCreatedBy(currentActor());
        reversal.setCreatedAt(businessClock.now());
        reversal.setReversalOf(original);
        reversal.setIdempotencyKey(key);
        ContractorAccrualAdjustment saved = adjustmentRepository.saveAndFlush(reversal);
        audit(saved, "REVERSE_CONTRACTOR_ACCRUAL_ADJUSTMENT");
        analyticsRefreshService.refreshAfterCommit(saved.getEffectiveOn());
        return response(saved);
    }

    private ContractorAccrualAdjustmentPreviewResponse preview(
            ContractorPaymentProfile profile, ContractorAllocationMode mode, long signedAmountKopecks
    ) {
        LocalDate from = businessClock.today().withDayOfMonth(1);
        LocalDate to = from.plusMonths(1);
        long work = rewardLedgerRepository.sumActiveByProfileIdAndPeriod(profile.getId(), from, to);
        long bonus = adjustmentRepository.sumBucketInPeriod(profile.getId(), ContractorAccrualBucket.BONUS, from, to);
        long deduction = Math.negateExact(adjustmentRepository.sumBucketInPeriod(
                profile.getId(), ContractorAccrualBucket.DEDUCTION, from, to
        ));
        long current = Math.subtractExact(Math.addExact(work, bonus), deduction);
        long after = Math.addExact(current, signedAmountKopecks);
        long free = taskCapacityService.ordinaryAvailable(profile, mode);
        long paid = Math.max(0L, accountingService.netReceived(profile, mode));
        long outstanding = allocationRepository.sumOutstandingExposure(profile.getId(), mode, OUTSTANDING);
        return new ContractorAccrualAdjustmentPreviewResponse(
                work, bonus, deduction, current, after, free,
                taskCapacityService.ordinaryAvailableAfterAccrualDelta(profile, mode, signedAmountKopecks),
                paid, outstanding,
                profile.getManualTaskCommitmentKopecks()
        );
    }

    private void requireCapacity(
            ContractorPaymentProfile profile, ContractorAllocationMode mode, long signedAmountKopecks
    ) {
        if (signedAmountKopecks < 0L) {
            long free = taskCapacityService.ordinaryAvailable(profile, mode);
            if (Math.negateExact(signedAmountKopecks) > free) {
                long paid = Math.max(0L, accountingService.netReceived(profile, mode));
                long outstanding = allocationRepository.sumOutstandingExposure(profile.getId(), mode, OUTSTANDING);
                throw conflict("Удержание превышает свободную сумму: свободно " + rubles(free)
                        + " руб., оплачено " + rubles(paid)
                        + " руб., зарезервировано счетами " + rubles(outstanding)
                        + " руб., обязательства заданий " + rubles(profile.getManualTaskCommitmentKopecks())
                        + " руб.");
            }
        }
        // Shared signed balance also catches an arithmetic overflow before persistence.
        Math.addExact(rewardLedgerService.totalAccrued(profile), signedAmountKopecks);
    }

    private String rubles(long kopecks) {
        return BigDecimal.valueOf(kopecks, 2).toPlainString();
    }

    private ValidatedRequest validate(ContractorAccrualAdjustmentRequest request, boolean requireIdempotency) {
        if (request == null || request.kind() == null
                || request.kind() == ContractorAccrualAdjustmentKind.REVERSAL) {
            throw badRequest("Выберите премию или удержание");
        }
        if (request.amountKopecks() <= 0L
                || request.amountKopecks() > ContractorPaymentAmountLimits.MAX_AMOUNT_KOPECKS) {
            throw badRequest("Некорректная сумма корректировки");
        }
        String reason = requireText(request.reason(), 255, "Укажите причину корректировки");
        String evidence = optionalText(request.evidenceReference(), 160, "Номер документа слишком длинный");
        String key = requireIdempotency
                ? requireText(request.idempotencyKey(), 120, "Укажите ключ операции")
                : optionalText(request.idempotencyKey(), 120, "Ключ операции слишком длинный");
        YearMonth referenceMonth;
        try {
            referenceMonth = YearMonth.parse(request.referenceMonth());
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw badRequest("Укажите месяц основания в формате ГГГГ-ММ");
        }
        if (referenceMonth.isAfter(YearMonth.from(businessClock.today()))) {
            throw badRequest("Месяц основания не может быть будущим");
        }
        ContractorAccrualBucket bucket = request.kind() == ContractorAccrualAdjustmentKind.BONUS
                ? ContractorAccrualBucket.BONUS : ContractorAccrualBucket.DEDUCTION;
        long signed = bucket == ContractorAccrualBucket.BONUS
                ? request.amountKopecks() : Math.negateExact(request.amountKopecks());
        return new ValidatedRequest(request.kind(), bucket, signed, referenceMonth.atDay(1),
                reason, evidence, key);
    }

    private void requireSameRequest(ContractorAccrualAdjustment entry, ValidatedRequest request) {
        if (entry.getKind() != request.kind()
                || entry.getSignedAmountKopecks() != request.signedAmountKopecks()
                || !Objects.equals(entry.getReferenceMonth(), request.referenceMonth())
                || !Objects.equals(entry.getReason(), request.reason())
                || !Objects.equals(entry.getEvidenceReference(), request.evidenceReference())) {
            throw conflict("Ключ операции уже использован для другой корректировки");
        }
    }

    private ContractorPaymentProfile requireProfile(Long userId, Long profileId) {
        return profileRepository.findById(profileId)
                .filter(profile -> Objects.equals(profile.getUser().getId(), userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платёжный профиль не найден"));
    }

    private ContractorPaymentProfile lockProfile(Long userId, Long profileId) {
        return profileRepository.findByIdForUpdate(profileId)
                .filter(profile -> Objects.equals(profile.getUser().getId(), userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платёжный профиль не найден"));
    }

    private void audit(ContractorAccrualAdjustment entry, String action) {
        businessAuditService.recordRequiredInCurrentTransaction(
                action, "CONTRACTOR_ACCRUAL_ADJUSTMENT", entry.getId(), null, null,
                null,
                Map.of("profileId", entry.getProfile().getId(), "kind", entry.getKind().name(),
                        "amountKopecks", entry.getSignedAmountKopecks(), "effectiveOn", entry.getEffectiveOn().toString()),
                "referenceMonth=" + YearMonth.from(entry.getReferenceMonth())
                        + (entry.getReversalOf() == null ? "" : ", reversalOf=" + entry.getReversalOf().getId())
        );
    }

    private ContractorAccrualAdjustmentResponse response(ContractorAccrualAdjustment entry) {
        return new ContractorAccrualAdjustmentResponse(
                entry.getId(), entry.getProfile().getId(), entry.getKind(),
                entry.getSignedAmountKopecks(), entry.getEffectiveOn(),
                YearMonth.from(entry.getReferenceMonth()).toString(), entry.getReason(),
                entry.getEvidenceReference(), entry.getCreatedBy(), entry.getCreatedAt(),
                entry.getReversalOf() == null ? null : entry.getReversalOf().getId()
        );
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Требуется авторизация");
        }
        String actor = authentication.getName().trim();
        return actor.length() <= 160 ? actor : actor.substring(0, 160);
    }

    private String requireText(String raw, int maxLength, String error) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > maxLength) {
            throw badRequest(error);
        }
        return value;
    }

    private String optionalText(String raw, int maxLength, String error) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > maxLength) {
            throw badRequest(error);
        }
        return value.isEmpty() ? null : value;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record ValidatedRequest(
            ContractorAccrualAdjustmentKind kind,
            ContractorAccrualBucket bucket,
            long signedAmountKopecks,
            LocalDate referenceMonth,
            String reason,
            String evidenceReference,
            String idempotencyKey
    ) {
    }
}
