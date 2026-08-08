package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ContractorRewardAttributionService {

    private final ReviewRepository reviewRepository;
    private final BadReviewTaskRepository badReviewTaskRepository;

    public List<SpecialistShare> attribute(Order order, BigDecimal payableSum) {
        return attribute(order, payableSum, false);
    }

    /** Uses persisted completed work as the weight basis without relying on a
     * current user coefficient or reconstructing the original gross amount. */
    public List<SpecialistShare> attributeRecordedWork(Order order) {
        return attribute(order, null, true);
    }

    /**
     * Strict post-cutover attribution. Unlike the legacy/shadow method this
     * never infers a recipient from the mutable current order card: every
     * expected published work unit must carry a durable worker identity.
     */
    public List<SpecialistShare> attributeCompletedBaseWork(Order order) {
        if (order == null || order.getId() == null || order.getAmount() <= 0) {
            throw unverifiableCompletedWork();
        }
        BigDecimal canonicalGross = money(order.getSum());
        if (canonicalGross.signum() <= 0) {
            throw unverifiableCompletedWork();
        }
        List<Review> reviews = reviewRepository.getAllByOrderId(order.getId());
        List<Review> published = (reviews == null ? List.<Review>of() : reviews).stream()
                .filter(review -> review != null && review.isPublish())
                .toList();
        if (published.size() != order.getAmount()) {
            throw unverifiableCompletedWork();
        }

        Map<Long, Long> userByWorker = new LinkedHashMap<>();
        List<WeightedWorker> regularWork = new ArrayList<>();
        for (Review review : published) {
            Worker worker = review.getWorker();
            if (review.getPublishedDate() == null || !validWithImmutableIdentity(worker)) {
                throw unverifiableCompletedWork();
            }
            Long existingUserId = userByWorker.putIfAbsent(worker.getId(), worker.getUser().getId());
            if (existingUserId != null && !existingUserId.equals(worker.getUser().getId())) {
                throw unverifiableCompletedWork();
            }
            regularWork.add(new WeightedWorker(worker, positiveWeight(review.getPrice())));
        }

        Map<Long, MutableShare> shares = new LinkedHashMap<>();
        addAllocated(shares, regularWork, canonicalGross, null, 0);
        List<SpecialistShare> result = shares.values().stream()
                .filter(value -> value.user != null
                        && value.user.getId() != null
                        && value.workerId != null
                        && value.sumKopecks > 0)
                .map(value -> new SpecialistShare(
                        value.user,
                        value.workerId,
                        BigDecimal.valueOf(value.sumKopecks, 2),
                        value.units
                ))
                .toList();
        BigDecimal attributed = result.stream()
                .map(SpecialistShare::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (result.isEmpty() || attributed.compareTo(canonicalGross) != 0) {
            throw unverifiableCompletedWork();
        }
        return result;
    }

    private List<SpecialistShare> attribute(
            Order order,
            BigDecimal payableSum,
            boolean deriveAdditionalFromTasks
    ) {
        if (order == null || order.getId() == null) {
            return List.of();
        }
        BigDecimal regularTotal = money(order.getSum());
        List<BadReviewTask> completedTasks = badReviewTaskRepository.findAllByOrderIdAndStatus(
                order.getId(),
                BadReviewTaskStatus.DONE
        );
        BigDecimal additionalTotal = deriveAdditionalFromTasks
                ? completedTasks.stream()
                        .map(BadReviewTask::getPrice)
                        .map(this::money)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : money(money(payableSum).subtract(regularTotal).max(BigDecimal.ZERO));
        Map<Long, MutableShare> shares = new LinkedHashMap<>();

        List<WeightedWorker> regularWork = reviewRepository.getAllByOrderId(order.getId()).stream()
                .filter(Review::isPublish)
                .map(review -> weighted(review.getWorker(), review.getPrice()))
                .filter(value -> value != null)
                .toList();
        addAllocated(shares, regularWork, regularTotal, order.getWorker(), Math.max(0, order.getAmount()));

        List<WeightedWorker> additionalWork = completedTasks.stream()
                .map(task -> weighted(task.getWorker(), task.getPrice()))
                .filter(value -> value != null)
                .toList();
        addAllocated(shares, additionalWork, additionalTotal, order.getWorker(), completedTasks.size());

        return shares.values().stream()
                .filter(value -> value.user != null && value.sumKopecks > 0)
                .map(value -> new SpecialistShare(
                        value.user,
                        value.workerId,
                        BigDecimal.valueOf(value.sumKopecks, 2),
                        value.units
                ))
                .toList();
    }

    private void addAllocated(
            Map<Long, MutableShare> target,
            List<WeightedWorker> work,
            BigDecimal total,
            Worker fallback,
            int fallbackUnits
    ) {
        long totalKopecks = toKopecks(total);
        if (totalKopecks <= 0) {
            return;
        }
        List<WeightedWorker> effective = new ArrayList<>(work);
        if (effective.isEmpty() && valid(fallback)) {
            effective.add(new WeightedWorker(fallback, BigDecimal.ONE));
        }
        if (effective.isEmpty()) {
            return;
        }

        Map<Long, WorkerWeight> grouped = new LinkedHashMap<>();
        for (WeightedWorker item : effective) {
            WorkerWeight current = grouped.computeIfAbsent(
                    item.worker().getId(),
                    ignored -> new WorkerWeight(item.worker())
            );
            current.weight = current.weight.add(item.weight());
            current.units++;
        }
        BigDecimal totalWeight = grouped.values().stream()
                .map(value -> value.weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
            totalWeight = BigDecimal.valueOf(grouped.size());
            grouped.values().forEach(value -> value.weight = BigDecimal.ONE);
        }

        long allocated = 0L;
        List<Remainder> remainders = new ArrayList<>();
        for (WorkerWeight item : grouped.values()) {
            BigDecimal exact = BigDecimal.valueOf(totalKopecks)
                    .multiply(item.weight)
                    .divide(totalWeight, 12, RoundingMode.DOWN);
            long floor = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            allocated += floor;
            remainders.add(new Remainder(item, floor, exact.subtract(BigDecimal.valueOf(floor))));
        }
        remainders.sort(Comparator.comparing(Remainder::fraction).reversed()
                .thenComparing(value -> value.weight().worker.getId()));
        long missing = totalKopecks - allocated;
        for (int i = 0; i < missing; i++) {
            Remainder item = remainders.get(i % remainders.size());
            item.extra++;
        }
        for (Remainder allocation : remainders) {
            Worker worker = allocation.weight().worker;
            MutableShare share = target.computeIfAbsent(
                    worker.getId(),
                    ignored -> new MutableShare(worker.getId(), worker.getUser())
            );
            share.sumKopecks += allocation.floor() + allocation.extra;
            share.units += grouped.size() == 1 && work.isEmpty()
                    ? Math.max(0, fallbackUnits)
                    : allocation.weight().units;
        }
    }

    private WeightedWorker weighted(Worker worker, BigDecimal value) {
        if (!valid(worker)) {
            return null;
        }
        return new WeightedWorker(worker, positiveWeight(value));
    }

    private BigDecimal positiveWeight(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ONE
                : value;
    }

    private boolean valid(Worker worker) {
        return worker != null && worker.getId() != null && worker.getUser() != null;
    }

    private boolean validWithImmutableIdentity(Worker worker) {
        return valid(worker) && worker.getUser().getId() != null;
    }

    private ResponseStatusException unverifiableCompletedWork() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Исполнитель каждой опубликованной работы должен быть подтвержден до фиксации вознаграждения"
        );
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private long toKopecks(BigDecimal value) {
        return money(value).movePointRight(2).longValueExact();
    }

    public record SpecialistShare(User user, Long workerId, BigDecimal grossAmount, int workUnits) {
    }

    private record WeightedWorker(Worker worker, BigDecimal weight) {
    }

    private static final class WorkerWeight {
        private final Worker worker;
        private BigDecimal weight = BigDecimal.ZERO;
        private int units;

        private WorkerWeight(Worker worker) {
            this.worker = worker;
        }
    }

    private static final class MutableShare {
        private final Long workerId;
        private final User user;
        private long sumKopecks;
        private int units;

        private MutableShare(Long workerId, User user) {
            this.workerId = workerId;
            this.user = user;
        }
    }

    private static final class Remainder {
        private final WorkerWeight weight;
        private final long floor;
        private final BigDecimal fraction;
        private long extra;

        private Remainder(WorkerWeight weight, long floor, BigDecimal fraction) {
            this.weight = weight;
            this.floor = floor;
            this.fraction = fraction;
        }

        private WorkerWeight weight() {
            return weight;
        }

        private long floor() {
            return floor;
        }

        private BigDecimal fraction() {
            return fraction;
        }
    }
}
