package com.hunt.otziv.performers.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardMarker;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardMarkerRepository;
import com.hunt.otziv.contractor_payments.service.ContractorOrderManagerResolver;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRolloutStateService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardAttributionSnapshotCodec;
import com.hunt.otziv.contractor_payments.service.ContractorRewardLedgerService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardSourceCodes;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformerProductRewardZpService {

    public static final String SOURCE = ContractorRewardSourceCodes.LEGACY_PERFORMER_PRODUCT;
    public static final String COMPLETION_SOURCE = ContractorRewardSourceCodes.PERFORMER_PRODUCT_COMPLETION;

    private final AppSettingService appSettingService;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final ZpRepository zpRepository;
    private final ContractorRewardAttributionService contractorRewardAttributionService;
    private final ContractorPaymentRolloutStateService rolloutStateService;
    private final ContractorPaymentRuntimeSwitch contractorPaymentRuntimeSwitch;
    private final ContractorRewardLedgerService contractorRewardLedgerService;
    private final ContractorCompletionRewardMarkerRepository completionMarkerRepository;
    private final com.hunt.otziv.contractor_payments.service.ContractorPaymentBusinessClock businessClock;
    private final ContractorOrderManagerResolver orderManagerResolver;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int accrueForPaidOrder(Long orderId) {
        // Under the completion rollout the publication/payment coordinator
        // owns this source. Never create the legacy paid-only aggregate.
        ContractorPaymentAccountingAuthority accountingAuthority =
                rolloutStateService.lockAccountingAuthority();
        if ((accountingAuthority != null && accountingAuthority.paymentBased())
                || contractorPaymentRuntimeSwitch.rewardAttributionLiveEnabled()) {
            return 0;
        }
        if (!appSettingService.getBoolean(AppSettingService.ZP_PRODUCT_REWARD_PERCENT_ENABLED, false)) {
            log.debug("Начисления по продуктам с исполнителями выключены: orderId={}", orderId);
            return 0;
        }
        if (orderId == null) {
            return 0;
        }

        // Cross-node idempotency mutex. The exists→insert decision must be
        // made only after every caller for this order is serialized.
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()
                || zpRepository.existsByOrderIdAndSourceAndActiveTrue(orderId, SOURCE)) {
            return 0;
        }

        Order order = orderRepository.findByIdForOrderDto(orderId).orElse(null);
        if (order == null || !isPaid(order)) {
            return 0;
        }

        RewardTotals totals = rewardTotals(order.getDetails());
        // Preserve the legacy paid-only semantics while completion routing is
        // disabled: only the manager frozen directly on the order is used,
        // and a missing manager simply omits that legacy row.
        Manager effectiveManager = order.getManager();
        int saved = 0;
        if (totals.managerAmount().compareTo(BigDecimal.ZERO) > 0) {
            saved += saveManagerReward(order, effectiveManager, totals, SOURCE, false, null);
        }
        if (totals.specialistAmount().compareTo(BigDecimal.ZERO) > 0) {
            saved += saveLegacySpecialistReward(order, totals, SOURCE, false, null);
        }
        if (saved > 0) {
            log.info(
                    "Начислено вознаграждение по продуктам с исполнителями: orderId={}, manager={}, specialist={}, rows={}",
                    orderId,
                    totals.managerAmount(),
                    totals.specialistAmount(),
                    saved
            );
        }
        return saved;
    }

    /**
     * Completion-path variant. The caller already owns the order mutex, but
     * re-taking the same row lock documents and enforces that contract for any
     * future direct caller. Ledger rows are written synchronously because the
     * invoice router may use the debt later in the same transaction.
     */
    @Transactional
    public int accrueForCompletedOrderLocked(
            Order suppliedOrder,
            LocalDate occurredOn,
            boolean convertLegacySource
    ) {
        if (!contractorPaymentRuntimeSwitch.rewardAttributionLiveEnabled()
                || suppliedOrder == null
                || suppliedOrder.getId() == null
                || !isPaid(suppliedOrder)) {
            return 0;
        }
        boolean rewardEnabled = appSettingService.getBoolean(
                AppSettingService.ZP_PRODUCT_REWARD_PERCENT_ENABLED,
                false
        );
        RewardTotals suppliedTotals = rewardEnabled
                ? rewardTotals(suppliedOrder.getDetails())
                : new RewardTotals(BigDecimal.ZERO, BigDecimal.ZERO, 0);
        Manager effectiveManager = rewardEnabled && suppliedTotals.managerAmount().signum() > 0
                ? orderManagerResolver.resolve(suppliedOrder, true)
                : null;
        return accrueForCompletedOrderLocked(
                suppliedOrder,
                effectiveManager,
                occurredOn,
                convertLegacySource
        );
    }

    @Transactional
    public int accrueForCompletedOrderLocked(
            Order suppliedOrder,
            Manager effectiveManager,
            LocalDate occurredOn,
            boolean convertLegacySource
    ) {
        if (!contractorPaymentRuntimeSwitch.rewardAttributionLiveEnabled()
                || suppliedOrder == null
                || suppliedOrder.getId() == null
                || !isPaid(suppliedOrder)) {
            return 0;
        }
        Long orderId = suppliedOrder.getId();
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            return 0;
        }
        boolean markerExists = completionMarkerRepository
                .findByOrderIdAndLogicalSource(orderId, COMPLETION_SOURCE)
                .isPresent();
        if (!convertLegacySource && zpRepository.existsByOrderIdAndSourceAndActiveTrue(orderId, SOURCE)) {
            return 0;
        }
        List<Zp> existing = zpRepository.findByOrderIdAndSourceAndActiveTrue(orderId, COMPLETION_SOURCE);
        if (!convertLegacySource && markerExists) {
            return 0;
        }
        if (!convertLegacySource && !existing.isEmpty()) {
            if (!markerExists) {
                ContractorCompletionRewardMarker marker = new ContractorCompletionRewardMarker();
                marker.setOrderId(orderId);
                marker.setLogicalSource(COMPLETION_SOURCE);
                marker.setOccurredOn(occurredOn == null ? businessClock.today() : occurredOn);
                completionMarkerRepository.save(marker);
            }
            return 0;
        }

        if (!appSettingService.getBoolean(AppSettingService.ZP_PRODUCT_REWARD_PERCENT_ENABLED, false)) {
            int deactivated = deactivateUnexpectedRewards(orderId, ContractorRole.MANAGER, Set.of())
                    + deactivateUnexpectedRewards(orderId, ContractorRole.SPECIALIST, Set.of());
            ContractorCompletionRewardMarker marker = new ContractorCompletionRewardMarker();
            if (!markerExists) {
                marker.setOrderId(orderId);
                marker.setLogicalSource(COMPLETION_SOURCE);
                marker.setOccurredOn(occurredOn == null ? businessClock.today() : occurredOn);
                completionMarkerRepository.save(marker);
            }
            return deactivated;
        }

        Order order = orderRepository.findByIdForOrderDto(orderId).orElse(suppliedOrder);
        RewardTotals totals = rewardTotals(order.getDetails());
        requireManagerForPositiveReward(effectiveManager, totals.managerAmount());
        List<ProductSpecialistReward> specialistRewards = completionSpecialistRewards(
                order.getDetails(),
                reviewRepository.getAllByOrderId(orderId)
        );
        int saved = 0;
        Set<Long> expectedManagers = new LinkedHashSet<>();
        if (totals.managerAmount().signum() > 0) {
            expectedManagers.add(effectiveManager.getId());
            saved += saveManagerReward(
                    order,
                    effectiveManager,
                    totals,
                    COMPLETION_SOURCE,
                    true,
                    occurredOn
            );
        }
        Set<Long> expectedSpecialists = specialistRewards.stream()
                .map(ProductSpecialistReward::workerId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        saved += saveCompletionSpecialistRewards(order, specialistRewards, occurredOn);
        saved += deactivateUnexpectedRewards(orderId, ContractorRole.MANAGER, expectedManagers);
        saved += deactivateUnexpectedRewards(orderId, ContractorRole.SPECIALIST, expectedSpecialists);
        if (!markerExists) {
            ContractorCompletionRewardMarker marker = new ContractorCompletionRewardMarker();
            marker.setOrderId(orderId);
            marker.setLogicalSource(COMPLETION_SOURCE);
            marker.setOccurredOn(occurredOn == null ? businessClock.today() : occurredOn);
            completionMarkerRepository.save(marker);
        }
        return saved;
    }

    /** Fail-closed preflight used before any base/task marker is persisted. */
    public void validateCompletedOrderEvidence(Order suppliedOrder) {
        if (!contractorPaymentRuntimeSwitch.rewardAttributionLiveEnabled()
                || !appSettingService.getBoolean(AppSettingService.ZP_PRODUCT_REWARD_PERCENT_ENABLED, false)
                || suppliedOrder == null
                || suppliedOrder.getId() == null) {
            return;
        }
        Order order = orderRepository.findByIdForOrderDto(suppliedOrder.getId()).orElse(suppliedOrder);
        completionSpecialistRewards(order.getDetails(), reviewRepository.getAllByOrderId(order.getId()));
    }

    /**
     * Paid-only bridge for work provably completed before completion cutover.
     * Recipients come only from the frozen order manager and published review
     * workers; the mutable current specialist is never used.
     */
    @Transactional
    public int accrueForPreCutoffPaymentLocked(
            Order suppliedOrder,
            Manager frozenManager,
            LocalDate paymentDate
    ) {
        if (!contractorPaymentRuntimeSwitch.rewardAttributionLiveEnabled()
                || suppliedOrder == null
                || suppliedOrder.getId() == null
                || !appSettingService.getBoolean(AppSettingService.ZP_PRODUCT_REWARD_PERCENT_ENABLED, false)) {
            return 0;
        }
        Order order = orderRepository.findByIdForOrderDto(suppliedOrder.getId()).orElse(suppliedOrder);
        RewardTotals totals = rewardTotals(order.getDetails());
        requireManagerForPositiveReward(frozenManager, totals.managerAmount());
        List<ProductSpecialistReward> specialistRewards = completionSpecialistRewards(
                order.getDetails(),
                reviewRepository.getAllByOrderId(order.getId())
        );
        int saved = 0;
        if (totals.managerAmount().signum() > 0) {
            saved += ensurePreCutoffPaymentReward(
                    order,
                    frozenManager.getUser(),
                    frozenManager.getId(),
                    totals.managerAmount(),
                    totals.amount(),
                    ContractorRole.MANAGER,
                    paymentDate
            );
        }
        for (ProductSpecialistReward reward : specialistRewards) {
            saved += ensurePreCutoffPaymentReward(
                    order,
                    reward.user(),
                    reward.workerId(),
                    BigDecimal.valueOf(reward.amountKopecks(), 2),
                    reward.workUnits(),
                    ContractorRole.SPECIALIST,
                    paymentDate
            );
        }
        return saved;
    }

    private int ensurePreCutoffPaymentReward(
            Order order,
            User user,
            Long professionId,
            BigDecimal amount,
            int workUnits,
            ContractorRole role,
            LocalDate paymentDate
    ) {
        if (amount == null || amount.signum() <= 0) {
            return 0;
        }
        Zp existing = zpRepository.findFirstByOrderIdAndSourceAndContractorRoleAndProfessionId(
                order.getId(), SOURCE, role, professionId
        ).orElse(null);
        if (existing != null) {
            if (!existing.isActive()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Историческое начисление было скорректировано; нужна ручная сверка"
                );
            }
            return 0;
        }
        Zp row = toZp(order, user, professionId, amount, workUnits, role, SOURCE, paymentDate);
        row.setAttributionFinal(true);
        zpRepository.save(row);
        return 1;
    }

    private int saveManagerReward(
            Order order,
            Manager manager,
            RewardTotals totals,
            String source,
            boolean synchronousLedger,
            LocalDate occurredOn
    ) {
        if (manager == null || manager.getUser() == null) {
            log.debug("Вознаграждение менеджера по продуктам не начислено: у заказа {} нет менеджера", order.getId());
            return 0;
        }
        Zp desired = toZp(
                order,
                manager.getUser(),
                manager.getId(),
                totals.managerAmount(),
                totals.amount(),
                ContractorRole.MANAGER,
                source,
                occurredOn
        );
        desired.setAttributionFinal(true);
        desired.setRewardBasis(totals.managerAmount());
        UpsertResult result = upsert(desired);
        synchronize(result.row(), synchronousLedger);
        return result.changed() ? 1 : 0;
    }

    private int saveLegacySpecialistReward(
            Order order,
            RewardTotals totals,
            String source,
            boolean synchronousLedger,
            LocalDate occurredOn
    ) {
        Worker worker = order.getWorker();
        if (worker == null || worker.getUser() == null) {
            log.debug("Вознаграждение специалиста по продуктам не начислено: у заказа {} нет специалиста", order.getId());
            return 0;
        }
        Zp legacyReward = toZp(
                order,
                worker.getUser(),
                worker.getId(),
                totals.specialistAmount(),
                totals.amount(),
                ContractorRole.SPECIALIST,
                source,
                occurredOn
        );
        // Even while the legacy payout row remains assigned to the current
        // specialist, ledger attribution must be immutable.
        legacyReward.setAttributionSnapshot(ContractorRewardAttributionSnapshotCodec.encode(
                immutableSpecialistShares(order, totals)
        ));
        Zp saved = zpRepository.save(legacyReward);
        synchronize(saved, synchronousLedger);
        return 1;
    }

    private int saveCompletionSpecialistRewards(
            Order order,
            List<ProductSpecialistReward> rewards,
            LocalDate occurredOn
    ) {
        int saved = 0;
        for (ProductSpecialistReward reward : rewards) {
            if (reward.amountKopecks() <= 0L) {
                continue;
            }
            Zp row = toZp(
                    order,
                    reward.user(),
                    reward.workerId(),
                    BigDecimal.valueOf(reward.amountKopecks(), 2),
                    reward.workUnits(),
                    ContractorRole.SPECIALIST,
                    COMPLETION_SOURCE,
                    occurredOn
            );
            row.setAttributionFinal(true);
            row.setRewardBasis(BigDecimal.valueOf(reward.amountKopecks(), 2));
            UpsertResult result = upsert(row);
            synchronize(result.row(), true);
            if (result.changed()) {
                saved++;
            }
        }
        return saved;
    }

    private UpsertResult upsert(Zp desired) {
        Zp existing = zpRepository.findFirstByOrderIdAndSourceAndContractorRoleAndProfessionId(
                desired.getOrderId(),
                desired.getSource(),
                desired.getContractorRole(),
                desired.getProfessionId()
        ).orElse(null);
        if (existing == null) {
            return new UpsertResult(zpRepository.save(desired), true);
        }
        boolean changed = !existing.isActive()
                || !Objects.equals(existing.getFio(), desired.getFio())
                || !Objects.equals(existing.getUserId(), desired.getUserId())
                || existing.getSum() == null
                || existing.getSum().compareTo(desired.getSum()) != 0
                || existing.getAmount() != desired.getAmount()
                || !Objects.equals(existing.getCreated(), desired.getCreated())
                || !existing.isAttributionFinal()
                || existing.getRewardBasis() == null
                || existing.getRewardBasis().compareTo(desired.getRewardBasis()) != 0;
        if (!changed) {
            return new UpsertResult(existing, false);
        }
        existing.setFio(desired.getFio());
        existing.setSum(desired.getSum());
        existing.setUserId(desired.getUserId());
        existing.setAmount(desired.getAmount());
        existing.setCreated(desired.getCreated());
        existing.setPaymentStatusGuardId(desired.getPaymentStatusGuardId());
        existing.setActive(true);
        existing.setAttributionFinal(true);
        existing.setRewardBasis(desired.getRewardBasis());
        return new UpsertResult(zpRepository.save(existing), true);
    }

    private record UpsertResult(Zp row, boolean changed) {
    }

    private int deactivateUnexpectedRewards(Long orderId, ContractorRole role, Set<Long> expectedProfessions) {
        List<Zp> unexpected = zpRepository.findByOrderIdAndSourceAndActiveTrue(orderId, COMPLETION_SOURCE).stream()
                .filter(reward -> reward.getContractorRole() == role)
                .filter(reward -> !expectedProfessions.contains(reward.getProfessionId()))
                .toList();
        if (unexpected.isEmpty()) {
            return 0;
        }
        unexpected.forEach(reward -> reward.setActive(false));
        zpRepository.saveAllAndFlush(unexpected);
        contractorRewardLedgerService.synchronizeSources(unexpected);
        return unexpected.size();
    }

    private List<ContractorRewardAttributionService.SpecialistShare> immutableSpecialistShares(
            Order order,
            RewardTotals totals
    ) {
        List<ContractorRewardAttributionService.SpecialistShare> shares;
        try {
            shares = contractorRewardAttributionService.attribute(order, order.getSum());
        } catch (RuntimeException exception) {
            log.warn(
                    "Снимок распределения вознаграждения сформирован по текущему специалисту: orderId={}, failure={}",
                    order.getId(),
                    exception.getClass().getSimpleName()
            );
            shares = List.of();
        }
        if (shares != null && !shares.isEmpty()) {
            return List.copyOf(shares);
        }
        Worker worker = order.getWorker();
        if (worker == null || worker.getId() == null || worker.getUser() == null) {
            return List.of();
        }
        BigDecimal weight = order.getSum() == null || order.getSum().signum() <= 0
                ? BigDecimal.ONE
                : order.getSum();
        return List.of(new ContractorRewardAttributionService.SpecialistShare(
                worker.getUser(),
                worker.getId(),
                weight,
                Math.max(0, totals.amount())
        ));
    }

    private List<ProductSpecialistReward> completionSpecialistRewards(
            List<OrderDetails> details,
            List<Review> reviews
    ) {
        Map<Long, MutableProductReward> totals = new LinkedHashMap<>();
        for (OrderDetails detail : details == null ? List.<OrderDetails>of() : details) {
            Product product = detail == null ? null : detail.getProduct();
            if (product == null || !product.isRequiresPerformer()) {
                continue;
            }
            BigDecimal detailReward = percent(
                    detailBase(detail, product),
                    product.getSpecialistRewardPercent()
            );
            if (detailReward.signum() <= 0) {
                continue;
            }
            int expectedUnits = Math.max(0, detail.getAmount());
            List<Review> published = (reviews == null ? List.<Review>of() : reviews).stream()
                    .filter(review -> review != null && review.isPublish())
                    .filter(review -> belongsTo(review, detail))
                    .toList();
            if (expectedUnits <= 0 || published.size() != expectedUnits) {
                throw unverifiableProductWork();
            }

            Map<Long, MutableProductReward> detailWeights = new LinkedHashMap<>();
            for (Review review : published) {
                Worker worker = review.getWorker();
                if (worker == null
                        || worker.getId() == null
                        || worker.getUser() == null
                        || worker.getUser().getId() == null) {
                    throw unverifiableProductWork();
                }
                MutableProductReward weight = detailWeights.computeIfAbsent(
                        worker.getId(),
                        ignored -> new MutableProductReward(worker.getId(), worker.getUser())
                );
                if (!weight.user.getId().equals(worker.getUser().getId())) {
                    throw unverifiableProductWork();
                }
                BigDecimal reviewWeight = review.getPrice() != null && review.getPrice().signum() > 0
                        ? review.getPrice()
                        : BigDecimal.ONE;
                weight.weight = weight.weight.add(reviewWeight);
                weight.workUnits++;
            }

            Map<Long, Long> detailAllocation = allocateKopecks(detailReward, detailWeights);
            for (MutableProductReward weight : detailWeights.values()) {
                long kopecks = detailAllocation.getOrDefault(weight.workerId, 0L);
                MutableProductReward total = totals.computeIfAbsent(
                        weight.workerId,
                        ignored -> new MutableProductReward(weight.workerId, weight.user)
                );
                if (!total.user.getId().equals(weight.user.getId())) {
                    throw unverifiableProductWork();
                }
                total.amountKopecks = Math.addExact(total.amountKopecks, kopecks);
                total.workUnits = Math.addExact(total.workUnits, weight.workUnits);
            }
        }
        return totals.values().stream()
                .filter(value -> value.amountKopecks > 0L)
                .sorted(Comparator.comparing(value -> value.workerId))
                .map(value -> new ProductSpecialistReward(
                        value.user,
                        value.workerId,
                        value.amountKopecks,
                        value.workUnits
                ))
                .toList();
    }

    private Map<Long, Long> allocateKopecks(
            BigDecimal total,
            Map<Long, MutableProductReward> weights
    ) {
        long totalKopecks = money(total).movePointRight(2).longValueExact();
        BigDecimal grossTotal = weights.values().stream()
                .map(value -> value.weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalKopecks <= 0 || grossTotal.signum() <= 0) {
            return Map.of();
        }
        List<RewardPart> parts = new ArrayList<>();
        long floorTotal = 0L;
        for (MutableProductReward weight : weights.values()) {
            BigDecimal exact = BigDecimal.valueOf(totalKopecks)
                    .multiply(weight.weight)
                    .divide(grossTotal, 12, RoundingMode.DOWN);
            long floor = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            floorTotal = Math.addExact(floorTotal, floor);
            parts.add(new RewardPart(
                    weight.workerId,
                    floor,
                    exact.subtract(BigDecimal.valueOf(floor))
            ));
        }
        parts.sort(Comparator.comparing(RewardPart::remainder).reversed()
                .thenComparing(RewardPart::workerId));
        Map<Long, Long> result = new LinkedHashMap<>();
        parts.forEach(part -> result.put(part.workerId(), part.floor()));
        long missing = Math.subtractExact(totalKopecks, floorTotal);
        for (long index = 0; index < missing; index++) {
            Long workerId = parts.get(Math.toIntExact(index % parts.size())).workerId();
            result.compute(workerId, (ignored, value) -> Math.addExact(value, 1L));
        }
        return result;
    }

    private boolean belongsTo(Review review, OrderDetails detail) {
        if (review.getOrderDetails() == detail) {
            return true;
        }
        return review.getOrderDetails() != null
                && review.getOrderDetails().getId() != null
                && detail.getId() != null
                && detail.getId().equals(review.getOrderDetails().getId());
    }

    private ResponseStatusException unverifiableProductWork() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Для продукта с вознаграждением не подтвержден исполнитель каждой опубликованной работы"
        );
    }

    private void requireManagerForPositiveReward(Manager manager, BigDecimal managerReward) {
        if (managerReward != null && managerReward.signum() > 0 && manager == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Для вознаграждения по продукту не зафиксирован менеджер; нужна ручная сверка"
            );
        }
    }

    private Zp toZp(
            Order order,
            User user,
            Long professionId,
            BigDecimal sum,
            int amount,
            ContractorRole role,
            String source,
            LocalDate occurredOn
    ) {
        Zp zp = new Zp();
        zp.setFio(user.getFio());
        zp.setSum(money(sum));
        zp.setOrderId(order.getId());
        zp.setPaymentStatusGuardId(order.getStatus().getId());
        zp.setUserId(user.getId());
        zp.setProfessionId(professionId);
        zp.setAmount(amount);
        zp.setActive(true);
        zp.setSource(source);
        zp.setContractorRole(role);
        zp.setCreated(occurredOn);
        return zp;
    }

    private void synchronize(Zp reward, boolean synchronous) {
        if (synchronous) {
            // Completion coordinator applies base/task/product rows in one
            // canonical ledger batch after every source has been saved.
            return;
        }
        contractorRewardLedgerService.synchronizeSourcesSafely(List.of(reward));
    }

    private RewardTotals rewardTotals(List<OrderDetails> details) {
        BigDecimal manager = BigDecimal.ZERO;
        BigDecimal specialist = BigDecimal.ZERO;
        int amount = 0;
        if (details == null) {
            return new RewardTotals(manager, specialist, amount);
        }
        for (OrderDetails detail : details) {
            Product product = detail != null ? detail.getProduct() : null;
            if (product == null || !product.isRequiresPerformer()) {
                continue;
            }
            BigDecimal base = detailBase(detail, product);
            manager = manager.add(percent(base, product.getManagerRewardPercent()));
            specialist = specialist.add(percent(base, product.getSpecialistRewardPercent()));
            amount += Math.max(0, detail.getAmount());
        }
        return new RewardTotals(money(manager), money(specialist), amount);
    }

    private BigDecimal detailBase(OrderDetails detail, Product product) {
        if (detail != null && detail.getPrice() != null) {
            return detail.getPrice();
        }
        BigDecimal price = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
        int amount = detail == null ? 0 : Math.max(0, detail.getAmount());
        return price.multiply(BigDecimal.valueOf(amount));
    }

    private BigDecimal percent(BigDecimal base, BigDecimal percent) {
        if (base == null || percent == null || base.compareTo(BigDecimal.ZERO) <= 0 || percent.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return base.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isPaid(Order order) {
        return order != null && order.getStatus() != null && "Оплачено".equals(order.getStatus().getTitle());
    }

    private record RewardTotals(BigDecimal managerAmount, BigDecimal specialistAmount, int amount) {
    }

    private record RewardPart(Long workerId, long floor, BigDecimal remainder) {
    }

    private record ProductSpecialistReward(
            User user,
            Long workerId,
            long amountKopecks,
            int workUnits
    ) {
    }

    private static final class MutableProductReward {
        private final Long workerId;
        private final User user;
        private BigDecimal weight = BigDecimal.ZERO;
        private long amountKopecks;
        private int workUnits;

        private MutableProductReward(Long workerId, User user) {
            this.workerId = workerId;
            this.user = user;
        }
    }
}
