package com.hunt.otziv.config.metrics;

import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class BusinessHealthMetrics {

    private static final String STATUS_ALL = "Все";
    private static final String BOT_READY_STATUS = "Новый";
    private static final Duration BUSINESS_METRICS_CACHE_TTL = Duration.ofHours(3);
    private static final List<String> ORDER_STATUSES = List.of(
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Публикация",
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено",
            "Архив",
            "Оплачено"
    );

    private final MeterRegistry meterRegistry;
    private final LeadsRepository leadsRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final BotsRepository botsRepository;

    @jakarta.annotation.PostConstruct
    void registerMetrics() {
        registerGauge("otziv.business.leads", "Lead count by status", "status", STATUS_ALL, leadsRepository::count);
        for (LeadStatus status : LeadStatus.values()) {
            registerGauge("otziv.business.leads", "Lead count by status", "status", status.title,
                    () -> leadsRepository.countByLidStatus(status.title));
        }

        registerGauge("otziv.business.orders", "Order count by status", "status", STATUS_ALL, orderRepository::countAllOrders);
        for (String status : ORDER_STATUSES) {
            registerGauge("otziv.business.orders", "Order count by status", "status", status,
                    () -> orderRepository.countByStatusTitle(status));
        }

        registerGauge("otziv.business.reviews", "Review operational count by state", "state", "all", reviewRepository::count);
        registerGauge("otziv.business.reviews", "Review operational count by state", "state", "unpublished", reviewRepository::countUnpublished);
        registerGauge("otziv.business.reviews", "Review operational count by state", "state", "unpublished_not_archive", reviewRepository::countUnpublishedNotArchive);
        registerGauge("otziv.business.reviews", "Review operational count by state", "state", "due_to_publish",
                () -> reviewRepository.countDueToPublish(LocalDate.now()));
        registerGauge("otziv.business.reviews", "Review operational count by state", "state", "due_to_walk",
                () -> reviewRepository.countDueToWalk(LocalDate.now()));

        registerGauge("otziv.business.bots", "Bot count by state", "state", "all", botsRepository::count);
        registerGauge("otziv.business.bots", "Bot count by state", "state", "active", botsRepository::countByActiveTrue);
        registerGauge("otziv.business.bots", "Bot count by state", "state", "inactive", botsRepository::countByActiveFalse);
        registerGauge("otziv.business.bots", "Bot count by state", "state", "ready", () -> botsRepository.countActiveByStatus(BOT_READY_STATUS));
    }

    private void registerGauge(String name, String description, String tagName, String tagValue, Supplier<Number> supplier) {
        CachedGaugeValue cachedValue = new CachedGaugeValue(supplier, BUSINESS_METRICS_CACHE_TTL);

        Gauge.builder(name, cachedValue, CachedGaugeValue::value)
                .description(description)
                .tag(tagName, tagValue)
                .strongReference(true)
                .register(meterRegistry);
    }

    private static final class CachedGaugeValue {

        private final Supplier<Number> supplier;
        private final long ttlNanos;
        private volatile double cachedValue = Double.NaN;
        private volatile long expiresAtNanos = 0L;
        private volatile boolean initialized = false;

        private CachedGaugeValue(Supplier<Number> supplier, Duration ttl) {
            this.supplier = supplier;
            this.ttlNanos = ttl.toNanos();
        }

        private synchronized double value() {
            long now = System.nanoTime();
            if (initialized && now < expiresAtNanos) {
                return cachedValue;
            }

            try {
                Number value = supplier.get();
                cachedValue = value == null ? Double.NaN : value.doubleValue();
                initialized = true;
                expiresAtNanos = now + ttlNanos;
                return cachedValue;
            } catch (RuntimeException exception) {
                return initialized ? cachedValue : Double.NaN;
            }
        }
    }
}
