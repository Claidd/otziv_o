package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes stale cards of control days older than {@value #EXPIRY_DAYS} days (05.11.2026: ~900 cards of
 * July–October stayed OPEN/ACTION_TAKEN/DEFERRED for ever, because past days are never synchronized again).
 *
 * <p>The outcome is explicit and distinguishable: RESOLVED, automatic, {@code expired_at} set, comment
 * {@value #EXPIRED_COMMENT}, an ITEM_RESOLVED event without an actor (system). A parent action item whose
 * cards are all closed by now is resolved the same way. The control row of the day (status, quality score,
 * closure) is never touched, so a closed day is neither reopened nor rescored. Nothing else changes:
 * no order, invoice, chat, reminder or Telegram message. Cards under an unfinished manual delivery fence
 * are left for reconciliation.
 *
 * <p>Daily under a DB lease; every batch renews the lease inside its transaction. A run handles the
 * backlog in bounded batches and the next run continues from where it stopped.
 */
@Component
@Slf4j
public class ManagerControlCardExpiryJob {

    static final String LEASE_NAME = "manager-control-card-expiry";
    static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    static final int EXPIRY_DAYS = 30;
    static final int BATCH_SIZE = 100;
    static final int MAX_BATCHES_PER_RUN = 50;
    static final Duration RUN_BUDGET = Duration.ofMinutes(5);
    static final String EXPIRED_COMMENT = "Срок истёк: карточка старше 30 дней закрыта автоматически";
    static final String EXPIRED_PARENT_COMMENT = "Срок истёк: все карточки пункта старше 30 дней закрыты автоматически";
    private static final List<String> DELIVERY_FENCES = List.of("client_message_delivery_", "client_reply_delivery_");

    private final SchedulerLeaseService leases;
    private final ManagerDailyControlConcreteItemRepository cards;
    private final ManagerDailyControlItemRepository items;
    private final ManagerDailyControlEventRepository events;
    private final ManagerControlReadSnapshots readSnapshots;
    private final ManagerControlCardLifecycle cardLifecycle;
    private final TransactionTemplate transaction;

    public ManagerControlCardExpiryJob(SchedulerLeaseService leases,
                                       ManagerDailyControlConcreteItemRepository cards,
                                       ManagerDailyControlItemRepository items,
                                       ManagerDailyControlEventRepository events,
                                       ManagerControlReadSnapshots readSnapshots,
                                       ManagerControlCardLifecycle cardLifecycle,
                                       PlatformTransactionManager transactionManager) {
        this.leases = leases;
        this.cards = cards;
        this.items = items;
        this.events = events;
        this.readSnapshots = readSnapshots;
        this.cardLifecycle = cardLifecycle;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(60);
    }

    @Scheduled(cron = "${manager-control.card-expiry.cron:0 40 3 * * *}",
            zone = "${manager-control.card-expiry.zone:Asia/Irkutsk}")
    public void expireStaleCards() {
        try {
            run(LocalDateTime.now());
        } catch (RuntimeException failure) {
            log.warn("Manager control card expiry failed: {}", failure.toString(), failure);
        }
    }

    /**
     * First control day that is kept (cards of earlier days expire): today minus {@value #EXPIRY_DAYS}
     * days, and never later than yesterday, so today's and yesterday's cards can never expire.
     */
    static LocalDate keptFrom(LocalDate today) {
        LocalDate cutoff = today.minusDays(EXPIRY_DAYS);
        LocalDate yesterday = today.minusDays(1);
        return cutoff.isBefore(yesterday) ? cutoff : yesterday;
    }

    /** Number of cards closed by this run; 0 when another instance holds the lease. */
    int run(LocalDateTime now) {
        var acquired = leases.tryAcquire(LEASE_NAME, LEASE_DURATION);
        if (acquired.isEmpty()) {
            return 0;
        }
        SchedulerLeaseService.Lease lease = acquired.get();
        int expired = 0;
        try {
            LocalDate before = keptFrom(now.toLocalDate());
            long deadline = System.nanoTime() + RUN_BUDGET.toNanos();
            long afterId = 0;
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN && System.nanoTime() < deadline; batch++) {
                List<Long> ids = cards.findUnresolvedIdsOfControlDaysBefore(
                        before, ManagerDailyControlItemStatus.RESOLVED, afterId, PageRequest.of(0, BATCH_SIZE));
                if (ids == null || ids.isEmpty()) {
                    break;
                }
                afterId = ids.getLast();
                Integer closed = transaction.execute(status -> {
                    leases.holdForTransaction(lease, LEASE_DURATION);
                    return expireBatch(ids, before, now);
                });
                expired += closed == null ? 0 : closed;
            }
            if (expired > 0) {
                log.info("Manager control card expiry: {} cards of control days before {} closed", expired, before);
            }
            return expired;
        } finally {
            leases.release(lease);
        }
    }

    int expireBatch(List<Long> ids, LocalDate before, LocalDateTime now) {
        Map<Long, ManagerDailyControlItem> parents = new LinkedHashMap<>();
        Set<Long> managerIds = new LinkedHashSet<>();
        int closed = 0;
        for (ManagerDailyControlConcreteItem card : cards.findAllByIdInForUpdate(ids)) {
            if (!expires(card, before, now)) {
                continue;
            }
            ManagerDailyControl control = card.getControl();
            if (card.getStatus() == ManagerDailyControlItemStatus.OPEN) {
                card.setAutoClosedEpisodeCount(card.getAutoClosedEpisodeCount() + 1);
            }
            card.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            card.setActionType(ManagerDailyControlActionType.RESOLVED);
            card.setAutomaticResolution(true);
            card.setResolvedAt(now);
            card.setExpiredAt(now);
            card.setFollowUpAt(null);
            card.setComment(EXPIRED_COMMENT);
            cards.save(card);
            events.save(event(control, card.getParentItem(), EXPIRED_COMMENT + ": " + card.getTitle()));
            if (card.getParentItem() != null && card.getParentItem().getId() != null) {
                parents.putIfAbsent(card.getParentItem().getId(), card.getParentItem());
            }
            if (control.managerId() != null) {
                managerIds.add(control.managerId());
            }
            closed++;
        }
        parents.values().forEach(parent -> resolveParentIfAllCardsClosed(parent, now));
        if (!managerIds.isEmpty()) {
            readSnapshots.invalidate(managerIds);
            cardLifecycle.invalidateManagerPerformance();
        }
        return closed;
    }

    /** Re-checked under the card lock: unresolved, of an old day that is not current, without a delivery fence. */
    static boolean expires(ManagerDailyControlConcreteItem card, LocalDate before, LocalDateTime now) {
        if (card == null || card.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        ManagerDailyControl control = card.getControl();
        if (control == null || control.getControlDate() == null || !control.getControlDate().isBefore(before)
                || ManagerControlDayLifecycle.isControlDayCurrent(control, now)) {
            return false;
        }
        String comment = card.getComment() == null ? "" : card.getComment().trim();
        return DELIVERY_FENCES.stream().noneMatch(comment::startsWith);
    }

    /** Same rule as a manager's last card: every expected card exists and none is left unresolved. */
    void resolveParentIfAllCardsClosed(ManagerDailyControlItem parent, LocalDateTime now) {
        if (parent.getGroup() != ManagerDailyControlGroup.ACTION || parent.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return;
        }
        List<ManagerDailyControlConcreteItem> siblings = cards.findByParentItem(parent);
        if (siblings.isEmpty() || siblings.size() < parent.getCount()
                || siblings.stream().anyMatch(card -> card.getStatus() != ManagerDailyControlItemStatus.RESOLVED)) {
            return;
        }
        if (parent.getStatus() == ManagerDailyControlItemStatus.OPEN) {
            parent.setAutoClosedEpisodeCount(parent.getAutoClosedEpisodeCount() + Math.max(1, parent.getCount()));
        }
        parent.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        parent.setActionType(ManagerDailyControlActionType.RESOLVED);
        parent.setAutomaticResolution(true);
        parent.setResolvedAt(now);
        parent.setComment(EXPIRED_PARENT_COMMENT);
        items.save(parent);
        events.save(event(parent.getControl(), parent, EXPIRED_PARENT_COMMENT + ": " + parent.getLabel()));
    }

    private static ManagerDailyControlEvent event(ManagerDailyControl control, ManagerDailyControlItem item, String comment) {
        ManagerDailyControlEvent event = new ManagerDailyControlEvent();
        event.setControl(control);
        event.setItem(item);
        event.setActorUserId(null);
        event.setEventType(ManagerDailyControlEventType.ITEM_RESOLVED);
        event.setActionType(ManagerDailyControlActionType.RESOLVED);
        event.setComment(comment.length() <= 1000 ? comment : comment.substring(0, 1000));
        return event;
    }
}
