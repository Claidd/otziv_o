package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRequisitesSnapshot;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates the LIVE contractor decision inside the same transaction that
 * freezes client-facing requisites. Callers must persist the returned
 * allocation id and its immutable recipient snapshots before committing.
 */
@Service
@RequiredArgsConstructor
public class ContractorPaymentLiveRoutingService {

    private static final Set<CommonInvoiceStatus> PUBLIC_PAYABLE_COMMON_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );

    private static final Set<ContractorAllocationStatus> RETRYABLE_STATUSES = Set.of(
            ContractorAllocationStatus.RELEASED_UNPAID,
            ContractorAllocationStatus.EXPIRED,
            ContractorAllocationStatus.CANCELED,
            ContractorAllocationStatus.PARTIALLY_RETURNED,
            ContractorAllocationStatus.RETURNED
    );

    private final ContractorPaymentRuntimeSwitch runtimeSwitch;
    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorPaymentProfileService profileService;
    private final ContractorPaymentRoutingLimitService routingLimitService;
    private final ContractorPaymentAccountingService accountingService;
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final ContractorPaymentRolloutStateService rolloutStateService;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final UserRepository userRepository;
    private final ContractorOrderManagerResolver orderManagerResolver;
    private final ManualPaymentTaskContractorCapacityService taskCapacityService;

    public boolean enabledForNewRoutes() {
        return runtimeSwitch.liveRoutingEnabled();
    }

    public boolean configuredButBlockedForNewRoutes() {
        ContractorPaymentRuntimeSwitch.RuntimeStatus status = runtimeSwitch.status();
        return status.liveRoutingMasterEnabled()
                && status.liveRoutingDatabaseEnabled()
                && status.rewardAttributionMasterEnabled()
                && status.rewardAttributionDatabaseEnabled()
                && status.rewardAttributionLiveEnabled()
                && !status.liveRoutingEnabled();
    }

    @Transactional(readOnly = true)
    public boolean isCommonClientReportable(CommonInvoice invoice) {
        if (invoice == null
                || invoice.getId() == null
                || invoice.getContractorAllocationId() == null
                || !PUBLIC_PAYABLE_COMMON_STATUSES.contains(invoice.getStatus())
                || invoice.getClientReportedAt() != null
                || invoice.getPaymentRouteAmountKopecks() == null
                || invoice.getPaymentRouteAmountKopecks() <= 0
                || invoice.getPaidKopecks() >= invoice.getAmountKopecks()
                || !"MANUAL_MOBILE_BANK".equals(normalize(invoice.getPaymentRouteType())
                        .toUpperCase(java.util.Locale.ROOT))
                || invoice.getPaymentRouteManualType() != ManualPaymentType.MOBILE_BANK
                || invoice.getPaymentRouteManualSource() != ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            return false;
        }
        long remaining = Math.max(0L, invoice.getAmountKopecks() - invoice.getPaidKopecks());
        long routeAmount = invoice.getPaymentRouteAmountKopecks();
        return allocationRepository.findById(invoice.getContractorAllocationId())
                .filter(allocation -> allocation.getMode() == ContractorAllocationMode.LIVE)
                .filter(allocation -> allocation.getSourceType() == ContractorAllocationSourceType.COMMON_INVOICE)
                .filter(allocation -> Objects.equals(allocation.getSourceId(), invoice.getId()))
                .filter(allocation -> Objects.equals(allocation.getCommonInvoiceId(), invoice.getId()))
                .filter(allocation -> allocation.getStatus() == ContractorAllocationStatus.RESERVED)
                .filter(allocation -> allocation.getRecipientProfile() != null)
                .filter(allocation -> allocation.getRecipientType() == ContractorRecipientType.SPECIALIST
                        || allocation.getRecipientType() == ContractorRecipientType.MANAGER)
                .filter(allocation -> allocation.getAmountKopecks() == routeAmount)
                .filter(allocation -> commonInvoiceGenerationStillMatches(invoice, allocation, remaining))
                .isPresent();
    }

    /**
     * Contractor requisites are public only while the exact frozen attempt can
     * still receive money. The encrypted snapshots remain available to staff
     * for reconciliation after the public page has stopped exposing them.
     */
    @Transactional(readOnly = true)
    public boolean isCommonContractorRequisitesVisible(CommonInvoice invoice) {
        long remaining = invoice == null
                ? 0L
                : Math.max(0L, invoice.getAmountKopecks() - invoice.getPaidKopecks());
        return isCommonContractorRequisitesVisible(invoice, remaining);
    }

    @Transactional(readOnly = true)
    public boolean isCommonContractorRequisitesVisible(
            CommonInvoice invoice,
            long remainingKopecks
    ) {
        return activeCommonInvoiceRequisites(invoice, remainingKopecks).isPresent();
    }

    /**
     * Resolves contractor requisites exclusively from the encrypted allocation
     * snapshot. Legacy common-invoice plaintext columns are deliberately not
     * consulted, even as a fallback.
     */
    @Transactional(readOnly = true)
    public Optional<ContractorPaymentRequisitesSnapshot> activeCommonInvoiceRequisites(
            CommonInvoice invoice,
            long remainingKopecks
    ) {
        if (invoice == null
                || invoice.getId() == null
                || invoice.getContractorAllocationId() == null
                || !PUBLIC_PAYABLE_COMMON_STATUSES.contains(invoice.getStatus())
                || invoice.getPaymentRouteAmountKopecks() == null
                || invoice.getPaymentRouteAmountKopecks() <= 0
                || remainingKopecks <= 0
                || !"MANUAL_MOBILE_BANK".equals(normalize(invoice.getPaymentRouteType())
                        .toUpperCase(java.util.Locale.ROOT))
                || invoice.getPaymentRouteManualType() != ManualPaymentType.MOBILE_BANK
                || invoice.getPaymentRouteManualSource() != ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            return Optional.empty();
        }
        long routeAmount = invoice.getPaymentRouteAmountKopecks();
        return allocationRepository.findById(invoice.getContractorAllocationId())
                .filter(allocation -> allocation.getMode() == ContractorAllocationMode.LIVE)
                .filter(allocation -> allocation.getSourceType() == ContractorAllocationSourceType.COMMON_INVOICE)
                .filter(allocation -> Objects.equals(allocation.getSourceId(), invoice.getId()))
                .filter(allocation -> Objects.equals(allocation.getCommonInvoiceId(), invoice.getId()))
                .filter(allocation -> allocation.getRecipientProfile() != null)
                .filter(allocation -> allocation.getRecipientType() == ContractorRecipientType.SPECIALIST
                        || allocation.getRecipientType() == ContractorRecipientType.MANAGER)
                .filter(allocation -> allocation.getAmountKopecks() == routeAmount)
                .filter(allocation -> commonInvoiceGenerationStillMatches(
                        invoice,
                        allocation,
                        remainingKopecks
                ))
                .filter(allocation -> allocation.getStatus() == ContractorAllocationStatus.RESERVED
                        || allocation.getStatus() == ContractorAllocationStatus.CLIENT_REPORTED
                        || allocation.getStatus() == ContractorAllocationStatus.PARTIALLY_CONFIRMED)
                .filter(allocation -> latest(ContractorAllocationSourceType.COMMON_INVOICE, invoice.getId())
                        .map(value -> Objects.equals(value.getId(), allocation.getId()))
                        .orElse(false))
                .flatMap(this::requisitesSnapshot);
    }

    /**
     * Public standalone requisites are valid only for the exact active LIVE
     * attempt frozen on this payment-link row. A payable-looking legacy link
     * must not keep exposing a contractor after its allocation was released
     * and the capacity was assigned elsewhere.
     */
    @Transactional(readOnly = true)
    public boolean isPaymentLinkContractorRequisitesVisible(PaymentLink link) {
        return activePaymentLinkRequisites(link).isPresent();
    }

    /**
     * Resolves contractor requisites exclusively from the encrypted allocation
     * snapshot after validating the exact active source attempt.
     */
    @Transactional(readOnly = true)
    public Optional<ContractorPaymentRequisitesSnapshot> activePaymentLinkRequisites(PaymentLink link) {
        if (link == null
                || link.getId() == null
                || link.getContractorAllocationId() == null
                || (link.getStatus() != PaymentLinkStatus.WAITING_MANUAL_PAYMENT
                && link.getStatus() != PaymentLinkStatus.MANUAL_REPORTED)
                || link.getPaymentMethod() != PaymentMethod.MANUAL_MOBILE_BANK
                || link.getManualPaymentType() != ManualPaymentType.MOBILE_BANK
                || link.getManualSource() != ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            return Optional.empty();
        }
        ContractorPaymentAllocation allocation = allocationRepository
                .findById(link.getContractorAllocationId())
                .orElse(null);
        if (!paymentLinkGenerationMatches(link, allocation)
                || !activePublicStatus(allocation)
                || !latest(ContractorAllocationSourceType.PAYMENT_LINK, link.getId())
                .map(value -> Objects.equals(value.getId(), allocation.getId()))
                .orElse(false)) {
            return Optional.empty();
        }
        return requisitesSnapshot(allocation);
    }

    /** Source PaymentLink must already be locked by the public POST flow. */
    @Transactional
    public void validatePaymentLinkClientReportedRoute(PaymentLink link) {
        if (link == null || link.getContractorAllocationId() == null) {
            return;
        }
        ContractorPaymentAllocation allocation = lockAllocationProfileFirst(link.getContractorAllocationId())
                .orElse(null);
        boolean current = paymentLinkGenerationMatches(link, allocation)
                && activePublicStatus(allocation)
                && latest(ContractorAllocationSourceType.PAYMENT_LINK, link.getId())
                .map(value -> Objects.equals(value.getId(), allocation.getId()))
                .orElse(false);
        if (!current) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платежный маршрут ссылки изменился и требует сверки"
            );
        }
    }

    private boolean paymentLinkGenerationMatches(
            PaymentLink link,
            ContractorPaymentAllocation allocation
    ) {
        Long orderId = link == null || link.getOrder() == null ? null : link.getOrder().getId();
        Long reservedAmount = link == null ? null : link.getReservedAmountKopecks();
        boolean contractorRecipient = allocation != null
                && (allocation.getRecipientType() == ContractorRecipientType.SPECIALIST
                || allocation.getRecipientType() == ContractorRecipientType.MANAGER);
        return link != null
                && allocation != null
                && Objects.equals(link.getContractorAllocationId(), allocation.getId())
                && link.getManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE
                && link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK
                && link.getManualPaymentType() == ManualPaymentType.MOBILE_BANK
                && allocation.getMode() == ContractorAllocationMode.LIVE
                && allocation.getSourceType() == ContractorAllocationSourceType.PAYMENT_LINK
                && Objects.equals(allocation.getSourceId(), link.getId())
                && Objects.equals(allocation.getOrderId(), orderId)
                && exactGeneration(link.getShadowRouteGeneration(), allocation.getSourceGenerationSnapshot())
                && Objects.equals(link.getShadowRouteOrderId(), orderId)
                && Objects.equals(link.getShadowRouteOrderId(), allocation.getOrderId())
                && link.getShadowRouteAmountKopecks() != null
                && link.getShadowRouteAmountKopecks() == allocation.getAmountKopecks()
                && allocation.getRecipientProfile() != null
                && contractorRecipient
                && allocation.getAmountKopecks() == link.getAmountKopecks()
                && (reservedAmount == null || reservedAmount == allocation.getAmountKopecks());
    }

    private boolean activePublicStatus(ContractorPaymentAllocation allocation) {
        return allocation != null
                && (allocation.getStatus() == ContractorAllocationStatus.RESERVED
                || allocation.getStatus() == ContractorAllocationStatus.CLIENT_REPORTED
                || allocation.getStatus() == ContractorAllocationStatus.PARTIALLY_CONFIRMED);
    }

    private boolean commonInvoiceGenerationStillMatches(
            CommonInvoice invoice,
            ContractorPaymentAllocation allocation,
            long remainingKopecks
    ) {
        if (!exactGeneration(
                invoice == null ? null : invoice.getShadowRouteGeneration(),
                allocation == null ? null : allocation.getSourceGenerationSnapshot()
        )
                || invoice.getShadowRouteAmountKopecks() == null
                || invoice.getShadowRouteAmountKopecks() != allocation.getAmountKopecks()) {
            return false;
        }
        long baseline = allocation.getSourcePaidBaselineKopecks();
        long amount = allocation.getAmountKopecks();
        long expectedInvoiceAmount;
        try {
            expectedInvoiceAmount = Math.addExact(baseline, amount);
        } catch (ArithmeticException overflow) {
            return false;
        }
        if (baseline < 0
                || invoice.getAmountKopecks() != expectedInvoiceAmount
                || invoice.getPaidKopecks() < baseline) {
            return false;
        }
        long observedForAttempt = invoice.getPaidKopecks() - baseline;
        long confirmedNet = Math.max(
                0L,
                allocation.getConfirmedKopecks() - allocation.getReturnedKopecks()
        );
        return observedForAttempt >= 0
                && observedForAttempt <= amount
                && confirmedNet <= observedForAttempt
                && remainingKopecks == amount - observedForAttempt;
    }

    private boolean exactGeneration(String sourceGeneration, String allocationGeneration) {
        String source = normalize(sourceGeneration);
        String allocation = normalize(allocationGeneration);
        return !source.isBlank() && source.equals(allocation);
    }

    private Optional<ContractorPaymentRequisitesSnapshot> requisitesSnapshot(
            ContractorPaymentAllocation allocation
    ) {
        String recipient = normalize(allocation == null ? null : allocation.getRecipientNameSnapshot());
        String phone = normalize(allocation == null ? null : allocation.getPaymentPhoneSnapshot());
        if (allocation == null || allocation.getId() == null || recipient.isBlank() || phone.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ContractorPaymentRequisitesSnapshot(
                allocation.getId(),
                recipient,
                phone,
                normalize(allocation.getBankNameSnapshot()),
                normalize(allocation.getPaymentCommentSnapshot())
        ));
    }

    /**
     * Decides whether an already published common-invoice route is still the
     * immutable current attempt. The allocation row is locked so a concurrent
     * return reconciler cannot change the decision between this check and
     * creation of the next attempt.
     */
    @Transactional
    public FrozenCommonRouteAction frozenCommonRouteAction(Long invoiceId, Long allocationId) {
        if (allocationId == null) {
            return FrozenCommonRouteAction.KEEP;
        }
        // Common-invoice lifecycle always locks source -> profile -> allocation.
        // publicInvoice already owns this source lock; reacquiring it is safe and
        // also makes this method correct when called independently.
        CommonInvoice invoice = commonInvoiceRepository.findByIdForUpdate(invoiceId)
                .orElse(null);
        if (invoice == null || !Objects.equals(invoice.getContractorAllocationId(), allocationId)) {
            return FrozenCommonRouteAction.BLOCK_RECONCILIATION;
        }
        ContractorPaymentAllocation allocation = lockAllocationProfileFirst(allocationId)
                .orElse(null);
        return evaluateFrozenCommonRouteAction(invoiceId, allocationId, invoice, allocation);
    }

    /**
     * Read-only preview used by GET/context endpoints. The mutating route-change
     * command always repeats the same decision under source/profile/allocation
     * locks before releasing a reservation or creating a replacement.
     */
    @Transactional(readOnly = true)
    public FrozenCommonRouteAction previewFrozenCommonRouteAction(Long invoiceId, Long allocationId) {
        if (allocationId == null) {
            return FrozenCommonRouteAction.KEEP;
        }
        CommonInvoice invoice = commonInvoiceRepository.findById(invoiceId)
                .orElse(null);
        ContractorPaymentAllocation allocation = allocationRepository.findById(allocationId)
                .orElse(null);
        return evaluateFrozenCommonRouteAction(invoiceId, allocationId, invoice, allocation);
    }

    private FrozenCommonRouteAction evaluateFrozenCommonRouteAction(
            Long invoiceId,
            Long allocationId,
            CommonInvoice invoice,
            ContractorPaymentAllocation allocation
    ) {
        if (invoice == null || !Objects.equals(invoice.getContractorAllocationId(), allocationId)) {
            return FrozenCommonRouteAction.BLOCK_RECONCILIATION;
        }
        if (allocation == null
                || allocation.getMode() != ContractorAllocationMode.LIVE
                || allocation.getSourceType() != ContractorAllocationSourceType.COMMON_INVOICE
                || !Objects.equals(allocation.getSourceId(), invoiceId)
                || !Objects.equals(allocation.getCommonInvoiceId(), invoiceId)) {
            return FrozenCommonRouteAction.BLOCK_RECONCILIATION;
        }
        if (allocation.getStatus() == ContractorAllocationStatus.RETURN_AMOUNT_PENDING) {
            return FrozenCommonRouteAction.BLOCK_RECONCILIATION;
        }
        // CommonInvoice stores aggregate paidKopecks, not evidence bound to a
        // concrete allocation attempt. Automatically creating attempt N+1
        // would let a late payment of attempt N be credited to the new
        // recipient. Standalone links have an attempt-bound source row and do
        // not have this ambiguity.
        return RETRYABLE_STATUSES.contains(allocation.getStatus())
                ? FrozenCommonRouteAction.BLOCK_RECONCILIATION
                : FrozenCommonRouteAction.KEEP;
    }

    /**
     * Locks and validates the exact LIVE allocation behind an operator's
     * statement confirmation. Unlike the public visibility predicate this also
     * permits released sources: a late transfer must still be attributed to A,
     * never to a newer source B.
     */
    @Transactional
    public ContractorPaymentAllocation validatedCommonConfirmationSource(
            Long invoiceId,
            Long allocationId
    ) {
        CommonInvoice invoice = commonInvoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ContractorPaymentAllocation allocation = lockAllocationProfileFirst(allocationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Назначение получателя общего счета не найдено"
                ));
        boolean contractorRecipient = allocation.getRecipientType() == ContractorRecipientType.SPECIALIST
                || allocation.getRecipientType() == ContractorRecipientType.MANAGER;
        long remainingKopecks = Math.max(0L, invoice.getAmountKopecks() - invoice.getPaidKopecks());
        if (!Objects.equals(invoice.getContractorAllocationId(), allocationId)
                || invoice.getPaymentRouteManualSource() != ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE
                || invoice.getPaymentRouteManualType() != ManualPaymentType.MOBILE_BANK
                || invoice.getPaymentRouteAmountKopecks() == null
                || invoice.getPaymentRouteAmountKopecks() != allocation.getAmountKopecks()
                || allocation.getMode() != ContractorAllocationMode.LIVE
                || allocation.getSourceType() != ContractorAllocationSourceType.COMMON_INVOICE
                || !Objects.equals(allocation.getSourceId(), invoiceId)
                || !Objects.equals(allocation.getCommonInvoiceId(), invoiceId)
                || allocation.getRecipientProfile() == null
                || !contractorRecipient
                || !commonInvoiceGenerationStillMatches(invoice, allocation, remainingKopecks)
                || allocation.getStatus() == ContractorAllocationStatus.RETURN_AMOUNT_PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Зафиксированный источник получателя изменился и требует отдельной сверки"
            );
        }
        return allocation;
    }

    /** Same lifecycle decision for an already published standalone link. */
    @Transactional
    public FrozenPaymentLinkAction frozenPaymentLinkAction(Long paymentLinkId, Long allocationId) {
        if (allocationId == null) {
            return FrozenPaymentLinkAction.KEEP;
        }
        ContractorPaymentAllocation allocation = lockAllocationProfileFirst(allocationId)
                .orElse(null);
        if (allocation == null
                || allocation.getMode() != ContractorAllocationMode.LIVE
                || allocation.getSourceType() != ContractorAllocationSourceType.PAYMENT_LINK
                || !Objects.equals(allocation.getSourceId(), paymentLinkId)) {
            return FrozenPaymentLinkAction.BLOCK_RECONCILIATION;
        }
        if (allocation.getStatus() == ContractorAllocationStatus.RETURN_AMOUNT_PENDING) {
            return FrozenPaymentLinkAction.BLOCK_RECONCILIATION;
        }
        return RETRYABLE_STATUSES.contains(allocation.getStatus())
                ? FrozenPaymentLinkAction.START_NEW_ATTEMPT
                : FrozenPaymentLinkAction.KEEP;
    }

    /**
     * Persists the public "I paid" statement for a frozen contractor route.
     * This event intentionally does not increase confirmedKopecks and keeps the
     * full reservation occupied until an administrator/bank confirms receipt.
     */
    @Transactional
    public LocalDateTime recordCommonClientReported(String rawToken) {
        String token = normalize(rawToken);
        CommonInvoiceRepository.ContractorRouteRef routeRef = commonInvoiceRepository
                .findContractorRouteRefByToken(token)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Общий счет не найден"
                ));
        if (routeRef.getAllocationId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Для этого счета не зафиксирован платежный профиль исполнителя"
            );
        }

        // Common-invoice lifecycle lock order: source -> recipient profile ->
        // allocation. GET, public report and reconciliation must all follow it;
        // otherwise GET(source->profile) and POST(profile->source) can deadlock.
        CommonInvoice invoice = commonInvoiceRepository.findByIdForUpdate(routeRef.getInvoiceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (!Objects.equals(invoice.getToken(), token)
                || !Objects.equals(invoice.getContractorAllocationId(), routeRef.getAllocationId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платежный маршрут общего счета изменился и требует сверки"
            );
        }
        ContractorPaymentAllocation allocation = lockAllocationProfileFirst(routeRef.getAllocationId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Платежный маршрут требует сверки"
                ));
        validateCommonClientReportedRoute(token, invoice, allocation);

        LocalDateTime existingEvidence = allocation.getClientReportedAt() != null
                ? allocation.getClientReportedAt()
                : invoice.getClientReportedAt();
        if (allocation.getStatus() == ContractorAllocationStatus.CLIENT_REPORTED) {
            if (existingEvidence == null) {
                existingEvidence = LocalDateTime.now();
                allocation.setClientReportedAt(existingEvidence);
                allocationRepository.save(allocation);
            }
            if (invoice.getClientReportedAt() == null) {
                invoice.setClientReportedAt(existingEvidence);
                commonInvoiceRepository.save(invoice);
            }
            return existingEvidence;
        }
        if (allocation.getStatus() != ContractorAllocationStatus.RESERVED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сообщение об оплате недоступно для текущего состояния платежа"
            );
        }

        LocalDateTime reportedAt = existingEvidence == null ? LocalDateTime.now() : existingEvidence;
        accountingService.recordClientReported(
                allocation,
                reportedAt,
                "Клиент сообщил об оплате общего счета",
                "COMMON_PUBLIC_REPORTED:ATTEMPT:" + allocation.getAttemptNo()
        );
        allocationRepository.save(allocation);
        LocalDateTime durableReportedAt = allocation.getClientReportedAt() == null
                ? reportedAt
                : allocation.getClientReportedAt();
        invoice.setClientReportedAt(durableReportedAt);
        commonInvoiceRepository.save(invoice);
        return durableReportedAt;
    }

    private void validateCommonClientReportedRoute(
            String token,
            CommonInvoice invoice,
            ContractorPaymentAllocation allocation
    ) {
        boolean contractorRecipient = allocation.getRecipientType() == ContractorRecipientType.SPECIALIST
                || allocation.getRecipientType() == ContractorRecipientType.MANAGER;
        boolean manualContractorRoute = invoice.getPaymentRouteManualSource()
                == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE;
        if (!Objects.equals(invoice.getToken(), token)
                || !Objects.equals(invoice.getContractorAllocationId(), allocation.getId())
                || allocation.getMode() != ContractorAllocationMode.LIVE
                || allocation.getSourceType() != ContractorAllocationSourceType.COMMON_INVOICE
                || !Objects.equals(allocation.getSourceId(), invoice.getId())
                || !Objects.equals(allocation.getCommonInvoiceId(), invoice.getId())
                || allocation.getRecipientProfile() == null
                || !contractorRecipient
                || !manualContractorRoute
                || !"MANUAL_MOBILE_BANK".equals(normalize(invoice.getPaymentRouteType())
                        .toUpperCase(java.util.Locale.ROOT))
                || invoice.getPaymentRouteManualType() != ManualPaymentType.MOBILE_BANK
                || invoice.getPaymentRouteAmountKopecks() == null
                || invoice.getPaymentRouteAmountKopecks() <= 0
                || allocation.getAmountKopecks() != invoice.getPaymentRouteAmountKopecks()
                || !commonInvoiceGenerationStillMatches(
                        invoice,
                        allocation,
                        Math.max(0L, invoice.getAmountKopecks() - invoice.getPaidKopecks())
                )
                || invoice.getPaidKopecks() >= invoice.getAmountKopecks()
                || !PUBLIC_PAYABLE_COMMON_STATUSES.contains(invoice.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платежный маршрут общего счета изменился и требует сверки"
            );
        }
    }

    /**
     * Releases a terminal predecessor synchronously before a replacement link
     * is routed. This closes the window where the periodic reconciler has not
     * yet observed an expired/cancelled link and its LIVE amount would still
     * reduce the recipient's available balance.
     */
    @Transactional
    public boolean releaseClosedPaymentLink(PaymentLink link) {
        if (link == null || link.getId() == null || !closedWithoutPayment(link.getStatus())) {
            return false;
        }
        Optional<ContractorPaymentAllocation> latestSnapshot = latest(
                ContractorAllocationSourceType.PAYMENT_LINK,
                link.getId()
        );
        if (latestSnapshot.isEmpty()) {
            return false;
        }
        ContractorPaymentAllocation allocation = lockAllocationProfileFirst(latestSnapshot.get().getId())
                .orElse(null);
        if (allocation == null
                || !Objects.equals(allocation.getId(), latestSnapshot.get().getId())
                || (allocation.getStatus() != ContractorAllocationStatus.RESERVED
                && allocation.getStatus() != ContractorAllocationStatus.CLIENT_REPORTED
                && allocation.getStatus() != ContractorAllocationStatus.PARTIALLY_CONFIRMED)) {
            return false;
        }
        ContractorAllocationStatus target = link.getStatus() == PaymentLinkStatus.EXPIRED
                ? ContractorAllocationStatus.EXPIRED
                : ContractorAllocationStatus.CANCELED;
        LocalDateTime observedAt = LocalDateTime.now();
        accountingService.recordRelease(
                allocation,
                target,
                ContractorPaymentEventTimePolicy.paymentLinkClosedAt(link, observedAt),
                "Платежная ссылка закрыта до выбора нового маршрута",
                "LIVE_LINK:CLOSED:" + link.getStatus()
        );
        allocationRepository.save(allocation);
        return true;
    }

    /** Releases a pristine common-invoice recipient reservation after an explicit
     * OWNER/ADMIN switch to a paper invoice. Provider/payment evidence is
     * checked by CommonBillingService before this method is called. */
    @Transactional
    public boolean releaseCommonInvoiceRouteForPaperInvoice(CommonInvoice invoice) {
        return releaseCommonInvoiceRouteForReplacement(
                invoice,
                "Получатель общего счёта заменён на бумажный счёт владельца",
                "LIVE_COMMON:PAPER_INVOICE_SWITCH"
        );
    }

    @Transactional
    public boolean releaseCommonInvoiceRouteForReplacement(
            CommonInvoice invoice,
            String reason,
            String eventKey
    ) {
        if (invoice == null || invoice.getId() == null || invoice.getContractorAllocationId() == null) {
            return false;
        }
        ContractorPaymentAllocation allocation = lockAllocationProfileFirst(invoice.getContractorAllocationId())
                .orElse(null);
        if (allocation == null
                || allocation.getMode() != ContractorAllocationMode.LIVE
                || allocation.getSourceType() != ContractorAllocationSourceType.COMMON_INVOICE
                || !Objects.equals(allocation.getSourceId(), invoice.getId())
                || (allocation.getStatus() != ContractorAllocationStatus.RESERVED
                && allocation.getStatus() != ContractorAllocationStatus.OWNER_FALLBACK)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Резерв общего счёта уже изменился и требует ручной сверки"
            );
        }
        if (allocation.getClientReportedAt() != null || allocation.getConfirmedKopecks() > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "По прежним реквизитам уже есть признаки оплаты; смена способа оплаты заблокирована"
            );
        }
        boolean released = accountingService.recordRelease(
                allocation,
                ContractorAllocationStatus.CANCELED,
                LocalDateTime.now(),
                reason == null || reason.isBlank()
                        ? "Получатель общего счёта заменён"
                        : reason,
                eventKey == null || eventKey.isBlank()
                        ? "LIVE_COMMON:ROUTE_REPLACED"
                        : eventKey
        );
        if (!released || allocation.getStatus() != ContractorAllocationStatus.CANCELED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не удалось безопасно закрыть прежний маршрут общего счёта"
            );
        }
        allocationRepository.save(allocation);
        return true;
    }

    @Transactional
    public ContractorPaymentAllocation reserveForPaymentLink(PaymentLink link) {
        return reserveForPaymentLink(link, PaymentLinkRoutePreference.AUTO);
    }

    @Transactional
    public ContractorPaymentAllocation reserveOwnerForPaymentLink(PaymentLink link) {
        return reserveForPaymentLink(link, PaymentLinkRoutePreference.OWNER);
    }

    @Transactional
    public ContractorPaymentAllocation reserveContractorForPaymentLink(PaymentLink link) {
        return reserveForPaymentLink(link, PaymentLinkRoutePreference.CONTRACTOR_ONLY);
    }

    private ContractorPaymentAllocation reserveForPaymentLink(
            PaymentLink link,
            PaymentLinkRoutePreference preference
    ) {
        if (!enabledForNewRoutes()) {
            return null;
        }
        if (link == null || link.getId() == null || link.getOrder() == null) {
            throw new IllegalArgumentException("Persisted payment link with order is required");
        }
        Order order = link.getOrder();
        long amount = link.getAmountKopecks();
        if (amount <= 0) {
            throw new IllegalArgumentException("Payment link amount must be positive");
        }
        Optional<ContractorPaymentAllocation> latest = latest(
                ContractorAllocationSourceType.PAYMENT_LINK,
                link.getId()
        );
        if (latest.isPresent() && !retryable(latest.get())) {
            return latest.get();
        }
        if (!rolloutStateService.lockAndCheckRoutingRequested()) {
            return null;
        }
        accountingPhaseService.lockAndPromoteForLiveRoute();

        boolean sourceSnapshotReady = paymentLinkSourceSnapshotReady(link, order, amount);
        ContractorPaymentRouteDecision decision;
        if (!sourceSnapshotReady) {
            // LIVE requisites are read back only through this immutable
            // generation. Falling back to owner here would silently bypass the
            // specialist->manager routing contract and expose the wrong payment
            // route to the client.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "payment_link_source_snapshot_not_ready"
            );
        } else if (preference == PaymentLinkRoutePreference.OWNER) {
            decision = ContractorPaymentRouteDecision.owner(
                    ContractorRoutingDecisionReason.CLIENT_REQUEST_OWNER_TBANK,
                    null,
                    null
            );
        } else if (!link.isShadowRouteCompanyRoutingAllowed()) {
            decision = ownerRequiredByCompany();
        } else {
            decision = selectRoutableProfile(
                    order.getWorker(),
                    effectiveManager(order),
                    amount,
                    null
            );
        }

        if (preference == PaymentLinkRoutePreference.CONTRACTOR_ONLY && decision.recipient() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сейчас ни специалист, ни менеджер не могут принять эту сумму: проверьте реквизиты, допуск и лимиты"
            );
        }

        ContractorPaymentAllocation allocation = base(
                ContractorAllocationSourceType.PAYMENT_LINK,
                link.getId(),
                amount,
                latest
        );
        allocation.setSourceGenerationSnapshot(link.getShadowRouteGeneration());
        allocation.setOrderId(order.getId());
        allocation.setCurrentWorkerId(order.getWorker() == null ? null : order.getWorker().getId());
        Manager manager = effectiveManager(order);
        allocation.setCurrentManagerId(manager == null ? null : manager.getId());
        return save(allocation, decision);
    }

    private enum PaymentLinkRoutePreference {
        AUTO,
        CONTRACTOR_ONLY,
        OWNER
    }

    private boolean paymentLinkSourceSnapshotReady(PaymentLink link, Order order, long amount) {
        return link.getShadowRouteGeneration() != null
                && !link.getShadowRouteGeneration().isBlank()
                && link.getShadowRoutePreparedAt() != null
                && Objects.equals(link.getShadowRouteOrderId(), order.getId())
                && Objects.equals(link.getShadowRouteAmountKopecks(), amount);
    }

    @Transactional
    public ContractorPaymentAllocation reserveForCommonInvoice(
            CommonInvoice invoice,
            List<Order> orders,
            Manager manager,
            long amount
    ) {
        return reserveForCommonInvoice(invoice, orders, manager, amount, CommonInvoiceRoutePreference.AUTO, false);
    }

    @Transactional
    public ContractorPaymentAllocation reserveContractorForCommonInvoice(
            CommonInvoice invoice,
            List<Order> orders,
            Manager manager,
            long amount
    ) {
        return reserveForCommonInvoice(
                invoice, orders, manager, amount, CommonInvoiceRoutePreference.CONTRACTOR_ONLY, true);
    }

    @Transactional
    public ContractorPaymentAllocation reserveOwnerForCommonInvoice(
            CommonInvoice invoice,
            List<Order> orders,
            Manager manager,
            long amount
    ) {
        return reserveForCommonInvoice(invoice, orders, manager, amount, CommonInvoiceRoutePreference.OWNER, true);
    }

    private ContractorPaymentAllocation reserveForCommonInvoice(
            CommonInvoice invoice,
            List<Order> orders,
            Manager manager,
            long amount,
            CommonInvoiceRoutePreference preference,
            boolean explicitReplacement
    ) {
        if (!enabledForNewRoutes()) {
            return null;
        }
        if (invoice == null || invoice.getId() == null) {
            throw new IllegalArgumentException("Persisted common invoice is required");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Common invoice amount must be positive");
        }
        Optional<ContractorPaymentAllocation> latest = latest(
                ContractorAllocationSourceType.COMMON_INVOICE,
                invoice.getId()
        );
        if (latest.isPresent()) {
            if (retryable(latest.get())) {
                if (!explicitReplacement) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Повторный маршрут общего счета требует явной сверки старой попытки"
                    );
                }
            } else {
                return latest.get();
            }
        }
        if (!rolloutStateService.lockAndCheckRoutingRequested()) {
            return null;
        }
        accountingPhaseService.lockAndPromoteForLiveRoute();
        if (!commonInvoiceSourceSnapshotReady(invoice, amount)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "common_invoice_source_snapshot_not_ready"
            );
        }

        List<Order> safeOrders = orders == null ? List.of() : orders;
        boolean completeWorkerSet = !safeOrders.isEmpty() && safeOrders.stream().allMatch(order ->
                order != null && order.getWorker() != null && order.getWorker().getId() != null
        );
        List<Worker> workers = safeOrders.stream()
                .filter(Objects::nonNull)
                .map(Order::getWorker)
                .filter(Objects::nonNull)
                .filter(worker -> worker.getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Worker::getId,
                        worker -> worker,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values().stream().toList();
        boolean contractorEligible = invoice.getShadowRouteGeneration() != null
                ? invoice.isShadowRouteContractorEligible()
                : commonInvoiceHasNoPriorPaymentEvidence(invoice);
        boolean companyAllowsRouting = invoice.getShadowRouteGeneration() != null
                ? invoice.isShadowRouteCompanyRoutingAllowed()
                : allCompaniesAllowContractorRouting(safeOrders);
        ContractorRoutingDecisionReason specialistPrecondition = commonSpecialistRejection(
                safeOrders,
                completeWorkerSet,
                workers
        );
        ContractorPaymentRouteDecision decision;
        if (preference == CommonInvoiceRoutePreference.OWNER) {
            decision = ContractorPaymentRouteDecision.owner(
                    ContractorRoutingDecisionReason.CLIENT_REQUEST_OWNER_TBANK,
                    null,
                    null
            );
        } else {
            decision = !companyAllowsRouting
                    ? ownerRequiredByCompany()
                    : contractorEligible
                    ? selectRoutableProfile(
                            completeWorkerSet && workers.size() == 1 ? workers.getFirst() : null,
                            manager,
                            amount,
                            specialistPrecondition
                    )
                    : ContractorPaymentRouteDecision.owner(
                            ContractorRoutingDecisionReason.PRIOR_PAYMENT_EVIDENCE,
                            null,
                            null
                    );
            if (preference == CommonInvoiceRoutePreference.CONTRACTOR_ONLY
                    && decision.recipient() == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Сейчас ни специалист, ни менеджер не могут принять сумму общего счёта: "
                                + "проверьте реквизиты, допуск и лимиты"
                );
            }
        }

        ContractorPaymentAllocation allocation = base(
                ContractorAllocationSourceType.COMMON_INVOICE,
                invoice.getId(),
                amount,
                latest
        );
        allocation.setSourceGenerationSnapshot(invoice.getShadowRouteGeneration());
        allocation.setCommonInvoiceId(invoice.getId());
        // Only money received after this route was frozen belongs to this
        // allocation. A partial prepayment that already existed must not be
        // credited to the newly selected recipient.
        allocation.setSourcePaidBaselineKopecks(Math.max(
                0L,
                invoice.getAmountKopecks() - amount
        ));
        allocation.setCurrentWorkerId(
                completeWorkerSet && workers.size() == 1 ? workers.getFirst().getId() : null
        );
        allocation.setCurrentManagerId(manager == null ? null : manager.getId());
        return save(allocation, decision);
    }

    private enum CommonInvoiceRoutePreference {
        AUTO,
        CONTRACTOR_ONLY,
        OWNER
    }

    private boolean commonInvoiceSourceSnapshotReady(CommonInvoice invoice, long amount) {
        return invoice != null
                && invoice.getShadowRouteGeneration() != null
                && !invoice.getShadowRouteGeneration().isBlank()
                && invoice.getShadowRoutePreparedAt() != null
                && Objects.equals(invoice.getShadowRouteAmountKopecks(), amount);
    }

    private Optional<ContractorPaymentAllocation> latest(
            ContractorAllocationSourceType sourceType,
            Long sourceId
    ) {
        return allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                sourceType,
                sourceId
        );
    }

    /**
     * All accounting mutations use one global row-lock order. The first read
     * is deliberately non-locking and may only identify the profile row; every
     * business decision is made from the subsequently locked allocation.
     */
    private Optional<ContractorPaymentAllocation> lockAllocationProfileFirst(Long allocationId) {
        if (allocationId == null) {
            return Optional.empty();
        }
        allocationRepository.findRecipientProfileIdById(allocationId)
                .ifPresent(profileRepository::findByIdForUpdate);
        return allocationRepository.findByIdForUpdate(allocationId);
    }

    private ContractorPaymentAllocation base(
            ContractorAllocationSourceType sourceType,
            Long sourceId,
            long amount,
            Optional<ContractorPaymentAllocation> latest
    ) {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(sourceType);
        allocation.setSourceId(sourceId);
        allocation.setAttemptNo(latest.map(value -> value.getAttemptNo() + 1).orElse(1));
        allocation.setAmountKopecks(amount);
        return allocation;
    }

    private ContractorPaymentAllocation save(
            ContractorPaymentAllocation allocation,
            ContractorPaymentRouteDecision decision
    ) {
        ContractorPaymentProfile recipient = decision.recipient();
        applyDecisionTrace(allocation, decision);
        if (recipient == null) {
            allocation.setRecipientType(ContractorRecipientType.OWNER);
            allocation.setStatus(ContractorAllocationStatus.OWNER_FALLBACK);
        } else {
            allocation.setRecipientProfile(recipient);
            allocation.setRecipientUserId(recipient.getUser().getId());
            allocation.setRecipientType(recipient.getRole() == ContractorRole.SPECIALIST
                    ? ContractorRecipientType.SPECIALIST
                    : ContractorRecipientType.MANAGER);
            allocation.setRecipientNameSnapshot(recipient.getRecipientName());
            allocation.setPaymentPhoneSnapshot(
                    ContractorPaymentTransferNumber.normalize(recipient.getPaymentPhone())
            );
            allocation.setBankNameSnapshot(recipient.getBankName());
            allocation.setPaymentCommentSnapshot(recipient.getPaymentComment());
            allocation.setAvailableBeforeKopecks(taskCapacityService.ordinaryAvailable(
                    recipient, ContractorAllocationMode.LIVE));
            allocation.setStatus(ContractorAllocationStatus.RESERVED);
            allocation.setReservedAt(LocalDateTime.now());
        }
        ContractorPaymentAllocation saved = allocationRepository.save(allocation);
        accountingService.recordReservation(saved);
        return saved;
    }

    private ContractorPaymentRouteDecision selectRoutableProfile(
            Object specialistSubject,
            Object managerSubject,
            long amount,
            ContractorRoutingDecisionReason specialistPrecondition
    ) {
        List<RouteCandidate> candidates = new ArrayList<>(2);
        addCandidate(candidates, specialistSubject, ContractorRole.SPECIALIST);
        addCandidate(candidates, managerSubject, ContractorRole.MANAGER);
        ContractorRoutingDecisionReason specialistRejection = specialistPrecondition;
        if (specialistRejection == null && user(specialistSubject) == null) {
            specialistRejection = ContractorRoutingDecisionReason.SPECIALIST_NOT_ASSIGNED;
        }
        ContractorRoutingDecisionReason managerRejection = user(managerSubject) == null
                ? ContractorRoutingDecisionReason.MANAGER_NOT_ASSIGNED
                : null;

        Map<Long, Boolean> activeByUser = new java.util.HashMap<>();
        candidates.stream()
                .map(RouteCandidate::userId)
                .distinct()
                .sorted()
                .forEach(userId -> activeByUser.put(
                        userId,
                        userRepository.lockContractorActiveFlag(userId).orElse(false)
                ));

        Map<RouteKey, Boolean> roleByCandidate = new java.util.HashMap<>();
        candidates.stream()
                .sorted(Comparator.comparing(RouteCandidate::userId)
                        .thenComparing(candidate -> roleLockOrder(candidate.role())))
                .forEach(candidate -> roleByCandidate.put(
                        candidate.key(),
                        !userRepository.lockContractorRoleIds(
                                candidate.userId(),
                                requiredRoleName(candidate.role())
                        ).isEmpty()
                ));

        List<Long> profileIds = candidates.stream()
                .map(RouteCandidate::profileId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        Map<Long, ContractorPaymentProfile> lockedProfiles = profileIds.isEmpty()
                ? Map.of()
                : profileRepository.findAllByIdForUpdate(profileIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                ContractorPaymentProfile::getId,
                                value -> value
                        ));

        for (RouteCandidate candidate : candidates) {
            ContractorPaymentProfile profile = lockedProfiles.get(candidate.profileId());
            ContractorRoutingDecisionReason rejection = candidateRejection(
                    candidate,
                    profile,
                    activeByUser.getOrDefault(candidate.userId(), false),
                    roleByCandidate.getOrDefault(candidate.key(), false),
                    amount
            );
            if (rejection != null) {
                if (candidate.role() == ContractorRole.SPECIALIST) {
                    specialistRejection = rejection;
                } else {
                    managerRejection = rejection;
                }
                continue;
            }
            if (candidate.role() == ContractorRole.SPECIALIST) {
                return ContractorPaymentRouteDecision.selected(
                        profile,
                        ContractorRoutingDecisionReason.SPECIALIST_SELECTED,
                        null
                );
            }
            return ContractorPaymentRouteDecision.selected(
                    profile,
                    ContractorRoutingDecisionReason.MANAGER_SELECTED,
                    specialistRejection
            );
        }
        ContractorRoutingDecisionReason decidingReason = managerRejection != null
                ? managerRejection
                : specialistRejection != null
                    ? specialistRejection
                    : ContractorRoutingDecisionReason.NO_ELIGIBLE_RECIPIENT;
        return ContractorPaymentRouteDecision.owner(
                decidingReason,
                specialistRejection,
                managerRejection
        );
    }

    private void addCandidate(List<RouteCandidate> candidates, Object subject, ContractorRole role) {
        User user = user(subject);
        if (user == null || user.getId() == null) {
            return;
        }
        Long profileId = profileRepository.findIdByUserIdAndRole(user.getId(), role).orElse(null);
        RouteCandidate candidate = new RouteCandidate(user.getId(), role, profileId);
        if (candidates.stream().noneMatch(existing -> existing.key().equals(candidate.key()))) {
            candidates.add(candidate);
        }
    }

    private ContractorRoutingDecisionReason candidateRejection(
            RouteCandidate candidate,
            ContractorPaymentProfile profile,
            boolean active,
            boolean roleAssigned,
            long amount
    ) {
        if (!active) {
            return ContractorRoutingDecisionReason.USER_INACTIVE;
        }
        if (!roleAssigned) {
            return ContractorRoutingDecisionReason.REQUIRED_ROLE_MISSING;
        }
        if (candidate.profileId() == null || profile == null) {
            return ContractorRoutingDecisionReason.PROFILE_NOT_FOUND;
        }
        if (!profile.isEnabled()) {
            return ContractorRoutingDecisionReason.PROFILE_DISABLED;
        }
        if (!profile.isLiveEnabled()) {
            return ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED;
        }
        if (profile.getRole() != candidate.role()
                || profile.getUser() == null
                || !Objects.equals(profile.getUser().getId(), candidate.userId())) {
            return ContractorRoutingDecisionReason.PROFILE_IDENTITY_MISMATCH;
        }
        if (normalize(profile.getRecipientName()).isBlank()
                || !ContractorPaymentTransferNumber.isValid(profile.getPaymentPhone())
                || normalize(profile.getBankName()).isBlank()) {
            return ContractorRoutingDecisionReason.RECIPIENT_DETAILS_INCOMPLETE;
        }
        if (taskCapacityService.ordinaryAvailable(
                profile, ContractorAllocationMode.LIVE) < amount) {
            return ContractorRoutingDecisionReason.INSUFFICIENT_AVAILABLE_BALANCE;
        }
        ContractorPaymentRoutingLimitService.RoutingLimitDecision limitDecision =
                routingLimitService.evaluate(profile, ContractorAllocationMode.LIVE, amount);
        return limitDecision.allowed() ? null : limitDecision.rejectionReason();
    }

    private ContractorRoutingDecisionReason commonSpecialistRejection(
            List<Order> orders,
            boolean completeWorkerSet,
            List<Worker> workers
    ) {
        if (orders == null || orders.isEmpty() || !completeWorkerSet) {
            return ContractorRoutingDecisionReason.SPECIALIST_NOT_ASSIGNED;
        }
        return workers.size() > 1
                ? ContractorRoutingDecisionReason.MIXED_SPECIALISTS
                : null;
    }

    private void applyDecisionTrace(
            ContractorPaymentAllocation allocation,
            ContractorPaymentRouteDecision decision
    ) {
        allocation.setRoutingDecisionReason(decision.decisionReason());
        allocation.setSpecialistRejectionReason(decision.specialistRejectionReason());
        allocation.setManagerRejectionReason(decision.managerRejectionReason());
    }

    private Manager effectiveManager(Order order) {
        return orderManagerResolver.resolveForRouting(order);
    }

    private boolean commonInvoiceHasNoPriorPaymentEvidence(CommonInvoice invoice) {
        return invoice != null
                && invoice.getPaidKopecks() == 0L
                && invoice.getPaidAt() == null
                && normalize(invoice.getTbankOrderId()).isBlank()
                && normalize(invoice.getTbankPaymentId()).isBlank()
                && (invoice.getTbankPaymentAmountKopecks() == null
                || invoice.getTbankPaymentAmountKopecks() == 0L)
                && invoice.getClientReportedAt() == null
                && invoice.getManualConfirmedAt() == null
                && normalize(invoice.getManualPaidBy()).isBlank()
                && normalize(invoice.getManualPaymentComment()).isBlank()
                && normalize(invoice.getManualPaymentReceiptUrl()).isBlank();
    }

    private boolean companyRoutingAllowed(Order order) {
        return order != null
                && order.getCompany() != null
                && order.getCompany().isContractorPaymentRoutingEnabled();
    }

    private boolean allCompaniesAllowContractorRouting(List<Order> orders) {
        return orders != null
                && !orders.isEmpty()
                && orders.stream().allMatch(this::companyRoutingAllowed);
    }

    private ContractorPaymentRouteDecision ownerRequiredByCompany() {
        return ContractorPaymentRouteDecision.owner(
                ContractorRoutingDecisionReason.COMPANY_REQUIRES_OWNER_PAYMENT,
                null,
                null
        );
    }

    private ContractorPaymentRouteDecision ownerRequiredBySourceSnapshot() {
        return ContractorPaymentRouteDecision.owner(
                ContractorRoutingDecisionReason.SOURCE_SNAPSHOT_NOT_READY,
                null,
                null
        );
    }

    private User user(Object subject) {
        if (subject instanceof Worker worker) {
            return worker.getUser();
        }
        if (subject instanceof Manager manager) {
            return manager.getUser();
        }
        return null;
    }

    private String requiredRoleName(ContractorRole role) {
        return role == ContractorRole.SPECIALIST ? "ROLE_WORKER" : "ROLE_MANAGER";
    }

    private int roleLockOrder(ContractorRole role) {
        return role == ContractorRole.SPECIALIST ? 0 : 1;
    }

    private boolean retryable(ContractorPaymentAllocation allocation) {
        return allocation != null && RETRYABLE_STATUSES.contains(allocation.getStatus());
    }

    private boolean closedWithoutPayment(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.EXPIRED
                || status == PaymentLinkStatus.CANCELED
                || status == PaymentLinkStatus.REJECTED
                || status == PaymentLinkStatus.FAILED;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record RouteKey(Long userId, ContractorRole role) {
    }

    private record RouteCandidate(Long userId, ContractorRole role, Long profileId) {
        private RouteKey key() {
            return new RouteKey(userId, role);
        }
    }

    public enum FrozenCommonRouteAction {
        KEEP,
        BLOCK_RECONCILIATION
    }

    public enum FrozenPaymentLinkAction {
        KEEP,
        START_NEW_ATTEMPT,
        BLOCK_RECONCILIATION
    }
}
