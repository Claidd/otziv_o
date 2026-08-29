package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractorPaymentShadowService {

    private static final long TERMINAL_RECHECK_SECONDS = 3_600L;

    private static final Set<ContractorAllocationStatus> RESERVING_STATUSES = EnumSet.of(
            ContractorAllocationStatus.RESERVED,
            ContractorAllocationStatus.CLIENT_REPORTED,
            ContractorAllocationStatus.PARTIALLY_CONFIRMED
    );
    private static final Set<ContractorAllocationStatus> UNPAID_RELEASABLE_STATUSES = EnumSet.of(
            ContractorAllocationStatus.RESERVED,
            ContractorAllocationStatus.CLIENT_REPORTED,
            ContractorAllocationStatus.PARTIALLY_CONFIRMED,
            ContractorAllocationStatus.OWNER_FALLBACK
    );
    private static final Set<ContractorAllocationStatus> RECONCILABLE_STATUSES = EnumSet.of(
            ContractorAllocationStatus.RESERVED,
            ContractorAllocationStatus.CLIENT_REPORTED,
            ContractorAllocationStatus.OWNER_FALLBACK,
            ContractorAllocationStatus.RELEASED_UNPAID,
            ContractorAllocationStatus.EXPIRED,
            ContractorAllocationStatus.CANCELED,
            ContractorAllocationStatus.CONFIRMED,
            ContractorAllocationStatus.SIMULATED_PAID,
            ContractorAllocationStatus.LATE_PAYMENT_AFTER_RELEASE,
            ContractorAllocationStatus.PARTIALLY_CONFIRMED,
            ContractorAllocationStatus.PARTIALLY_RETURNED,
            ContractorAllocationStatus.RETURN_AMOUNT_PENDING,
            ContractorAllocationStatus.RETURNED
    );
    private static final int RECONCILIATION_BATCH_SIZE = 250;
    private static final Set<PaymentLinkStatus> PAID_LINK_STATUSES = EnumSet.of(
            PaymentLinkStatus.TEST_CONFIRMED,
            PaymentLinkStatus.CONFIRMED,
            PaymentLinkStatus.AMOUNT_MISMATCH
    );
    private static final Set<PaymentLinkStatus> RETURNED_LINK_STATUSES = EnumSet.of(
            PaymentLinkStatus.REVERSED,
            PaymentLinkStatus.PARTIAL_REVERSED,
            PaymentLinkStatus.REFUNDED,
            PaymentLinkStatus.PARTIAL_REFUNDED
    );

    private final ContractorActualPaymentAttributionRepository actualPaymentAttributionRepository;
    private final ManualPaymentTaskContractorReturnBridge taskReturnBridge;
    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorPaymentProfileService profileService;
    private final ManualPaymentTaskContractorCapacityService taskCapacityService;
    private final ContractorPaymentRoutingLimitService routingLimitService;
    private final ContractorPaymentAccountingService accountingService;
    private final PaymentLinkRepository paymentLinkRepository;
    private final OrderRepository orderRepository;
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    private final CommonInvoicePaymentRefRepository commonInvoicePaymentRefRepository;
    private final AppSettingService appSettingService;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final EntityManager entityManager;
    private final UserRepository userRepository;
    private final ContractorOrderManagerResolver orderManagerResolver;

    /**
     * Captures immutable routing inputs before the source transaction commits.
     * The asynchronous SHADOW callback uses these fields instead of the order's
     * later worker/manager assignment.
     */
    public String preparePaymentLinkSource(PaymentLink link, LocalDateTime preparedAt) {
        try {
            if (!shadowEnabled()) {
                return null;
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Contractor SHADOW payment-link preparation gate failed; legacy route continues: code={}",
                    exception.getClass().getSimpleName()
            );
            return null;
        }
        return preparePaymentLinkSourceSnapshot(link, preparedAt, "SHADOW");
    }

    /**
     * LIVE routing uses the same immutable source snapshot as SHADOW routing,
     * but it must not depend on whether background shadow simulations are
     * enabled. Without this snapshot the live allocator cannot safely expose
     * contractor requisites to a client.
     */
    public String prepareLivePaymentLinkSource(PaymentLink link, LocalDateTime preparedAt) {
        return preparePaymentLinkSourceSnapshot(link, preparedAt, "LIVE");
    }

    private String preparePaymentLinkSourceSnapshot(
            PaymentLink link,
            LocalDateTime preparedAt,
            String mode
    ) {
        if (link == null || link.getOrder() == null) {
            return null;
        }
        Order order = link.getOrder();
        lockCompanyRoutingPolicy(order);
        Worker worker = order.getWorker();
        Manager manager;
        try {
            manager = effectiveManager(order);
        } catch (RuntimeException exception) {
            log.error(
                    "Contractor {} payment-link snapshot failed; route continues fail-closed when required: linkId={}, code={}",
                    mode,
                    link.getId(),
                    exception.getClass().getSimpleName()
            );
            return null;
        }
        String generation = UUID.randomUUID().toString();
        link.setShadowRouteGeneration(generation);
        link.setShadowRouteOrderId(order.getId());
        link.setShadowRouteWorkerId(worker == null ? null : worker.getId());
        link.setShadowRouteWorkerUserId(worker == null || worker.getUser() == null
                ? null
                : worker.getUser().getId());
        link.setShadowRouteManagerId(manager == null ? null : manager.getId());
        link.setShadowRouteManagerUserId(manager == null || manager.getUser() == null
                ? null
                : manager.getUser().getId());
        link.setShadowRouteAmountKopecks(Math.max(0L, link.getAmountKopecks()));
        link.setShadowRouteCompanyRoutingAllowed(companyRoutingAllowed(order));
        link.setShadowRoutePreparedAt(firstNonNull(preparedAt, LocalDateTime.now()));
        return generation;
    }

    public String prepareCommonInvoiceSource(
            CommonInvoice invoice,
            List<Order> orders,
            Manager manager,
            long amount,
            LocalDateTime preparedAt
    ) {
        try {
            if (!shadowEnabled()) {
                return null;
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Contractor SHADOW common-invoice preparation gate failed; legacy route continues: code={}",
                    exception.getClass().getSimpleName()
            );
            return null;
        }
        return prepareCommonInvoiceSourceSnapshot(invoice, orders, manager, amount, preparedAt, "SHADOW");
    }

    /**
     * LIVE common-invoice routing must capture the same immutable inputs even
     * when background SHADOW simulations are disabled.
     */
    public String prepareLiveCommonInvoiceSource(
            CommonInvoice invoice,
            List<Order> orders,
            Manager manager,
            long amount,
            LocalDateTime preparedAt
    ) {
        return prepareCommonInvoiceSourceSnapshot(invoice, orders, manager, amount, preparedAt, "LIVE");
    }

    private String prepareCommonInvoiceSourceSnapshot(
            CommonInvoice invoice,
            List<Order> orders,
            Manager manager,
            long amount,
            LocalDateTime preparedAt,
            String mode
    ) {
        if (invoice == null || invoice.getId() == null || amount <= 0) {
            return null;
        }
        List<Order> safeOrders = orders == null
                ? List.of()
                : orders.stream().filter(Objects::nonNull).toList();
        lockCompanyRoutingPolicies(safeOrders);
        boolean complete = !safeOrders.isEmpty() && safeOrders.stream().allMatch(order ->
                order.getWorker() != null
                        && order.getWorker().getId() != null
                        && order.getWorker().getUser() != null
                        && order.getWorker().getUser().getId() != null
        );
        List<Worker> workers = safeOrders.stream()
                .map(Order::getWorker)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toMap(
                        Worker::getId,
                        value -> value,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values().stream().toList();
        Worker homogeneousWorker = complete && workers.size() == 1 ? workers.getFirst() : null;
        String membershipHash;
        boolean contractorEligible;
        try {
            membershipHash = membershipHash(safeOrders);
            contractorEligible = commonInvoiceHasNoPriorPaymentEvidence(invoice)
                    && commonInvoicePaymentRefRepository
                            .findIdsByInvoiceIdForUpdate(invoice.getId())
                            .isEmpty();
        } catch (RuntimeException exception) {
            log.error(
                    "Contractor {} common-invoice snapshot failed; route continues fail-closed when required: invoiceId={}, code={}",
                    mode,
                    invoice.getId(),
                    exception.getClass().getSimpleName()
            );
            return null;
        }
        String generation = UUID.randomUUID().toString();
        invoice.setShadowRouteGeneration(generation);
        invoice.setShadowRouteWorkerState(homogeneousWorker == null
                ? "MIXED_OR_MISSING"
                : "HOMOGENEOUS");
        invoice.setShadowRouteWorkerId(homogeneousWorker == null ? null : homogeneousWorker.getId());
        invoice.setShadowRouteWorkerUserId(homogeneousWorker == null
                ? null
                : homogeneousWorker.getUser().getId());
        invoice.setShadowRouteManagerId(manager == null ? null : manager.getId());
        invoice.setShadowRouteManagerUserId(manager == null || manager.getUser() == null
                ? null
                : manager.getUser().getId());
        invoice.setShadowRouteAmountKopecks(amount);
        invoice.setShadowRouteMembershipHash(membershipHash);
        invoice.setShadowRouteContractorEligible(contractorEligible);
        invoice.setShadowRouteCompanyRoutingAllowed(allCompaniesAllowContractorRouting(safeOrders));
        invoice.setShadowRoutePreparedAt(firstNonNull(preparedAt, LocalDateTime.now()));
        return generation;
    }

    @Transactional
    public ContractorPaymentAllocation reserveForPaymentLink(PaymentLink link) {
        if (!shadowEnabled() || link == null || link.getId() == null || link.getOrder() == null) {
            return null;
        }
        long amount = Math.max(0L, link.getAmountKopecks());
        if (amount == 0L) {
            return null;
        }
        Optional<ContractorPaymentAllocation> existing = latestAllocation(
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.PAYMENT_LINK,
                link.getId()
        );
        if (existing.isPresent() && !canRetry(existing.get())) {
            return existing.get();
        }

        Order order = link.getOrder();
        boolean preparedSnapshot = link.getShadowRouteGeneration() != null
                && link.getShadowRoutePreparedAt() != null;
        if (preparedSnapshot
                && (!Objects.equals(link.getShadowRouteOrderId(), order.getId())
                || !Objects.equals(link.getShadowRouteAmountKopecks(), amount))) {
            return null;
        }
        ContractorPaymentAllocation allocation = baseAllocation(
                ContractorAllocationSourceType.PAYMENT_LINK,
                link.getId(),
                order,
                amount
        );
        allocation.setAttemptNo(nextAttempt(existing));
        boolean companyAllowsRouting = preparedSnapshot
                ? link.isShadowRouteCompanyRoutingAllowed()
                : companyRoutingAllowed(order);
        ContractorPaymentRouteDecision decision = companyAllowsRouting
                ? preparedSnapshot
                        ? selectRoutableProfileByUserIds(
                                link.getShadowRouteWorkerUserId(),
                                link.getShadowRouteManagerUserId(),
                                amount,
                                null
                        )
                        : selectRoutableProfile(order.getWorker(), effectiveManager(order), amount, null)
                : ownerRequiredByCompany();
        allocation.setSourceGenerationSnapshot(link.getShadowRouteGeneration());
        if (preparedSnapshot) {
            allocation.setCurrentWorkerId(link.getShadowRouteWorkerId());
            allocation.setCurrentManagerId(link.getShadowRouteManagerId());
        }
        applyRecipient(allocation, decision);
        if (decision.recipient() == null) {
            log.info("Тестовый маршрут счета: orderId={}, paymentLinkId={}, result=OWNER, amount={}",
                    order.getId(), link.getId(), amount);
        } else {
            log.info("Тестовый маршрут счета: orderId={}, paymentLinkId={}, result={}, userId={}, amount={}",
                    order.getId(), link.getId(), allocation.getRecipientType(), allocation.getRecipientUserId(), amount);
        }
        ContractorPaymentAllocation saved = allocationRepository.save(allocation);
        accountingService.recordReservation(saved);
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractorPaymentAllocation reserveForPaymentLinkId(Long paymentLinkId) {
        return reserveForPaymentLinkId(paymentLinkId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShadowReservationResult reserveForPaymentLinkIdOutcome(Long paymentLinkId) {
        if (!shadowEnabled()) {
            return new ShadowReservationResult(ShadowReservationOutcome.OUT_OF_SCOPE, null);
        }
        boolean sourceObserved = paymentLinkId != null && paymentLinkRepository.existsById(paymentLinkId);
        Optional<ContractorPaymentAllocation> before = paymentLinkId == null
                ? Optional.empty()
                : latestAllocation(
                        ContractorAllocationMode.SHADOW,
                        ContractorAllocationSourceType.PAYMENT_LINK,
                        paymentLinkId
                );
        ContractorPaymentAllocation allocation = reserveForPaymentLinkId(paymentLinkId, null);
        if (allocation != null) {
            ShadowReservationOutcome outcome = before
                    .filter(value -> Objects.equals(value.getId(), allocation.getId()))
                    .isPresent()
                    ? ShadowReservationOutcome.ALREADY_EXISTS
                    : ShadowReservationOutcome.CREATED;
            return new ShadowReservationResult(outcome, allocation);
        }
        return new ShadowReservationResult(
                sourceObserved
                        ? ShadowReservationOutcome.NOT_PREPARED_OR_INCONSISTENT
                        : ShadowReservationOutcome.OUT_OF_SCOPE,
                null
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractorPaymentAllocation reserveForPaymentLinkId(
            Long paymentLinkId,
            String expectedGeneration
    ) {
        if (!shadowEnabled()) {
            return null;
        }
        // The source row is the durable mutex for one public payment link.
        // It prevents two nodes from creating competing attempts for the same
        // evidence source.
        PaymentLink locked = paymentLinkRepository.findByIdForUpdate(paymentLinkId).orElse(null);
        if (locked == null) {
            return null;
        }
        entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
        if (expectedGeneration != null
                && !Objects.equals(expectedGeneration, locked.getShadowRouteGeneration())) {
            return null;
        }
        if (locked.getShadowRouteGeneration() == null
                || locked.getShadowRoutePreparedAt() == null
                || locked.getShadowRouteOrderId() == null
                || locked.getShadowRouteAmountKopecks() == null
                || locked.getShadowRouteAmountKopecks() <= 0
                || locked.getOrder() == null
                || !Objects.equals(locked.getShadowRouteOrderId(), locked.getOrder().getId())
                || locked.getAmountKopecks() != locked.getShadowRouteAmountKopecks()) {
            return null;
        }
        Optional<ContractorPaymentAllocation> existing = latestAllocation(
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.PAYMENT_LINK,
                paymentLinkId
        );
        if (existing.isPresent() && !Objects.equals(
                existing.get().getSourceGenerationSnapshot(),
                locked.getShadowRouteGeneration()
        )) {
            return null;
        }
        return reserveForPaymentLink(locked);
    }

    @Transactional
    public ContractorPaymentAllocation reserveForCommonInvoice(
            CommonInvoice invoice,
            List<Order> orders,
            Manager invoiceManager,
            long amount
    ) {
        if (!shadowEnabled() || invoice == null || invoice.getId() == null || amount <= 0) {
            return null;
        }
        Optional<ContractorPaymentAllocation> existing = latestAllocation(
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.COMMON_INVOICE,
                invoice.getId()
        );
        // A common invoice exposes aggregate paidKopecks rather than evidence
        // bound to one recipient attempt. Never manufacture attempt N+1 for
        // the same public token: a delayed transfer to attempt N could then be
        // attributed to the replacement recipient. A new invoice/source id is
        // required after explicit reconciliation.
        if (existing.isPresent()) {
            return existing.get();
        }

        List<Order> safeOrders = orders == null ? List.of() : orders;
        boolean allOrdersHaveWorker = !safeOrders.isEmpty() && safeOrders.stream().allMatch(order ->
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
                        java.util.LinkedHashMap::new
                ))
                .values().stream().toList();
        boolean preparedSnapshot = invoice.getShadowRouteGeneration() != null
                && invoice.getShadowRoutePreparedAt() != null;
        if (preparedSnapshot
                && (!Objects.equals(invoice.getShadowRouteAmountKopecks(), amount)
                || !Objects.equals(invoice.getShadowRouteMembershipHash(), membershipHash(safeOrders)))) {
            return null;
        }
        boolean contractorEligible = preparedSnapshot
                ? invoice.isShadowRouteContractorEligible()
                : commonInvoiceHasNoPriorPaymentEvidence(invoice);
        boolean companyAllowsRouting = preparedSnapshot
                ? invoice.isShadowRouteCompanyRoutingAllowed()
                : allCompaniesAllowContractorRouting(safeOrders);
        ContractorRoutingDecisionReason specialistPrecondition = commonSpecialistRejection(
                safeOrders,
                allOrdersHaveWorker,
                workers
        );
        ContractorPaymentRouteDecision decision = !companyAllowsRouting
                ? ownerRequiredByCompany()
                : contractorEligible
                ? preparedSnapshot
                        ? selectRoutableProfileByUserIds(
                                invoice.getShadowRouteWorkerUserId(),
                                invoice.getShadowRouteManagerUserId(),
                                amount,
                                specialistPrecondition
                        )
                        : selectRoutableProfile(
                                allOrdersHaveWorker && workers.size() == 1 ? workers.getFirst() : null,
                                invoiceManager,
                                amount,
                                specialistPrecondition
                        )
                : ContractorPaymentRouteDecision.owner(
                        ContractorRoutingDecisionReason.PRIOR_PAYMENT_EVIDENCE,
                        null,
                        null
                );

        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.COMMON_INVOICE);
        allocation.setSourceId(invoice.getId());
        allocation.setSourceGenerationSnapshot(invoice.getShadowRouteGeneration());
        allocation.setAttemptNo(nextAttempt(existing));
        allocation.setCommonInvoiceId(invoice.getId());
        allocation.setAmountKopecks(amount);
        allocation.setSourcePaidBaselineKopecks(Math.max(
                0L,
                invoice.getAmountKopecks() - amount
        ));
        allocation.setCurrentWorkerId(preparedSnapshot
                ? invoice.getShadowRouteWorkerId()
                : allOrdersHaveWorker && workers.size() == 1 ? workers.getFirst().getId() : null);
        allocation.setCurrentManagerId(preparedSnapshot
                ? invoice.getShadowRouteManagerId()
                : invoiceManager == null ? null : invoiceManager.getId());
        applyRecipient(allocation, decision);
        ContractorPaymentAllocation saved = allocationRepository.save(allocation);
        accountingService.recordReservation(saved);
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractorPaymentAllocation reserveForCommonInvoiceId(Long invoiceId) {
        return reserveForCommonInvoiceId(invoiceId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShadowReservationResult reserveForCommonInvoiceIdOutcome(Long invoiceId) {
        if (!shadowEnabled()) {
            return new ShadowReservationResult(ShadowReservationOutcome.OUT_OF_SCOPE, null);
        }
        boolean sourceObserved = invoiceId != null && commonInvoiceRepository.existsById(invoiceId);
        Optional<ContractorPaymentAllocation> before = invoiceId == null
                ? Optional.empty()
                : latestAllocation(
                        ContractorAllocationMode.SHADOW,
                        ContractorAllocationSourceType.COMMON_INVOICE,
                        invoiceId
                );
        ContractorPaymentAllocation allocation = reserveForCommonInvoiceId(invoiceId, null);
        if (allocation != null) {
            ShadowReservationOutcome outcome = before
                    .filter(value -> Objects.equals(value.getId(), allocation.getId()))
                    .isPresent()
                    ? ShadowReservationOutcome.ALREADY_EXISTS
                    : ShadowReservationOutcome.CREATED;
            return new ShadowReservationResult(outcome, allocation);
        }
        return new ShadowReservationResult(
                sourceObserved
                        ? ShadowReservationOutcome.NOT_PREPARED_OR_INCONSISTENT
                        : ShadowReservationOutcome.OUT_OF_SCOPE,
                null
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractorPaymentAllocation reserveForCommonInvoiceId(
            Long invoiceId,
            String expectedGeneration
    ) {
        if (!shadowEnabled()) {
            return null;
        }
        CommonSourcePrelude source = lockCommonSourceOrderFirst(invoiceId);
        if (source == null || source.invoice() == null) {
            return null;
        }
        CommonInvoice invoice = source.invoice();
        entityManager.refresh(invoice, LockModeType.PESSIMISTIC_WRITE);
        if (expectedGeneration != null
                && !Objects.equals(expectedGeneration, invoice.getShadowRouteGeneration())) {
            return null;
        }
        Long preparedAmount = invoice.getShadowRouteAmountKopecks();
        if (invoice.getShadowRouteGeneration() == null
                || invoice.getShadowRoutePreparedAt() == null
                || preparedAmount == null
                || preparedAmount <= 0
                || !Objects.equals(preparedAmount, invoice.getPaymentRouteAmountKopecks())
                || !Objects.equals(invoice.getShadowRouteMembershipHash(), membershipHash(source.orders()))) {
            return null;
        }
        Optional<ContractorPaymentAllocation> existing = latestAllocation(
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.COMMON_INVOICE,
                invoiceId
        );
        if (existing.isPresent() && !Objects.equals(
                existing.get().getSourceGenerationSnapshot(),
                invoice.getShadowRouteGeneration()
        )) {
            return null;
        }
        return reserveForCommonInvoice(invoice, source.orders(), null, preparedAmount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseForFinanciallyClosedOrder(Long orderId, String reason) {
        if (orderId == null) {
            return 0;
        }
        Order lockedOrder = orderRepository.findByIdForCounterUpdate(orderId).orElse(null);
        if (!orderReleasesReservation(lockedOrder)) {
            // Delayed after-commit callback after a status restoration must
            // not release a newer route.
            return 0;
        }
        List<ContractorPaymentAllocation> allocations = new ArrayList<>();
        // The toggle controls creation only. Existing SHADOW obligations must
        // always finish their lifecycle after rollout, promotion, or emergency stop.
        allocations.addAll(allocationRepository.findActiveByOrderId(
                orderId, ContractorAllocationMode.SHADOW, UNPAID_RELEASABLE_STATUSES
        ));
        allocations.addAll(allocationRepository.findActiveByOrderId(
                orderId, ContractorAllocationMode.LIVE, UNPAID_RELEASABLE_STATUSES
        ));
        // Lock every evidence source first in one canonical order. Taking a
        // second source after a profile would allow source->profile and
        // profile->source cycles between concurrent status transitions.
        allocations.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.getSourceType() != null && value.getSourceId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        value -> value.getSourceType().name() + ":" + value.getSourceId(),
                        value -> value,
                        (left, right) -> left,
                        java.util.TreeMap::new
                ))
                .values()
                .forEach(this::lockEvidenceSource);
        LocalDateTime now = databaseNow();
        List<ContractorPaymentAllocation> lockedAllocations = lockLatestAttemptsCanonical(allocations);
        List<ContractorPaymentAllocation> releasedAllocations = lockedAllocations.stream()
                .filter(allocation -> orderReleasesAllocation(lockedOrder, allocation))
                .filter(allocation -> accountingService.recordRelease(
                        allocation,
                        ContractorAllocationStatus.RELEASED_UNPAID,
                        now,
                        reason,
                        "ORDER_UNPAID:" + allocation.getId()
                ))
                .toList();
        allocationRepository.saveAll(releasedAllocations);
        return releasedAllocations.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseForUnpaidCommonInvoice(Long invoiceId, String reason) {
        if (invoiceId == null) {
            return 0;
        }
        // Called from after-commit callbacks as well as manual transitions.
        // Lock the evidence source before profile/allocation accounting locks.
        CommonSourcePrelude commonPrelude = lockCommonSourceOrderFirst(invoiceId);
        CommonInvoice lockedInvoice = commonPrelude == null ? null : commonPrelude.invoice();
        if (lockedInvoice == null || lockedInvoice.getStatus() != CommonInvoiceStatus.UNPAID) {
            return 0;
        }
        List<ContractorPaymentAllocation> candidates = new ArrayList<>();
        List<ContractorAllocationMode> modes =
                List.of(ContractorAllocationMode.SHADOW, ContractorAllocationMode.LIVE);
        for (ContractorAllocationMode mode : modes) {
            Optional<ContractorPaymentAllocation> candidate = latestAllocation(
                    mode, ContractorAllocationSourceType.COMMON_INVOICE, invoiceId
            );
            if (candidate.isEmpty()
                    || (!RESERVING_STATUSES.contains(candidate.get().getStatus())
                    && candidate.get().getStatus() != ContractorAllocationStatus.OWNER_FALLBACK)) {
                continue;
            }
            candidates.add(candidate.get());
        }
        int released = 0;
        for (ContractorPaymentAllocation allocation : lockLatestAttemptsCanonical(candidates)) {
            boolean currentLiveBinding = allocation.getMode() != ContractorAllocationMode.LIVE
                    || Objects.equals(lockedInvoice.getContractorAllocationId(), allocation.getId());
            if (!UNPAID_RELEASABLE_STATUSES.contains(allocation.getStatus())
                    || allocation.getSourceType() != ContractorAllocationSourceType.COMMON_INVOICE
                    || !Objects.equals(allocation.getSourceId(), invoiceId)
                    || !Objects.equals(allocation.getCommonInvoiceId(), invoiceId)
                    || !currentLiveBinding) {
                continue;
            }
            accountingService.recordRelease(
                    allocation,
                    ContractorAllocationStatus.RELEASED_UNPAID,
                    LocalDateTime.now(),
                    reason,
                    "COMMON_UNPAID:" + allocation.getId()
            );
            allocationRepository.save(allocation);
            released++;
        }
        return released;
    }

    @Transactional
    public boolean hasFrozenLiveRoute(Long orderId) {
        if (orderId == null) {
            return false;
        }
        return !allocationRepository.findActiveByOrderId(
                orderId,
                ContractorAllocationMode.LIVE,
                RESERVING_STATUSES
        ).isEmpty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordManualCardPaymentEvidence(
            Long originalLinkId,
            Long evidenceLinkId,
            LocalDateTime paidAt
    ) {
        if (originalLinkId == null || evidenceLinkId == null) {
            return false;
        }
        PaymentLink original = paymentLinkRepository.findByIdForUpdate(originalLinkId).orElse(null);
        if (original == null || Objects.equals(originalLinkId, evidenceLinkId)) {
            return false;
        }
        entityManager.refresh(original, LockModeType.PESSIMISTIC_WRITE);
        PaymentLink evidence = paymentLinkRepository.findByIdForUpdate(evidenceLinkId).orElse(null);
        if (evidence == null) {
            return false;
        }
        entityManager.refresh(evidence, LockModeType.PESSIMISTIC_WRITE);
        Long originalOrderId = original.getOrder() == null ? null : original.getOrder().getId();
        Long evidenceOrderId = evidence.getOrder() == null ? null : evidence.getOrder().getId();
        if (!Objects.equals(evidence.getContractorEvidenceOriginalLinkId(), originalLinkId)
                || evidence.getStatus() != PaymentLinkStatus.CONFIRMED
                || evidence.getPaymentMethod() != com.hunt.otziv.payments.model.PaymentMethod.MANUAL_MOBILE_BANK
                || evidence.getPaidAt() == null
                || !Objects.equals(originalOrderId, evidenceOrderId)
                || originalOrderId == null
                || original.getAmountKopecks() != evidence.getAmountKopecks()
                || !Objects.equals(evidence.getConfirmedAmountKopecks(), evidence.getAmountKopecks())
                || (paidAt != null && !Objects.equals(paidAt, evidence.getPaidAt()))) {
            return false;
        }
        if (paymentLinkRepository.existsContractorActualPaymentAttribution(originalLinkId, evidenceLinkId)) {
            return false;
        }
        List<ContractorPaymentAllocation> snapshots = new ArrayList<>();
        ContractorPaymentAllocation shadowAllocation = latestAllocation(
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.PAYMENT_LINK,
                originalLinkId
        ).orElse(null);
        if (shadowAllocation != null) {
            snapshots.add(shadowAllocation);
        }
        // A creation switch is only an emergency stop for new routes. Evidence
        // for an already frozen LIVE obligation must still be recorded.
        ContractorPaymentAllocation liveAllocation = latestAllocation(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.PAYMENT_LINK,
                originalLinkId
        ).orElse(null);
        if (liveAllocation != null) {
            snapshots.add(liveAllocation);
        }
        boolean changed = false;
        for (ContractorPaymentAllocation allocation : lockLatestAttemptsCanonical(snapshots)) {
            if (allocation.getSourceType() != ContractorAllocationSourceType.PAYMENT_LINK
                    || !Objects.equals(allocation.getSourceId(), originalLinkId)
                    || !Objects.equals(allocation.getOrderId(), originalOrderId)
                    || allocation.getAmountKopecks() != evidence.getAmountKopecks()) {
                continue;
            }
            changed |= recordManualEvidence(
                    allocation,
                    evidenceLinkId,
                    evidence.getPaidAt(),
                    allocation.getMode() == ContractorAllocationMode.SHADOW
            );
        }
        return changed;
    }

    private boolean recordManualEvidence(
            ContractorPaymentAllocation allocationSnapshot,
            Long evidenceLinkId,
            LocalDateTime paidAt,
            boolean simulated
    ) {
        if (allocationSnapshot == null || allocationSnapshot.getRecipientProfile() == null) {
            return false;
        }
        ContractorPaymentAllocation allocation = allocationSnapshot;
        boolean late = isReleased(allocation);
        boolean changed = accountingService.recordConfirmation(
                allocation,
                allocation.getAmountKopecks(),
                paidAt,
                late
                        ? "Ручная оплата подтверждена после отмены исходного банковского платежа"
                        : "Ручная оплата подтверждена",
                "MANUAL_EVIDENCE:" + evidenceLinkId,
                simulated,
                late
        );
        allocationRepository.save(allocation);
        return changed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractorPaymentAllocation recordObservedReturnAmount(
            Long allocationId,
            long returnedTotalKopecks,
            LocalDateTime effectiveAt,
            String externalRef,
            String reason
    ) {
        if (allocationId == null || returnedTotalKopecks < 0) {
            throw new IllegalArgumentException("Некорректная сумма возврата");
        }
        if (effectiveAt != null && effectiveAt.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Дата возврата не может быть в будущем");
        }
        ContractorPaymentAllocation snapshot = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new IllegalArgumentException("Назначение платежа не найдено"));
        boolean sourceAllocation = snapshot.getSourceType() == ContractorAllocationSourceType.PAYMENT_LINK
                || snapshot.getSourceType() == ContractorAllocationSourceType.COMMON_INVOICE;
        boolean actualPaymentReturn = snapshot.getSourceType() == ContractorAllocationSourceType.ACTUAL_PAYMENT;
        if ((!sourceAllocation && !actualPaymentReturn)
                || (sourceAllocation
                    && !canRecordObservedReturn(snapshot.getStatus())
                    && !canRecordActualPaymentReturn(snapshot.getStatus()))
                || (actualPaymentReturn && !canRecordActualPaymentReturn(snapshot.getStatus()))) {
            throw new IllegalArgumentException(
                    "Сумму возврата можно уточнить только для подтвержденного поступления работнику"
            );
        }
        ManualPaymentTaskContractorReturnBridge.Binding taskReturnBinding;
        if (snapshot.getSourceType() == ContractorAllocationSourceType.PAYMENT_LINK) {
            PaymentLink lockedLink = paymentLinkRepository.findByIdForUpdate(snapshot.getSourceId())
                    .orElse(null);
            taskReturnBinding = lockedLink == null
                    ? taskReturnBridge.lockArchivedSourceBinding(snapshot)
                    : taskReturnBridge.lockPaymentLinkBinding(snapshot, lockedLink);
        } else if (snapshot.getSourceType() == ContractorAllocationSourceType.COMMON_INVOICE) {
            CommonInvoice lockedInvoice = commonInvoiceRepository
                    .findByIdForUpdate(snapshot.getSourceId())
                    .orElse(null);
            taskReturnBinding = lockedInvoice == null
                    ? taskReturnBridge.lockArchivedSourceBinding(snapshot)
                    : taskReturnBridge.lockCommonInvoiceBinding(snapshot, lockedInvoice);
        } else {
            taskReturnBinding = taskReturnBridge.lockActualPaymentBinding(snapshot);
        }
        boolean exactTaskSourceReturn = sourceAllocation && taskReturnBinding.taskBound();
        boolean permittedSourceReturn = snapshot.getSourceType()
                == ContractorAllocationSourceType.PAYMENT_LINK
                && canRecordObservedReturn(snapshot.getStatus());
        permittedSourceReturn |= exactTaskSourceReturn
                && canRecordActualPaymentReturn(snapshot.getStatus());
        if (sourceAllocation && !permittedSourceReturn) {
            throw new IllegalArgumentException(
                    "Возврат подтвержденного назначения разрешен только для точного получателя платежного задания"
            );
        }
        ContractorPaymentAllocation allocation = lockLatestAttempt(snapshot);
        if (allocation == null) {
            throw new IllegalArgumentException("Назначение платежа больше не существует");
        }
        boolean lockedSourceReturn = allocation.getSourceType() == ContractorAllocationSourceType.PAYMENT_LINK
                && canRecordObservedReturn(allocation.getStatus());
        lockedSourceReturn |= (allocation.getSourceType() == ContractorAllocationSourceType.PAYMENT_LINK
                || allocation.getSourceType() == ContractorAllocationSourceType.COMMON_INVOICE)
                && exactTaskSourceReturn
                && canRecordActualPaymentReturn(allocation.getStatus());
        boolean lockedActualPaymentReturn = allocation.getSourceType() == ContractorAllocationSourceType.ACTUAL_PAYMENT
                && canRecordActualPaymentReturn(allocation.getStatus());
        if (!lockedSourceReturn && !lockedActualPaymentReturn) {
            throw new IllegalArgumentException(
                    "Сумму возврата можно уточнить только для подтвержденного поступления работнику"
            );
        }
        if (returnedTotalKopecks > allocation.getConfirmedKopecks()) {
            throw new IllegalArgumentException("Сумма возврата превышает подтвержденную сумму");
        }
        if (returnedTotalKopecks < allocation.getReturnedKopecks()) {
            throw new IllegalArgumentException("Итоговая сумма возврата не может уменьшаться");
        }
        accountingService.recordReturnTotal(
                allocation,
                returnedTotalKopecks,
                effectiveAt,
                reason,
                externalRef
        );
        taskReturnBridge.recordReturn(taskReturnBinding, allocation);
        return allocationRepository.save(allocation);
    }

    /** Reconciles a terminal source synchronously before retry routing. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reconcilePaymentLinkId(Long paymentLinkId) {
        if (paymentLinkId == null) {
            return 0;
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(paymentLinkId).orElse(null);
        if (link == null) {
            return 0;
        }
        entityManager.refresh(link, LockModeType.PESSIMISTIC_WRITE);
        taskReturnBridge.recordAuthoritativePaymentLinkReturn(link);
        List<ContractorPaymentAllocation> snapshots = new ArrayList<>();
        List<ContractorAllocationMode> modes =
                List.of(ContractorAllocationMode.SHADOW, ContractorAllocationMode.LIVE);
        for (ContractorAllocationMode mode : modes) {
            ContractorPaymentAllocation snapshot = latestAllocation(
                    mode, ContractorAllocationSourceType.PAYMENT_LINK, paymentLinkId
            ).orElse(null);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        if (isFinalAttributedPaymentLink(link)) {
            PaymentLinkActualReturnPlan actualReturnPlan = paymentLinkActualReturnPlan(link, snapshots);
            if (actualReturnPlan.attributionPresent()) {
                return reconcileAttributedPaymentLinkReturn(
                        link, snapshots, actualReturnPlan, LocalDateTime.now()
                );
            }
        }
        int updated = 0;
        Map<Long, ManualPaymentTaskContractorReturnBridge.Binding> taskReturnBindings = new LinkedHashMap<>();
        for (ContractorPaymentAllocation snapshot : snapshots) {
            taskReturnBindings.put(
                    snapshot.getId(),
                    taskReturnBridge.lockPaymentLinkBinding(snapshot, link)
            );
        }
        for (ContractorPaymentAllocation allocation : lockLatestAttemptsCanonical(snapshots)) {
            long version = allocation.getRowVersion();
            ContractorAllocationStatus status = allocation.getStatus();
            applyLinkStatus(allocation, link, LocalDateTime.now());
            taskReturnBridge.recordReturn(taskReturnBindings.get(allocation.getId()), allocation);
            allocationRepository.save(allocation);
            if (status != allocation.getStatus() || version != allocation.getRowVersion()) {
                updated++;
            }
        }
        return updated;
    }

    /** Reconciles one common-invoice source immediately after its payment
     * transaction commits. The periodic dispatcher remains the durable retry
     * path if this best-effort fast path cannot complete. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reconcileCommonInvoiceId(Long invoiceId) {
        if (invoiceId == null) {
            return 0;
        }
        List<ContractorPaymentAllocation> snapshots = new ArrayList<>();
        List<ContractorAllocationMode> modes =
                List.of(ContractorAllocationMode.SHADOW, ContractorAllocationMode.LIVE);
        for (ContractorAllocationMode mode : modes) {
            ContractorPaymentAllocation snapshot = latestAllocation(
                    mode,
                    ContractorAllocationSourceType.COMMON_INVOICE,
                    invoiceId
            ).orElse(null);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        int updated = 0;
        for (ContractorPaymentAllocation snapshot : snapshots.stream()
                .sorted(Comparator.comparing(ContractorPaymentAllocation::getId))
                .toList()) {
            long previousVersion = snapshot.getRowVersion();
            ContractorAllocationStatus previousStatus = snapshot.getStatus();
            ContractorPaymentAllocation reconciled = reconcileOneAllocation(snapshot.getId());
            if (reconciled != null
                    && (previousStatus != reconciled.getStatus()
                    || previousVersion != reconciled.getRowVersion())) {
                updated++;
            }
        }
        return updated;
    }

    /**
     * Reconciles exactly one claimed allocation in an independent transaction.
     * A poison source therefore cannot roll back the rest of the scheduler
     * page, and the evidence source is always locked before profile/allocation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractorPaymentAllocation reconcileAllocationId(Long allocationId) {
        return reconcileOneAllocation(allocationId);
    }

    /** Archive guard variant: keeps every source/profile/allocation lock in
     * the caller's archive transaction until copy/delete completes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractorPaymentAllocation reconcileAllocationForArchive(Long allocationId) {
        return reconcileOneAllocation(allocationId);
    }

    private ContractorPaymentAllocation reconcileOneAllocation(Long allocationId) {
        if (allocationId == null) {
            return null;
        }
        ContractorPaymentAllocation snapshot = allocationRepository.findById(allocationId).orElse(null);
        if (snapshot == null || !RECONCILABLE_STATUSES.contains(snapshot.getStatus())) {
            return snapshot;
        }
        LocalDateTime now = databaseNow();
        if (snapshot.getSourceType() == ContractorAllocationSourceType.ACTUAL_PAYMENT) {
            ContractorActualPaymentAttribution row = actualPaymentAttributionRepository
                    .findById(snapshot.getSourceId()).orElse(null);
            if (row == null || row.getSourceKind() != ContractorActualPaymentSourceKind.PAYMENT_LINK) {
                throw new ContractorReconciliationRequiredException(
                        "Фактическое назначение не связано с платежной ссылкой"
                );
            }
            PaymentSourcePrelude sourcePrelude = lockPaymentSourceOrderFirst(row.getSourceId(), row.getOrderId());
            if (sourcePrelude == null || sourcePrelude.link() == null) {
                throw new ContractorReconciliationRequiredException(
                        "Источник фактического назначения больше не существует"
                );
            }
            PaymentLink link = sourcePrelude.link();
            if (!isFinalAttributedPaymentLink(link)) {
                return snapshot;
            }
            if (RETURNED_LINK_STATUSES.contains(link.getStatus())) {
                taskReturnBridge.recordAuthoritativePaymentLinkReturn(link);
            }
            List<ContractorPaymentAllocation> sourceSnapshots = paymentLinkSourceSnapshots(link.getId());
            PaymentLinkActualReturnPlan plan = paymentLinkActualReturnPlan(link, sourceSnapshots);
            Map<Long, ContractorPaymentAllocation> reconciled = reconcileAttributedPaymentLinkReturnLocked(
                    link, sourceSnapshots, plan, now
            );
            return reconciled.getOrDefault(snapshot.getId(), snapshot);
        }
        if (snapshot.getSourceType() == ContractorAllocationSourceType.PAYMENT_LINK) {
            PaymentSourcePrelude sourcePrelude = lockPaymentSourceOrderFirst(
                    snapshot.getSourceId(),
                    snapshot.getOrderId()
            );
            if (sourcePrelude == null) {
                return null;
            }
            PaymentLink link = sourcePrelude.link();
            if (isFinalAttributedPaymentLink(link)) {
                if (RETURNED_LINK_STATUSES.contains(link.getStatus())) {
                    taskReturnBridge.recordAuthoritativePaymentLinkReturn(link);
                }
                List<ContractorPaymentAllocation> sourceSnapshots = paymentLinkSourceSnapshots(link.getId());
                PaymentLinkActualReturnPlan plan = paymentLinkActualReturnPlan(link, sourceSnapshots);
                if (plan.attributionPresent()) {
                    Map<Long, ContractorPaymentAllocation> reconciled = reconcileAttributedPaymentLinkReturnLocked(
                            link, sourceSnapshots, plan, now
                    );
                    return reconciled.getOrDefault(snapshot.getId(), snapshot);
                }
            }
            ManualPaymentTaskContractorReturnBridge.Binding taskReturnBinding = link == null
                    ? null : taskReturnBridge.lockPaymentLinkBinding(snapshot, link);
            ContractorPaymentAllocation allocation = lockLatestAttempt(snapshot);
            if (allocation == null) {
                return null;
            }
            allocation.setLastReconciledAt(now);
            if (link == null) {
                release(
                        allocation,
                        ContractorAllocationStatus.CANCELED,
                        "Источник платежа больше не существует",
                        now,
                        "LINK:SOURCE_MISSING"
                );
            } else {
                boolean releasedByOrderStatus = releaseIfOrderFinanciallyClosed(
                        allocation,
                        sourcePrelude.order(),
                        now
                );
                if (!releasedByOrderStatus
                        || PAID_LINK_STATUSES.contains(link.getStatus())
                        || RETURNED_LINK_STATUSES.contains(link.getStatus())) {
                    applyLinkStatus(allocation, link, now);
                }
            }
            taskReturnBridge.recordReturn(taskReturnBinding, allocation);
            clearReconciliationFailure(allocation);
            return allocationRepository.save(allocation);
        }
        if (snapshot.getSourceType() == ContractorAllocationSourceType.COMMON_INVOICE) {
            CommonSourcePrelude sourcePrelude = lockCommonSourceOrderFirst(snapshot.getSourceId());
            if (sourcePrelude == null) {
                return null;
            }
            CommonInvoice invoice = sourcePrelude.invoice();
            ManualPaymentTaskContractorReturnBridge.Binding taskReturnBinding = invoice == null
                    ? null : taskReturnBridge.lockCommonInvoiceBinding(snapshot, invoice);
            ContractorPaymentAllocation allocation = lockLatestAttempt(snapshot);
            if (allocation == null) {
                return null;
            }
            allocation.setLastReconciledAt(now);
            if (invoice == null) {
                release(
                        allocation,
                        ContractorAllocationStatus.CANCELED,
                        "Источник общего счета больше не существует",
                        now,
                        "COMMON:SOURCE_MISSING"
                );
            } else if (!hasFinalCommonActualRecipient(invoice.getId())) {
                applyCommonInvoiceStatus(allocation, invoice, now);
                releaseIfCommonInvoiceContainsUnpaidOrder(
                        allocation,
                        sourcePrelude.orders(),
                        now
                );
            }
            taskReturnBridge.recordReturn(taskReturnBinding, allocation);
            clearReconciliationFailure(allocation);
            return allocationRepository.save(allocation);
        }
        return snapshot;
    }

    private List<ContractorPaymentAllocation> paymentLinkSourceSnapshots(Long paymentLinkId) {
        List<ContractorPaymentAllocation> snapshots = new ArrayList<>();
        for (ContractorAllocationMode mode : List.of(
                ContractorAllocationMode.SHADOW, ContractorAllocationMode.LIVE)) {
            latestAllocation(mode, ContractorAllocationSourceType.PAYMENT_LINK, paymentLinkId)
                    .ifPresent(snapshots::add);
        }
        return snapshots;
    }

    private int reconcileAttributedPaymentLinkReturn(
            PaymentLink link,
            List<ContractorPaymentAllocation> sourceSnapshots,
            PaymentLinkActualReturnPlan plan,
            LocalDateTime now
    ) {
        Map<Long, AllocationBefore> before = new LinkedHashMap<>();
        sourceSnapshots.forEach(allocation -> before.put(
                allocation.getId(),
                new AllocationBefore(allocation.getStatus(), allocation.getReturnedKopecks())
        ));
        plan.actualSnapshots().forEach(allocation -> before.put(
                allocation.getId(),
                new AllocationBefore(allocation.getStatus(), allocation.getReturnedKopecks())
        ));
        Map<Long, ContractorPaymentAllocation> reconciled = reconcileAttributedPaymentLinkReturnLocked(
                link, sourceSnapshots, plan, now
        );
        return (int) reconciled.values().stream()
                .filter(allocation -> {
                    AllocationBefore previous = before.get(allocation.getId());
                    return previous != null && (previous.status() != allocation.getStatus()
                            || previous.returnedKopecks() != allocation.getReturnedKopecks());
                })
                .count();
    }

    private Map<Long, ContractorPaymentAllocation> reconcileAttributedPaymentLinkReturnLocked(
            PaymentLink link,
            List<ContractorPaymentAllocation> sourceSnapshots,
            PaymentLinkActualReturnPlan plan,
            LocalDateTime now
    ) {
        if (!plan.attributionPresent()) {
            throw new ContractorReconciliationRequiredException(
                    "У фактического поступления отсутствует точная атрибуция"
            );
        }
        Map<Long, ManualPaymentTaskContractorReturnBridge.Binding> bindings = new LinkedHashMap<>();
        for (ContractorPaymentAllocation snapshot : sourceSnapshots) {
            if (plan.reusedSourceAllocationIds().contains(snapshot.getId())
                    || plan.supersededSourceAllocationIds().contains(snapshot.getId())
                    && requiresSupersededSourceCorrection(snapshot)) {
                bindings.put(snapshot.getId(), taskReturnBridge.lockPaymentLinkBinding(snapshot, link));
            }
        }
        for (ContractorPaymentAllocation snapshot : plan.actualSnapshots()) {
            bindings.put(snapshot.getId(), taskReturnBridge.lockActualPaymentBinding(snapshot));
        }

        List<ContractorPaymentAllocation> allSnapshots = new ArrayList<>(sourceSnapshots);
        allSnapshots.addAll(plan.actualSnapshots());
        Map<Long, ContractorPaymentAllocation> locked = lockLatestAttemptsCanonical(allSnapshots).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ContractorPaymentAllocation::getId,
                        value -> value,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        requireExactActualReturnPlan(plan, locked);

        Map<Long, ContractorPaymentAllocation> saved = new LinkedHashMap<>();
        for (ContractorPaymentAllocation snapshot : sourceSnapshots) {
            ContractorPaymentAllocation allocation = locked.get(snapshot.getId());
            if (allocation == null) {
                throw new ContractorReconciliationRequiredException(
                        "Исходное назначение платежа изменилось во время сверки"
                );
            }
            allocation.setLastReconciledAt(now);
            if (plan.reusedSourceAllocationIds().contains(allocation.getId())) {
                applyLinkStatus(allocation, link, now);
                taskReturnBridge.recordReturn(bindings.get(allocation.getId()), allocation);
            } else if (plan.supersededSourceAllocationIds().contains(allocation.getId())
                    && correctSupersededSourceAllocation(allocation, link, now)) {
                taskReturnBridge.recordReturn(bindings.get(allocation.getId()), allocation);
            }
            clearReconciliationFailure(allocation);
            saved.put(allocation.getId(), allocationRepository.save(allocation));
        }
        for (ContractorPaymentAllocation snapshot : plan.actualSnapshots()) {
            ContractorPaymentAllocation allocation = locked.get(snapshot.getId());
            if (allocation == null) {
                throw new ContractorReconciliationRequiredException(
                        "Фактическое назначение платежа изменилось во время сверки"
                );
            }
            allocation.setLastReconciledAt(now);
            applyLinkStatus(allocation, link, now);
            taskReturnBridge.recordReturn(bindings.get(allocation.getId()), allocation);
            clearReconciliationFailure(allocation);
            saved.put(allocation.getId(), allocationRepository.save(allocation));
        }
        return saved;
    }

    /**
     * A final actual-recipient row is authoritative. Once the money is known
     * to have reached somebody else, a delayed source-status reconciliation
     * must never confirm the frozen original recipient again.
     */
    private boolean correctSupersededSourceAllocation(
            ContractorPaymentAllocation allocation,
            PaymentLink link,
            LocalDateTime now
    ) {
        long netConfirmed = Math.max(
                0L,
                allocation.getConfirmedKopecks() - allocation.getReturnedKopecks()
        );
        if (netConfirmed > 0L) {
            return accountingService.recordReturnTotal(
                    allocation,
                    allocation.getConfirmedKopecks(),
                    paidAt(link, now),
                    "Подтверждение исходному получателю отменено: деньги фактически получил другой получатель",
                    "LINK:ACTUAL_RECIPIENT_SUPERSEDED:RETURNED:" + allocation.getConfirmedKopecks()
            );
        }
        if (UNPAID_RELEASABLE_STATUSES.contains(allocation.getStatus())
                || allocation.getStatus() == ContractorAllocationStatus.OWNER_FALLBACK) {
            return accountingService.recordRelease(
                    allocation,
                    ContractorAllocationStatus.CANCELED,
                    paidAt(link, now),
                    "Исходное назначение отменено: деньги фактически получил другой получатель",
                    "LINK:ACTUAL_RECIPIENT_SUPERSEDED:CANCELED"
            );
        }
        return false;
    }

    private boolean requiresSupersededSourceCorrection(ContractorPaymentAllocation allocation) {
        return allocation != null && (
                allocation.getConfirmedKopecks() > allocation.getReturnedKopecks()
                        || UNPAID_RELEASABLE_STATUSES.contains(allocation.getStatus())
                        || allocation.getStatus() == ContractorAllocationStatus.OWNER_FALLBACK
        );
    }

    private boolean isFinalAttributedPaymentLink(PaymentLink link) {
        return link != null && (PAID_LINK_STATUSES.contains(link.getStatus())
                || RETURNED_LINK_STATUSES.contains(link.getStatus()));
    }

    private PaymentLinkActualReturnPlan paymentLinkActualReturnPlan(
            PaymentLink link,
            Collection<ContractorPaymentAllocation> sourceSnapshots
    ) {
        List<ContractorActualPaymentAttribution> rows = actualPaymentAttributionRepository
                .findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                        ContractorActualPaymentSourceKind.PAYMENT_LINK, link.getId()
                );
        if (rows.isEmpty()) {
            return PaymentLinkActualReturnPlan.none();
        }
        long attributedTotal = 0L;
        Map<Long, ContractorPaymentAllocation> sourceById = (sourceSnapshots == null
                ? List.<ContractorPaymentAllocation>of() : sourceSnapshots).stream()
                .filter(Objects::nonNull)
                .filter(allocation -> allocation.getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        ContractorPaymentAllocation::getId,
                        value -> value,
                        (left, right) -> left
                ));
        List<ContractorPaymentAllocation> actualSnapshots = new ArrayList<>();
        Map<Long, ContractorActualPaymentAttribution> actualRowsByAllocationId = new LinkedHashMap<>();
        Set<Long> reusedSourceAllocationIds = new java.util.LinkedHashSet<>();
        Set<Long> supersededSourceAllocationIds = new java.util.LinkedHashSet<>();
        Set<Long> rowIds = new java.util.HashSet<>();

        for (ContractorActualPaymentAttribution row : rows) {
            requirePaymentLinkAttributionRow(row, link);
            if (!rowIds.add(row.getId())) {
                throw new ContractorReconciliationRequiredException(
                        "Атрибуция фактического поступления продублирована"
                );
            }
            attributedTotal = Math.addExact(attributedTotal, row.getAmountKopecks());
            ContractorPaymentAllocation actual = latestAllocation(
                    row.getAccountingMode(), ContractorAllocationSourceType.ACTUAL_PAYMENT, row.getId()
            ).orElse(null);
            ContractorPaymentAllocation original = row.getOriginalAllocationId() == null
                    ? null : sourceById.get(row.getOriginalAllocationId());
            boolean contractorDestination = row.getActualRecipientProfileId() != null
                    && row.getActualRecipientType() != null
                    && row.getActualRecipientType() != ContractorRecipientType.OWNER
                    && row.getActualCashDestinationKind() != ContractorCashDestinationKind.OWNER;
            if (actual != null) {
                if (!contractorDestination) {
                    throw new ContractorReconciliationRequiredException(
                            "Для оплаты владельцу обнаружено лишнее фактическое назначение"
                    );
                }
                requireActualPaymentAllocation(row, actual);
                actualSnapshots.add(actual);
                actualRowsByAllocationId.put(actual.getId(), row);
                if (row.getOriginalAllocationId() != null) {
                    supersededSourceAllocationIds.add(row.getOriginalAllocationId());
                }
                continue;
            }
            if (contractorDestination) {
                if (original == null) {
                    throw new ContractorReconciliationRequiredException(
                            "Не найдено подтвержденное назначение фактическому получателю"
                    );
                }
                requireReusedPaymentLinkAllocation(row, original);
                reusedSourceAllocationIds.add(original.getId());
            } else if (row.getOriginalAllocationId() != null) {
                supersededSourceAllocationIds.add(row.getOriginalAllocationId());
            }
        }
        if (attributedTotal != confirmedAmount(link)) {
            throw new ContractorReconciliationRequiredException(
                    "Сумма фактических получателей не совпадает с подтвержденной суммой платежа"
            );
        }
        if (!java.util.Collections.disjoint(reusedSourceAllocationIds, supersededSourceAllocationIds)) {
            throw new ContractorReconciliationRequiredException(
                    "Исходное назначение одновременно помечено использованным и замененным"
            );
        }
        return new PaymentLinkActualReturnPlan(
                true,
                List.copyOf(actualSnapshots),
                Map.copyOf(actualRowsByAllocationId),
                Set.copyOf(reusedSourceAllocationIds),
                Set.copyOf(supersededSourceAllocationIds)
        );
    }

    private void requireExactActualReturnPlan(
            PaymentLinkActualReturnPlan plan,
            Map<Long, ContractorPaymentAllocation> locked
    ) {
        for (Map.Entry<Long, ContractorActualPaymentAttribution> entry
                : plan.actualRowsByAllocationId().entrySet()) {
            ContractorPaymentAllocation allocation = locked.get(entry.getKey());
            if (allocation == null) {
                throw new ContractorReconciliationRequiredException(
                        "Фактическое назначение платежа больше не существует"
                );
            }
            requireActualPaymentAllocation(entry.getValue(), allocation);
        }
    }

    private void requirePaymentLinkAttributionRow(
            ContractorActualPaymentAttribution row,
            PaymentLink link
    ) {
        if (row == null || row.getId() == null || row.getId() <= 0L
                || row.getSourceKind() != ContractorActualPaymentSourceKind.PAYMENT_LINK
                || !Objects.equals(row.getSourceId(), link.getId())
                || row.getAccountingMode() == null
                || row.getAmountKopecks() <= 0L
                || row.getActualCashDestinationKind() == null) {
            throw new ContractorReconciliationRequiredException(
                    "Атрибуция фактического поступления повреждена"
            );
        }
    }

    private void requireActualPaymentAllocation(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation
    ) {
        Long profileId = allocation.getRecipientProfile() == null
                ? null : allocation.getRecipientProfile().getId();
        Long expectedTaskId = row.getActualCashDestinationKind()
                == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                ? row.getActualManualPaymentTaskId() : null;
        if (allocation.getMode() != row.getAccountingMode()
                || allocation.getSourceType() != ContractorAllocationSourceType.ACTUAL_PAYMENT
                || !Objects.equals(allocation.getSourceId(), row.getId())
                || !Objects.equals(profileId, row.getActualRecipientProfileId())
                || allocation.getRecipientType() != row.getActualRecipientType()
                || !Objects.equals(allocation.getManualPaymentTaskId(), expectedTaskId)
                || allocation.getAmountKopecks() != row.getAmountKopecks()) {
            throw new ContractorReconciliationRequiredException(
                    "Фактическое назначение расходится с неизменяемой атрибуцией"
            );
        }
    }

    private void requireReusedPaymentLinkAllocation(
            ContractorActualPaymentAttribution row,
            ContractorPaymentAllocation allocation
    ) {
        Long profileId = allocation.getRecipientProfile() == null
                ? null : allocation.getRecipientProfile().getId();
        Long expectedTaskId = row.getActualCashDestinationKind()
                == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                ? row.getActualManualPaymentTaskId() : null;
        if (allocation.getMode() != row.getAccountingMode()
                || allocation.getSourceType() != ContractorAllocationSourceType.PAYMENT_LINK
                || !Objects.equals(allocation.getSourceId(), row.getSourceId())
                || !Objects.equals(allocation.getId(), row.getOriginalAllocationId())
                || !Objects.equals(profileId, row.getActualRecipientProfileId())
                || allocation.getRecipientType() != row.getActualRecipientType()
                || !Objects.equals(allocation.getManualPaymentTaskId(), expectedTaskId)
                || allocation.getAmountKopecks() != row.getAmountKopecks()) {
            throw new ContractorReconciliationRequiredException(
                    "Повторно использованное назначение расходится с атрибуцией"
            );
        }
    }

    private boolean hasFinalCommonActualRecipient(Long invoiceId) {
        return invoiceId != null && actualPaymentAttributionRepository
                .existsBySourceKindAndSourceIdAndEvidenceId(
                        ContractorActualPaymentSourceKind.COMMON_INVOICE,
                        invoiceId,
                        null
                );
    }

    /** Test/backfill entry point. Production scheduling is handled by the
     * claim-based per-allocation dispatcher above. */
    @Transactional
    public void reconcilePaymentLinks() {
        // Creation switches never suspend cleanup of already persisted obligations.
        reconcilePaymentLinks(ContractorAllocationMode.SHADOW);
        reconcilePaymentLinks(ContractorAllocationMode.LIVE);
    }

    private void reconcilePaymentLinks(ContractorAllocationMode mode) {
        LocalDateTime now = databaseNow();
        List<ContractorPaymentAllocation> allocations = allocationRepository.findPaymentLinksForReconciliation(
                mode,
                RECONCILABLE_STATUSES,
                UNPAID_RELEASABLE_STATUSES,
                now,
                now.minusSeconds(TERMINAL_RECHECK_SECONDS),
                PageRequest.of(0, RECONCILIATION_BATCH_SIZE)
        );
        allocations.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ContractorPaymentAllocation::getSourceId)
                        .thenComparing(ContractorPaymentAllocation::getId))
                .forEach(snapshot -> reconcileOneAllocation(snapshot.getId()));
        reconcileCommonInvoices(mode);
    }

    private void reconcileCommonInvoices(ContractorAllocationMode mode) {
        LocalDateTime now = databaseNow();
        List<ContractorPaymentAllocation> allocations = allocationRepository.findCommonInvoicesForReconciliation(
                mode,
                RECONCILABLE_STATUSES,
                UNPAID_RELEASABLE_STATUSES,
                now,
                now.minusSeconds(TERMINAL_RECHECK_SECONDS),
                PageRequest.of(0, RECONCILIATION_BATCH_SIZE)
        );
        if (allocations.isEmpty()) {
            return;
        }
        allocations.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ContractorPaymentAllocation::getSourceId)
                        .thenComparing(ContractorPaymentAllocation::getId))
                .forEach(snapshot -> reconcileOneAllocation(snapshot.getId()));
    }

    private LocalDateTime databaseNow() {
        LocalDateTime now = allocationRepository.currentDatabaseTime();
        if (now == null) {
            throw new IllegalStateException("Contractor reconciliation database clock is unavailable");
        }
        return now;
    }

    private void applyCommonInvoiceStatus(
            ContractorPaymentAllocation allocation,
            CommonInvoice invoice,
            LocalDateTime now
    ) {
        if (invoice.getClientReportedAt() != null
                && allocation.getConfirmedKopecks() == 0L
                && RESERVING_STATUSES.contains(allocation.getStatus())) {
            accountingService.recordClientReported(
                    allocation,
                    invoice.getClientReportedAt(),
                    "Клиент сообщил об оплате общего счета",
                    "COMMON:CLIENT_REPORTED"
            );
        }
        // Do not cap reliable bank/manual evidence at the routed amount. An
        // overpayment is still money received by this recipient and must be
        // visible as credit/exposure overrun instead of disappearing.
        long observedPaid = Math.max(
                0L,
                Math.subtractExact(invoice.getPaidKopecks(), allocation.getSourcePaidBaselineKopecks())
        );
        long currentNet = Math.max(0L, allocation.getConfirmedKopecks() - allocation.getReturnedKopecks());
        LocalDateTime effectivePaidAt = firstNonNull(
                invoice.getPaidAt(),
                invoice.getManualConfirmedAt(),
                invoice.getUpdatedAt(),
                now
        );
        if (observedPaid > currentNet) {
            long confirmedTarget = Math.addExact(
                    allocation.getConfirmedKopecks(),
                    observedPaid - currentNet
            );
            boolean late = isReleased(allocation);
            accountingService.recordConfirmation(
                    allocation,
                    confirmedTarget,
                    effectivePaidAt,
                    late
                            ? "Оплата общего счета подтверждена после освобождения резерва"
                            : "Подтверждена оплата общего счета",
                    "COMMON:CONFIRMED_TOTAL:" + confirmedTarget,
                    allocation.getMode() == ContractorAllocationMode.SHADOW,
                    late
            );
        } else if (observedPaid < currentNet) {
            long returnedTarget = Math.addExact(
                    allocation.getReturnedKopecks(),
                    currentNet - observedPaid
            );
            accountingService.recordReturnTotal(
                    allocation,
                    returnedTarget,
                    firstNonNull(invoice.getUpdatedAt(), invoice.getClosedAt(), now),
                    "Уменьшилась фактически подтвержденная сумма общего счета",
                    "COMMON:RETURNED_TOTAL:" + returnedTarget
            );
        }
        if (invoice.getStatus() == CommonInvoiceStatus.UNPAID) {
            release(allocation, ContractorAllocationStatus.RELEASED_UNPAID,
                    "Общий счет не оплачен", now, "COMMON:UNPAID");
        } else if (invoice.getStatus() == CommonInvoiceStatus.ARCHIVED
                || invoice.getStatus() == CommonInvoiceStatus.BAN
                || invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            release(allocation, ContractorAllocationStatus.CANCELED,
                    "Общий счет закрыт", now, "COMMON:CLOSED:" + invoice.getStatus());
        }
    }

    private boolean releaseIfCommonInvoiceContainsUnpaidOrder(
            ContractorPaymentAllocation allocation,
            Long invoiceId,
            LocalDateTime observedAt
    ) {
        if (invoiceId == null) {
            return false;
        }
        List<Order> orders = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId).stream()
                .map(item -> item.getOrder())
                .filter(Objects::nonNull)
                .toList();
        return releaseIfCommonInvoiceContainsUnpaidOrder(allocation, orders, observedAt);
    }

    private boolean releaseIfCommonInvoiceContainsUnpaidOrder(
            ContractorPaymentAllocation allocation,
            Collection<Order> orders,
            LocalDateTime observedAt
    ) {
        if (allocation == null || !UNPAID_RELEASABLE_STATUSES.contains(allocation.getStatus())) {
            return false;
        }
        Order closingOrder = orders == null ? null : orders.stream()
                .filter(Objects::nonNull)
                .filter(order -> orderReleasesAllocation(order, allocation))
                .sorted(Comparator.comparingInt(order ->
                        "Бан".equalsIgnoreCase(statusTitle(order)) ? 0 : 1))
                .findFirst()
                .orElse(null);
        if (closingOrder == null) {
            return false;
        }
        boolean banned = "Бан".equalsIgnoreCase(statusTitle(closingOrder));
        accountingService.recordRelease(
                allocation,
                ContractorAllocationStatus.RELEASED_UNPAID,
                observedAt,
                banned
                        ? "Один из заказов общего счета находится в статусе «Бан»"
                        : "Один из заказов общего счета находится в статусе «Не оплачено»",
                banned ? "COMMON:ORDER_BANNED" : "COMMON:ORDER_UNPAID"
        );
        return true;
    }

    private void applyLinkStatus(
            ContractorPaymentAllocation allocation,
            PaymentLink link,
            LocalDateTime now
    ) {
        PaymentLinkStatus linkStatus = link.getStatus();
        if (PAID_LINK_STATUSES.contains(linkStatus)) {
            if (linkStatus == PaymentLinkStatus.AMOUNT_MISMATCH
                    && (link.getConfirmedAmountKopecks() == null
                    || link.getConfirmedAmountKopecks() <= 0)) {
                throw new ContractorReconciliationRequiredException(
                        "Банк сообщил расхождение без надежной фактической суммы"
                );
            }
            boolean late = isReleased(allocation);
            long confirmed = confirmedAmount(link);
            String linkAudit = normalize(link.getLastError());
            int sourceAuditIndex = linkAudit.indexOf("contractor_source_confirmation;");
            String sourceAudit = sourceAuditIndex >= 0
                    ? linkAudit.substring(sourceAuditIndex)
                    : null;
            accountingService.recordConfirmation(
                    allocation,
                    confirmed,
                    paidAt(link, now),
                    sourceAudit != null
                            ? (late ? "После освобождения резерва; " : "") + sourceAudit
                            : late
                                ? "Оплата подтверждена после освобождения тестового резерва"
                                : "Оплата подтверждена",
                    "LINK:CONFIRMED:" + confirmed,
                    allocation.getMode() == ContractorAllocationMode.SHADOW,
                    late
            );
            return;
        }
        if (RETURNED_LINK_STATUSES.contains(linkStatus)) {
            long confirmed = confirmedAmount(link);
            boolean late = isReleased(allocation);
            accountingService.recordConfirmation(
                    allocation,
                    confirmed,
                    paidAt(link, now),
                    "Оплата, предшествовавшая возврату",
                    "LINK:IMPLIED_CONFIRMATION:" + confirmed,
                    allocation.getMode() == ContractorAllocationMode.SHADOW,
                    late
            );
            LocalDateTime returnedAt = firstNonNull(link.getUpdatedAt(), now);
            if (linkStatus == PaymentLinkStatus.REVERSED || linkStatus == PaymentLinkStatus.REFUNDED) {
                accountingService.recordReturnTotal(
                        allocation,
                        allocation.getConfirmedKopecks(),
                        returnedAt,
                        "Полный возврат или отмена поступления",
                        "LINK:RETURN:" + linkStatus + ":" + allocation.getConfirmedKopecks()
                );
            } else {
                accountingService.recordReturnAmountPending(
                        allocation,
                        returnedAt,
                        "Банк сообщил о частичном возврате без сохраненной суммы; требуется сверка",
                        "LINK:RETURN_AMOUNT_PENDING:" + linkStatus + ":V:" + sourceVersion(link)
                );
            }
            return;
        }
        if (linkStatus == PaymentLinkStatus.MANUAL_REPORTED) {
            accountingService.recordClientReported(
                    allocation,
                    firstNonNull(link.getManualReportedAt(), now),
                    "Клиент сообщил об оплате",
                    "LINK:MANUAL_REPORTED"
            );
            return;
        }
        if (linkStatus == PaymentLinkStatus.EXPIRED) {
            release(allocation, ContractorAllocationStatus.EXPIRED,
                    "Срок счета истек",
                    ContractorPaymentEventTimePolicy.paymentLinkClosedAt(link, now),
                    "LINK:EXPIRED");
        } else if (linkStatus == PaymentLinkStatus.CANCELED) {
            release(allocation, ContractorAllocationStatus.CANCELED,
                    "Платеж отменен",
                    ContractorPaymentEventTimePolicy.paymentLinkClosedAt(link, now),
                    "LINK:CANCELED");
        } else if (linkStatus == PaymentLinkStatus.REJECTED || linkStatus == PaymentLinkStatus.FAILED) {
            release(allocation, ContractorAllocationStatus.CANCELED,
                    "Перевод не подтвержден",
                    ContractorPaymentEventTimePolicy.paymentLinkClosedAt(link, now),
                    "LINK:CLOSED:" + linkStatus);
        }
    }

    private boolean canRecordObservedReturn(ContractorAllocationStatus status) {
        return status == ContractorAllocationStatus.RETURN_AMOUNT_PENDING
                || status == ContractorAllocationStatus.PARTIALLY_RETURNED;
    }

    private boolean canRecordActualPaymentReturn(ContractorAllocationStatus status) {
        return status == ContractorAllocationStatus.CONFIRMED
                || status == ContractorAllocationStatus.SIMULATED_PAID
                || status == ContractorAllocationStatus.LATE_PAYMENT_AFTER_RELEASE
                || status == ContractorAllocationStatus.PARTIALLY_CONFIRMED
                || status == ContractorAllocationStatus.PARTIALLY_RETURNED
                || status == ContractorAllocationStatus.RETURNED
                || status == ContractorAllocationStatus.RETURN_AMOUNT_PENDING;
    }

    private long sourceVersion(PaymentLink link) {
        return link == null || link.getRowVersion() == null
                ? 0L
                : Math.max(0L, link.getRowVersion());
    }

    private void release(
            ContractorPaymentAllocation allocation,
            ContractorAllocationStatus status,
            String reason,
            LocalDateTime now,
            String externalRef
    ) {
        if (!RESERVING_STATUSES.contains(allocation.getStatus())
                && allocation.getStatus() != ContractorAllocationStatus.OWNER_FALLBACK) {
            return;
        }
        accountingService.recordRelease(allocation, status, now, reason, externalRef);
    }

    private ContractorPaymentRouteDecision selectRoutableProfile(
            Object specialistSubject,
            Object managerSubject,
            long amount,
            ContractorRoutingDecisionReason specialistPrecondition
    ) {
        User specialist = user(specialistSubject);
        User manager = user(managerSubject);
        return selectRoutableProfileByUserIds(
                specialist == null ? null : specialist.getId(),
                manager == null ? null : manager.getId(),
                amount,
                specialistPrecondition
        );
    }

    private ContractorPaymentRouteDecision selectRoutableProfileByUserIds(
            Long specialistUserId,
            Long managerUserId,
            long amount,
            ContractorRoutingDecisionReason specialistPrecondition
    ) {
        List<RouteCandidate> candidates = new ArrayList<>(2);
        addCandidate(candidates, specialistUserId, ContractorRole.SPECIALIST);
        addCandidate(candidates, managerUserId, ContractorRole.MANAGER);
        ContractorRoutingDecisionReason specialistRejection = specialistPrecondition;
        if (specialistRejection == null && specialistUserId == null) {
            specialistRejection = ContractorRoutingDecisionReason.SPECIALIST_NOT_ASSIGNED;
        }
        ContractorRoutingDecisionReason managerRejection = managerUserId == null
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

    private void addCandidate(List<RouteCandidate> candidates, Long userId, ContractorRole role) {
        if (userId == null) {
            return;
        }
        Long profileId = profileRepository.findIdByUserIdAndRole(userId, role).orElse(null);
        RouteCandidate candidate = new RouteCandidate(userId, role, profileId);
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
                profile, ContractorAllocationMode.SHADOW) < amount) {
            return ContractorRoutingDecisionReason.INSUFFICIENT_AVAILABLE_BALANCE;
        }
        ContractorPaymentRoutingLimitService.RoutingLimitDecision limitDecision =
                routingLimitService.evaluate(profile, ContractorAllocationMode.SHADOW, amount);
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

    private ContractorPaymentAllocation baseAllocation(
            ContractorAllocationSourceType sourceType,
            Long sourceId,
            Order order,
            long amount
    ) {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(sourceType);
        allocation.setSourceId(sourceId);
        allocation.setOrderId(order.getId());
        allocation.setAmountKopecks(amount);
        allocation.setCurrentWorkerId(order.getWorker() == null ? null : order.getWorker().getId());
        Manager manager = effectiveManager(order);
        allocation.setCurrentManagerId(manager == null ? null : manager.getId());
        return allocation;
    }

    private Optional<ContractorPaymentAllocation> latestAllocation(
            ContractorAllocationMode mode,
            ContractorAllocationSourceType sourceType,
            Long sourceId
    ) {
        return allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                mode, sourceType, sourceId
        );
    }

    private void lockEvidenceSource(ContractorPaymentAllocation allocation) {
        if (allocation == null || allocation.getSourceType() == null || allocation.getSourceId() == null) {
            return;
        }
        if (allocation.getSourceType() == ContractorAllocationSourceType.PAYMENT_LINK) {
            paymentLinkRepository.findByIdForUpdate(allocation.getSourceId());
        } else if (allocation.getSourceType() == ContractorAllocationSourceType.COMMON_INVOICE) {
            commonInvoiceRepository.findByIdForUpdate(allocation.getSourceId());
        }
    }

    private PaymentSourcePrelude lockPaymentSourceOrderFirst(Long paymentLinkId, Long knownOrderId) {
        if (paymentLinkId == null) {
            return null;
        }
        PaymentLink prelude = paymentLinkRepository.findByIdWithOrder(paymentLinkId).orElse(null);
        Long orderId = knownOrderId != null
                ? knownOrderId
                : prelude == null || prelude.getOrder() == null ? null : prelude.getOrder().getId();
        Order lockedOrder = null;
        if (orderId != null) {
            lockedOrder = orderRepository.findByIdForCounterUpdate(orderId).orElse(null);
            if (lockedOrder == null) {
                return null;
            }
        }
        PaymentLink lockedSource = paymentLinkRepository.findByIdForUpdate(paymentLinkId).orElse(null);
        if (lockedSource == null) {
            return new PaymentSourcePrelude(null, lockedOrder);
        }
        entityManager.refresh(lockedSource, LockModeType.PESSIMISTIC_WRITE);
        Long currentOrderId = lockedSource.getOrder() == null ? null : lockedSource.getOrder().getId();
        if (orderId != null && !Objects.equals(orderId, currentOrderId)) {
            return null;
        }
        if (orderId == null && currentOrderId != null) {
            // The source acquired an order after the pre-read. Starting over is
            // safer than taking the Order lock after the PaymentLink lock.
            return null;
        }
        return new PaymentSourcePrelude(lockedSource, lockedOrder);
    }

    private CommonSourcePrelude lockCommonSourceOrderFirst(Long invoiceId) {
        if (invoiceId == null) {
            return null;
        }
        List<Long> preludeOrderIds = commonInvoiceOrderRepository.findOrderIdsByInvoiceId(invoiceId).stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        List<Order> lockedOrders = new ArrayList<>(preludeOrderIds.size());
        for (Long orderId : preludeOrderIds) {
            Order order = orderRepository.findByIdForCounterUpdate(orderId).orElse(null);
            if (order == null) {
                return null;
            }
            lockedOrders.add(order);
        }
        CommonInvoice invoice = commonInvoiceRepository.findByIdForUpdate(invoiceId).orElse(null);
        List<Long> currentOrderIds = commonInvoiceOrderRepository.findMembershipByInvoiceIdForRead(invoiceId)
                .stream()
                .map(item -> item.getOrder() == null ? null : item.getOrder().getId())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (!preludeOrderIds.equals(currentOrderIds)) {
            return null;
        }
        return new CommonSourcePrelude(invoice, lockedOrders);
    }

    /**
     * Locks a same-source/multi-source batch in the global order established
     * by callers: all evidence sources, then every profile id ascending, then
     * every allocation id ascending. The final scalar latest-id read is a
     * current generation check that bypasses the persistence-context cache.
     */
    private List<ContractorPaymentAllocation> lockLatestAttemptsCanonical(
            Collection<ContractorPaymentAllocation> snapshots
    ) {
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        List<ContractorPaymentAllocation> orderedSnapshots = snapshots.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        ContractorPaymentAllocation::getId,
                        value -> value,
                        (left, right) -> left,
                        java.util.TreeMap::new
                ))
                .values().stream().toList();

        Set<Long> missingProfiles = new java.util.HashSet<>();
        orderedSnapshots.stream()
                .map(ContractorPaymentAllocation::getRecipientProfile)
                .filter(Objects::nonNull)
                .map(ContractorPaymentProfile::getId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(profileId -> {
                    if (profileRepository.findByIdForUpdate(profileId).isEmpty()) {
                        missingProfiles.add(profileId);
                    }
                });

        Map<Long, ContractorPaymentAllocation> lockedById = new LinkedHashMap<>();
        for (ContractorPaymentAllocation snapshot : orderedSnapshots) {
            Long profileId = snapshot.getRecipientProfile() == null
                    ? null
                    : snapshot.getRecipientProfile().getId();
            if (profileId != null && missingProfiles.contains(profileId)) {
                continue;
            }
            ContractorPaymentAllocation locked = allocationRepository.findByIdForUpdate(snapshot.getId())
                    .orElse(null);
            if (locked == null) {
                continue;
            }
            entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
            lockedById.put(locked.getId(), locked);
        }

        List<ContractorPaymentAllocation> current = new ArrayList<>();
        lockedById.values().stream()
                .sorted(Comparator
                        .comparing((ContractorPaymentAllocation value) -> value.getSourceType().name())
                        .thenComparing(ContractorPaymentAllocation::getSourceId)
                        .thenComparing(value -> value.getMode().name())
                        .thenComparing(ContractorPaymentAllocation::getId))
                .forEach(locked -> {
                    Long latestId = allocationRepository.findLatestIdForUpdate(
                            locked.getMode().name(),
                            locked.getSourceType().name(),
                            locked.getSourceId()
                    ).orElse(null);
                    if (Objects.equals(latestId, locked.getId())) {
                        current.add(locked);
                    }
                });
        return current;
    }

    /**
     * Global accounting lock order: recipient profile, then allocation. Route
     * creation uses the same profile mutex while checking available capacity.
     * Consequently a late confirmation that has acquired this lock is visible
     * before any subsequent route can reserve more capacity on another node.
     */
    private ContractorPaymentAllocation lockForAccounting(ContractorPaymentAllocation snapshot) {
        if (snapshot == null || snapshot.getId() == null) {
            return null;
        }
        Long profileId = snapshot.getRecipientProfile() == null
                ? null
                : snapshot.getRecipientProfile().getId();
        if (profileId != null && profileRepository.findByIdForUpdate(profileId).isEmpty()) {
            return null;
        }
        ContractorPaymentAllocation locked = allocationRepository.findByIdForUpdate(snapshot.getId()).orElse(null);
        if (locked == null) {
            return null;
        }
        // A pessimistic repository query may return the already-managed
        // snapshot without replacing stale fields. Refresh is an explicit
        // current read while retaining the allocation write lock.
        entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
        return locked;
    }

    private ContractorPaymentAllocation lockLatestAttempt(ContractorPaymentAllocation snapshot) {
        if (snapshot == null) {
            return null;
        }
        List<ContractorPaymentAllocation> locked = lockLatestAttemptsCanonical(List.of(snapshot));
        return locked.isEmpty() ? null : locked.getFirst();
    }

    private void clearReconciliationFailure(ContractorPaymentAllocation allocation) {
        allocation.setReconcileClaimToken(null);
        allocation.setReconcileLeaseUntil(null);
        allocation.setReconcileAttempts(0);
        allocation.setReconcileNextRetryAt(null);
        allocation.setReconcileLastErrorCode(null);
    }

    private int nextAttempt(Optional<ContractorPaymentAllocation> latest) {
        return latest.map(value -> Math.addExact(value.getAttemptNo(), 1)).orElse(1);
    }

    private boolean canRetry(ContractorPaymentAllocation allocation) {
        return allocation != null && (allocation.getStatus() == ContractorAllocationStatus.RELEASED_UNPAID
                || allocation.getStatus() == ContractorAllocationStatus.EXPIRED
                || allocation.getStatus() == ContractorAllocationStatus.CANCELED
                || allocation.getStatus() == ContractorAllocationStatus.PARTIALLY_RETURNED
                || allocation.getStatus() == ContractorAllocationStatus.RETURNED);
    }

    private boolean isReleased(ContractorPaymentAllocation allocation) {
        if (allocation == null) {
            return false;
        }
        ContractorAllocationStatus status = allocation.getStatus();
        return allocation.getReleasedAt() != null
                || status == ContractorAllocationStatus.RELEASED_UNPAID
                || status == ContractorAllocationStatus.EXPIRED
                || status == ContractorAllocationStatus.CANCELED
                || status == ContractorAllocationStatus.LATE_PAYMENT_AFTER_RELEASE;
    }

    private boolean orderReleasesReservation(Order order) {
        String statusTitle = order == null || order.getStatus() == null
                ? ""
                : normalize(order.getStatus().getTitle());
        return "Не оплачено".equalsIgnoreCase(statusTitle)
                || "Бан".equalsIgnoreCase(statusTitle);
    }

    private boolean releaseIfOrderFinanciallyClosed(
            ContractorPaymentAllocation allocation,
            Order order,
            LocalDateTime observedAt
    ) {
        if (!orderReleasesAllocation(order, allocation)
                || !UNPAID_RELEASABLE_STATUSES.contains(allocation.getStatus())) {
            return false;
        }
        accountingService.recordRelease(
                allocation,
                ContractorAllocationStatus.RELEASED_UNPAID,
                observedAt,
                "Бан".equalsIgnoreCase(statusTitle(order))
                        ? "Заказ находится в статусе «Бан»"
                        : "Заказ находится в статусе «Не оплачено»",
                "Бан".equalsIgnoreCase(statusTitle(order))
                        ? "LINK:ORDER_BANNED"
                        : "LINK:ORDER_UNPAID"
        );
        return true;
    }

    /**
     * «Не оплачено» closes the payment attempt which existed when the order
     * entered that status, not every future attempt for the same order. A final
     * bad-review invoice is legitimately created after that transition and must
     * remain reserved. «Бан» is terminal and releases every active attempt.
     */
    private boolean orderReleasesAllocation(Order order, ContractorPaymentAllocation allocation) {
        String statusTitle = statusTitle(order);
        if ("Бан".equalsIgnoreCase(statusTitle)) {
            return true;
        }
        if (!"Не оплачено".equalsIgnoreCase(statusTitle) || allocation == null) {
            return false;
        }
        LocalDateTime statusChangedAt = order.getStatusChangedAt();
        LocalDateTime reservedAt = allocation.getReservedAt() != null
                ? allocation.getReservedAt()
                : allocation.getCreatedAt();
        // Old rows can predate either timestamp. Preserve the historical
        // release behaviour for them; every newly created allocation has both.
        return statusChangedAt == null || reservedAt == null || !reservedAt.isAfter(statusChangedAt);
    }

    private String statusTitle(Order order) {
        return order == null || order.getStatus() == null
                ? ""
                : normalize(order.getStatus().getTitle());
    }

    private long confirmedAmount(PaymentLink link) {
        Long confirmed = link.getConfirmedAmountKopecks();
        return confirmed != null && confirmed > 0 ? confirmed : Math.max(0L, link.getAmountKopecks());
    }

    private LocalDateTime paidAt(PaymentLink link, LocalDateTime fallback) {
        return firstNonNull(link.getPaidAt(), link.getManualConfirmedAt(), fallback);
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

    /**
     * The company row is the linearization point for the «по ссылке/по счёту»
     * policy. Route creation already holds its Order lock; company edits take
     * this same row before changing the flag. Whichever lock commits first is
     * the policy frozen into the immutable source snapshot.
     */
    private void lockCompanyRoutingPolicy(Order order) {
        Company company = order == null ? null : order.getCompany();
        if (company != null && company.getId() != null) {
            entityManager.lock(company, LockModeType.PESSIMISTIC_WRITE);
        }
    }

    private void lockCompanyRoutingPolicies(Collection<Order> orders) {
        if (orders == null) {
            return;
        }
        orders.stream()
                .filter(Objects::nonNull)
                .map(Order::getCompany)
                .filter(Objects::nonNull)
                .filter(company -> company.getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Company::getId,
                        company -> company,
                        (left, right) -> left,
                        java.util.TreeMap::new
                ))
                .values()
                .forEach(company -> entityManager.lock(company, LockModeType.PESSIMISTIC_WRITE));
    }

    private boolean allCompaniesAllowContractorRouting(Collection<Order> orders) {
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

    private String membershipHash(Collection<Order> orders) {
        String canonical = (orders == null ? List.<Order>of() : orders).stream()
                .filter(Objects::nonNull)
                .map(Order::getId)
                .filter(Objects::nonNull)
                .sorted()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private LocalDateTime firstNonNull(LocalDateTime... values) {
        if (values != null) {
            for (LocalDateTime value : values) {
                if (value != null) {
                    return value;
                }
            }
        }
        return LocalDateTime.now();
    }

    private void applyRecipient(
            ContractorPaymentAllocation allocation,
            ContractorPaymentRouteDecision decision
    ) {
        ContractorPaymentProfile recipient = decision.recipient();
        allocation.setRoutingDecisionReason(decision.decisionReason());
        allocation.setSpecialistRejectionReason(decision.specialistRejectionReason());
        allocation.setManagerRejectionReason(decision.managerRejectionReason());
        if (recipient == null) {
            allocation.setRecipientType(ContractorRecipientType.OWNER);
            allocation.setStatus(ContractorAllocationStatus.OWNER_FALLBACK);
            return;
        }
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
                recipient, ContractorAllocationMode.SHADOW));
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        allocation.setReservedAt(LocalDateTime.now());
    }

    private boolean shadowEnabled() {
        if (accountingPhaseService.current() == ContractorAllocationMode.LIVE) {
            return false;
        }
        return appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value) {
        String normalized = normalize(value);
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private record AllocationBefore(
            ContractorAllocationStatus status,
            long returnedKopecks
    ) {
    }

    private record PaymentLinkActualReturnPlan(
            boolean attributionPresent,
            List<ContractorPaymentAllocation> actualSnapshots,
            Map<Long, ContractorActualPaymentAttribution> actualRowsByAllocationId,
            Set<Long> reusedSourceAllocationIds,
            Set<Long> supersededSourceAllocationIds
    ) {
        private static PaymentLinkActualReturnPlan none() {
            return new PaymentLinkActualReturnPlan(
                    false, List.of(), Map.of(), Set.of(), Set.of()
            );
        }
    }

    private record PaymentSourcePrelude(PaymentLink link, Order order) {
    }

    private record CommonSourcePrelude(CommonInvoice invoice, List<Order> orders) {
    }

    private record RouteKey(Long userId, ContractorRole role) {
    }

    private record RouteCandidate(Long userId, ContractorRole role, Long profileId) {
        private RouteKey key() {
            return new RouteKey(userId, role);
        }
    }

    public enum ShadowReservationOutcome {
        CREATED,
        ALREADY_EXISTS,
        NOT_PREPARED_OR_INCONSISTENT,
        OUT_OF_SCOPE;

        public boolean completed() {
            return this == CREATED || this == ALREADY_EXISTS || this == OUT_OF_SCOPE;
        }
    }

    public record ShadowReservationResult(
            ShadowReservationOutcome outcome,
            ContractorPaymentAllocation allocation
    ) {
    }
}
