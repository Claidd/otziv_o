package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.model.Order;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tracks a persistent stage imbalance inside a collecting common invoice.
 *
 * <p>The financial status and composition of the invoice are never changed by
 * this service. It only maintains per-position control timestamps used by the
 * manager board and daily remarks.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommonInvoicePublicationBlockerService {

    public static final int ATTENTION_AFTER_HOURS = 48;

    private static final Set<String> PRE_PUBLICATION_STATUSES = Set.of(
            "Новый",
            "Нагул",
            "В проверку",
            "На проверке",
            "Коррекция"
    );
    private static final Set<String> PUBLICATION_OR_LATER_STATUSES = Set.of(
            "Публикация",
            "Опубликовано",
            CommonBillingService.STATUS_WAITING_COMMON_INVOICE,
            "Выставлен счет",
            "Напоминание",
            "Требует внимания",
            "Не оплачено",
            "Оплачено"
    );
    private static final Set<CommonInvoiceStatus> TRACKED_INVOICE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.NEEDS_ATTENTION
    );

    private final CommonInvoiceRepository invoiceRepository;
    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    @Scheduled(fixedDelayString = "${common-billing.publication-blockers.delay-ms:300000}", initialDelay = 60000L)
    @Transactional
    public void reconcileScheduled() {
        try {
            int changed = reconcileAll();
            if (changed > 0) {
                log.info("Common invoice publication blocker timestamps reconciled: changed={}", changed);
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось пересчитать блокеры публикации общих счетов", e);
        }
    }

    @Transactional
    public int reconcileAll() {
        List<CommonInvoice> invoices = invoiceRepository.findBoardInvoices(TRACKED_INVOICE_STATUSES);
        if (invoices.isEmpty()) {
            return 0;
        }
        List<Long> invoiceIds = invoices.stream()
                .map(CommonInvoice::getId)
                .filter(Objects::nonNull)
                .toList();
        if (invoiceIds.isEmpty()) {
            return 0;
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdsWithOrders(invoiceIds);
        int changed = 0;
        for (CommonInvoice invoice : invoices) {
            List<CommonInvoiceOrder> invoiceItems = items.stream()
                    .filter(item -> item.getInvoice() != null && Objects.equals(invoice.getId(), item.getInvoice().getId()))
                    .toList();
            changed += reconcile(invoice, invoiceItems, LocalDateTime.now());
        }
        return changed;
    }

    @Transactional
    public int reconcileInvoice(Long invoiceId) {
        if (invoiceId == null || invoiceId <= 0) {
            return 0;
        }
        CommonInvoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        if (invoice == null) {
            return 0;
        }
        return reconcile(
                invoice,
                invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId),
                LocalDateTime.now()
        );
    }

    @Transactional
    public int reconcileOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return 0;
        }
        return invoiceOrderRepository.findByOrderIdWithInvoice(orderId)
                .map(CommonInvoiceOrder::getInvoice)
                .map(CommonInvoice::getId)
                .map(this::reconcileInvoice)
                .orElse(0);
    }

    public int reconcile(CommonInvoice invoice, List<CommonInvoiceOrder> items, LocalDateTime now) {
        List<CommonInvoiceOrder> safeItems = items == null ? List.of() : items;
        boolean trackedInvoice = invoice != null && TRACKED_INVOICE_STATUSES.contains(invoice.getStatus());
        boolean hasPublicationOrLater = trackedInvoice && safeItems.stream()
                .map(CommonInvoiceOrder::getOrder)
                .anyMatch(this::isPublicationOrLater);
        LocalDateTime publicationAnchor = hasPublicationOrLater
                ? earliestPublicationAnchor(safeItems, now)
                : null;

        int changed = 0;
        for (CommonInvoiceOrder item : safeItems) {
            boolean shouldTrack = hasPublicationOrLater && isPrePublication(item == null ? null : item.getOrder());
            if (shouldTrack && item.getPublicationBlockerSince() == null) {
                LocalDateTime linkedAt = item.getInvoiceLinkedAt() == null
                        ? item.getCreatedAt()
                        : item.getInvoiceLinkedAt();
                item.setPublicationBlockerSince(laterOf(linkedAt, publicationAnchor, now));
                invoiceOrderRepository.save(item);
                changed++;
            } else if (!shouldTrack && item.getPublicationBlockerSince() != null) {
                item.setPublicationBlockerSince(null);
                invoiceOrderRepository.save(item);
                changed++;
            }
        }
        return changed;
    }

    public boolean hasOverdueBlockers(Collection<CommonInvoiceOrder> items, LocalDateTime now) {
        return !overdueBlockers(items, now).isEmpty();
    }

    public List<CommonInvoiceOrder> overdueBlockers(Collection<CommonInvoiceOrder> items, LocalDateTime now) {
        LocalDateTime cutoff = attentionCutoff(now);
        Collection<CommonInvoiceOrder> safeItems = items == null ? List.of() : items;
        return safeItems.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getPublicationBlockerSince() != null)
                .filter(item -> !item.getPublicationBlockerSince().isAfter(cutoff))
                .filter(item -> isPrePublication(item.getOrder()))
                .toList();
    }

    public LocalDateTime attentionCutoff(LocalDateTime now) {
        return (now == null ? LocalDateTime.now() : now).minusHours(ATTENTION_AFTER_HOURS);
    }

    public boolean isPrePublication(Order order) {
        return PRE_PUBLICATION_STATUSES.contains(statusTitle(order));
    }

    public boolean isPublicationOrLater(Order order) {
        return PUBLICATION_OR_LATER_STATUSES.contains(statusTitle(order));
    }

    private LocalDateTime earliestPublicationAnchor(List<CommonInvoiceOrder> items, LocalDateTime fallback) {
        return items.stream()
                .filter(item -> item != null && isPublicationOrLater(item.getOrder()))
                .map(item -> {
                    Order order = item.getOrder();
                    if (order != null && order.getStatusChangedAt() != null) {
                        return order.getStatusChangedAt();
                    }
                    if (item.getUpdatedAt() != null) {
                        return item.getUpdatedAt();
                    }
                    return item.getCreatedAt();
                })
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(fallback == null ? LocalDateTime.now() : fallback);
    }

    private LocalDateTime laterOf(LocalDateTime first, LocalDateTime second, LocalDateTime fallback) {
        if (first == null && second == null) {
            return fallback == null ? LocalDateTime.now() : fallback;
        }
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private String statusTitle(Order order) {
        return order == null || order.getStatus() == null || order.getStatus().getTitle() == null
                ? ""
                : order.getStatus().getTitle().trim();
    }
}
