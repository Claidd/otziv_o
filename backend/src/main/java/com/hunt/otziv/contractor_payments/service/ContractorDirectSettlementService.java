package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.dto.ContractorDirectSettlementRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorDirectSettlementResponse;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorDirectSettlement;
import com.hunt.otziv.contractor_payments.model.ContractorDirectSettlementType;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAmountLimits;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorDirectSettlementRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ContractorDirectSettlementService {

    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorDirectSettlementRepository settlementRepository;
    private final ContractorPaymentProfileService profileService;
    private final ContractorPaymentAccountingService accountingService;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final EntityManager entityManager;
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy;

    @Transactional
    public ContractorDirectSettlementResponse createPayment(
            Long userId,
            Long profileId,
            ContractorDirectSettlementRequest rawRequest
    ) {
        targetAccessPolicy.requireCanManageUser(userId);
        NormalizedRequest request = normalizeAndValidate(rawRequest);
        ContractorAllocationMode mode = accountingPhaseService.lockCurrent();
        ContractorPaymentProfile profile = lockProfile(userId, profileId);

        ContractorDirectSettlement replay = settlementRepository
                .findByProfileIdAndIdempotencyKeyForUpdate(profileId, request.idempotencyKey())
                .orElse(null);
        if (replay != null) {
            requireSamePayment(replay, request);
            return toResponse(replay);
        }

        if (request.expectedMode() != mode) {
            throw conflict("Режим учёта изменился. Обновите данные и повторите операцию");
        }
        long available = profileService.available(profile, mode);
        if (request.amountKopecks() > available) {
            throw conflict("Сумма выплаты превышает доступный остаток");
        }

        ContractorDirectSettlement settlement = ContractorDirectSettlement.payment(
                profile,
                mode,
                request.amountKopecks(),
                request.effectiveAt(),
                request.reason(),
                request.evidenceReference(),
                request.idempotencyKey(),
                currentActor()
        );
        settlement = settlementRepository.saveAndFlush(settlement);

        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setMode(mode);
        allocation.setSourceType(ContractorAllocationSourceType.DIRECT_SETTLEMENT);
        allocation.setSourceId(settlement.getId());
        allocation.setAttemptNo(1);
        allocation.setRecipientType(recipientType(profile.getRole()));
        allocation.setRecipientProfile(profile);
        allocation.setRecipientUserId(profile.getUser().getId());
        allocation.setAmountKopecks(request.amountKopecks());
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        allocation.setAvailableBeforeKopecks(available);
        allocation.setReservedAt(request.effectiveAt());
        allocation = allocationRepository.saveAndFlush(allocation);

        accountingService.recordReservation(allocation);
        boolean confirmed = accountingService.recordConfirmation(
                allocation,
                request.amountKopecks(),
                request.effectiveAt(),
                request.reason(),
                "DIRECT_SETTLEMENT:PAYMENT:" + settlement.getId(),
                mode == ContractorAllocationMode.SHADOW,
                false
        );
        if (!confirmed) {
            throw conflict("Не удалось зафиксировать выплату");
        }
        allocationRepository.saveAndFlush(allocation);

        settlement.attachAllocation(allocation);
        return toResponse(settlementRepository.saveAndFlush(settlement));
    }

    @Transactional
    public ContractorDirectSettlementResponse createReversal(
            Long userId,
            Long profileId,
            Long originalSettlementId,
            ContractorDirectSettlementRequest rawRequest
    ) {
        targetAccessPolicy.requireCanManageUser(userId);
        NormalizedRequest request = normalizeAndValidate(rawRequest);
        accountingPhaseService.lockCurrent();
        ContractorPaymentProfile profile = lockProfile(userId, profileId);

        ContractorDirectSettlement replay = settlementRepository
                .findByProfileIdAndIdempotencyKeyForUpdate(profileId, request.idempotencyKey())
                .orElse(null);
        if (replay != null) {
            requireSameReversal(replay, originalSettlementId, request);
            return toResponse(replay);
        }

        ContractorDirectSettlement original = settlementRepository.findByIdForUpdate(originalSettlementId)
                .filter(value -> value.getType() == ContractorDirectSettlementType.PAYMENT)
                .filter(value -> value.getProfile() != null
                        && Objects.equals(value.getProfile().getId(), profile.getId()))
                .orElseThrow(() -> notFound("Исходная выплата не найдена"));
        if (request.effectiveAt().isBefore(original.getEffectiveAt())) {
            throw badRequest("Дата возврата не может быть раньше даты выплаты");
        }
        if (request.expectedMode() != original.getMode()) {
            throw conflict("Режим исходной выплаты не совпадает с запросом");
        }
        if (original.getAllocation() == null || original.getAllocation().getId() == null) {
            throw conflict("Исходная выплата не связана с учётной записью");
        }

        ContractorPaymentAllocation allocation = allocationRepository
                .findByIdForUpdate(original.getAllocation().getId())
                .orElseThrow(() -> conflict("Учётная запись исходной выплаты не найдена"));
        entityManager.refresh(allocation, LockModeType.PESSIMISTIC_WRITE);
        requireDirectAllocation(original, profile, allocation);

        List<ContractorDirectSettlement> reversals = settlementRepository
                .findAllReversalsForUpdate(original.getId());
        long recordedReversals = reversals.stream()
                .mapToLong(ContractorDirectSettlement::getAmountKopecks)
                .reduce(0L, Math::addExact);
        if (recordedReversals != allocation.getReturnedKopecks()) {
            throw conflict("История возвратов требует сверки");
        }
        long remaining = Math.subtractExact(original.getAmountKopecks(), recordedReversals);
        if (request.amountKopecks() > remaining) {
            throw conflict("Сумма возврата превышает невозвращённый остаток");
        }

        ContractorDirectSettlement reversal = ContractorDirectSettlement.reversal(
                original,
                request.amountKopecks(),
                request.effectiveAt(),
                request.reason(),
                request.evidenceReference(),
                request.idempotencyKey(),
                currentActor()
        );
        reversal = settlementRepository.saveAndFlush(reversal);

        long returnedTotal = Math.addExact(allocation.getReturnedKopecks(), request.amountKopecks());
        boolean returned = accountingService.recordReturnTotal(
                allocation,
                returnedTotal,
                request.effectiveAt(),
                request.reason(),
                "DIRECT_SETTLEMENT:REVERSAL:" + reversal.getId()
        );
        if (!returned || allocation.getReturnedKopecks() != returnedTotal) {
            throw conflict("Не удалось зафиксировать возврат");
        }
        allocationRepository.saveAndFlush(allocation);
        return toResponse(reversal);
    }

    @Transactional(readOnly = true)
    public List<ContractorDirectSettlementResponse> history(Long userId, Long profileId) {
        targetAccessPolicy.requireCanManageUser(userId);
        ContractorPaymentProfile profile = profileRepository.findById(profileId)
                .filter(value -> value.getUser() != null
                        && Objects.equals(value.getUser().getId(), userId))
                .orElseThrow(() -> notFound("Платёжный профиль не найден"));
        return settlementRepository.findAllByProfileIdOrderByEffectiveAtDescIdDesc(profile.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    private ContractorPaymentProfile lockProfile(Long userId, Long profileId) {
        return profileRepository.findByIdForUpdate(profileId)
                .filter(value -> value.getUser() != null
                        && Objects.equals(value.getUser().getId(), userId))
                .orElseThrow(() -> notFound("Платёжный профиль не найден"));
    }

    private void requireDirectAllocation(
            ContractorDirectSettlement original,
            ContractorPaymentProfile profile,
            ContractorPaymentAllocation allocation
    ) {
        if (allocation.getSourceType() != ContractorAllocationSourceType.DIRECT_SETTLEMENT
                || !Objects.equals(allocation.getSourceId(), original.getId())
                || allocation.getAttemptNo() != 1
                || allocation.getRecipientProfile() == null
                || !Objects.equals(allocation.getRecipientProfile().getId(), profile.getId())
                || allocation.getMode() != original.getMode()
                || allocation.getAmountKopecks() != original.getAmountKopecks()
                || allocation.getConfirmedKopecks() < original.getAmountKopecks()
                || allocation.getReturnedKopecks() > original.getAmountKopecks()) {
            throw conflict("Учётная запись исходной выплаты требует сверки");
        }
    }

    private void requireSamePayment(
            ContractorDirectSettlement settlement,
            NormalizedRequest request
    ) {
        if (settlement.getType() != ContractorDirectSettlementType.PAYMENT
                || !samePayload(settlement, request)) {
            throw conflict("Ключ идемпотентности уже использован с другими данными");
        }
    }

    private void requireSameReversal(
            ContractorDirectSettlement settlement,
            Long originalSettlementId,
            NormalizedRequest request
    ) {
        if (settlement.getType() != ContractorDirectSettlementType.REVERSAL
                || settlement.getOriginalSettlement() == null
                || !Objects.equals(settlement.getOriginalSettlement().getId(), originalSettlementId)
                || !samePayload(settlement, request)) {
            throw conflict("Ключ идемпотентности уже использован с другими данными");
        }
    }

    private boolean samePayload(
            ContractorDirectSettlement settlement,
            NormalizedRequest request
    ) {
        return settlement.getAmountKopecks() == request.amountKopecks()
                && settlement.getMode() == request.expectedMode()
                && Objects.equals(settlement.getEffectiveAt(), request.effectiveAt())
                && Objects.equals(settlement.getReason(), request.reason())
                && Objects.equals(settlement.getEvidenceReference(), request.evidenceReference())
                && Objects.equals(settlement.getIdempotencyKey(), request.idempotencyKey());
    }

    private NormalizedRequest normalizeAndValidate(ContractorDirectSettlementRequest request) {
        if (request == null) {
            throw badRequest("Данные выплаты обязательны");
        }
        if (request.expectedMode() == null) {
            throw badRequest("Ожидаемый режим учёта обязателен");
        }
        LocalDateTime effectiveAt = request.effectiveAt() == null
                ? null
                : request.effectiveAt().truncatedTo(ChronoUnit.MICROS);
        String reason = normalizeRequired(request.reason(), 255, "Причина");
        String evidenceReference = normalizeRequired(
                request.evidenceReference(),
                160,
                "Ссылка на подтверждение"
        );
        String idempotencyKey = normalizeRequired(
                request.idempotencyKey(),
                120,
                "Ключ идемпотентности"
        );
        if (request.amountKopecks() <= 0L) {
            throw badRequest("Сумма должна быть больше нуля");
        }
        if (request.amountKopecks() > ContractorPaymentAmountLimits.MAX_AMOUNT_KOPECKS) {
            throw badRequest("Сумма превышает допустимый предел");
        }
        if (effectiveAt == null || effectiveAt.isAfter(LocalDateTime.now())) {
            throw badRequest("Дата операции не может быть в будущем");
        }
        if (reason == null || evidenceReference == null || idempotencyKey == null) {
            throw badRequest("Причина, подтверждение и ключ идемпотентности обязательны");
        }
        return new NormalizedRequest(
                request.expectedMode(),
                request.amountKopecks(),
                effectiveAt,
                reason,
                evidenceReference,
                idempotencyKey
        );
    }

    private ContractorDirectSettlementResponse toResponse(ContractorDirectSettlement settlement) {
        return new ContractorDirectSettlementResponse(
                settlement.getId(),
                settlement.getProfile().getId(),
                settlement.getProfile().getUser().getId(),
                settlement.getType(),
                settlement.getMode(),
                settlement.getMode() == ContractorAllocationMode.SHADOW,
                settlement.getAmountKopecks(),
                settlement.getEffectiveAt(),
                settlement.getReason(),
                settlement.getEvidenceReference(),
                settlement.getIdempotencyKey(),
                settlement.getActor(),
                settlement.getCreatedAt(),
                settlement.getOriginalSettlement() == null ? null : settlement.getOriginalSettlement().getId(),
                settlement.getAllocation() == null ? null : settlement.getAllocation().getId()
        );
    }

    private ContractorRecipientType recipientType(ContractorRole role) {
        return role == ContractorRole.MANAGER
                ? ContractorRecipientType.MANAGER
                : ContractorRecipientType.SPECIALIST;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return "system";
        }
        return normalize(authentication.getName(), 150);
    }

    private String normalize(String value, int limit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String normalizeRequired(String value, int limit, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > limit) {
            throw badRequest(field + " превышает допустимую длину");
        }
        return normalized;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record NormalizedRequest(
            ContractorAllocationMode expectedMode,
            long amountKopecks,
            LocalDateTime effectiveAt,
            String reason,
            String evidenceReference,
            String idempotencyKey
    ) {
    }
}
