package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes «Ошибки счетов и сообщений» cards of earlier control days whose problem the autoresponder no longer
 * reports. Today's cards are closed by the board synchronization, but a past control day is never synchronized
 * again, so a card of a failure that has since recovered (for example a WhatsApp gateway restart) stayed open
 * until the 30-day expiry and kept showing on the board.
 *
 * <p>The rule is the same as the board's own: a card is closed when its key is no longer among the manager's
 * current automation issues and no manual delivery of it is pending. Only the card and its parent item change;
 * no order, invoice, queue, chat or message is touched. Runs hourly under a DB lease, in bounded batches.
 */
@Component
@Slf4j
public class ManagerControlAutomationCardSweep {

    static final String LEASE_NAME = "manager-control-automation-card-sweep";
    static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    static final int BATCH_SIZE = 200;
    static final int MAX_BATCHES_PER_RUN = 20;
    static final int MAX_ISSUES = 10_000;
    static final String RESOLVED_COMMENT = "Проблема больше не актуальна и закрыта автоматически: автоответчик больше "
            + "не сообщает об этой проблеме; состояние доставки не изменено.";
    static final List<String> TYPES = List.of(
            ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE,
            ManagerAutomationFailureService.ENTITY_COMMON_INVOICE_AUTOMATION
    );

    private final SchedulerLeaseService leases;
    private final ManagerDailyControlConcreteItemRepository cards;
    private final ManagerDailyControlEventRepository events;
    private final ManagerAutomationFailureService automationFailures;
    private final ManagerControlOrderDeliveryState deliveryState;
    private final ManagerControlCardLifecycle cardLifecycle;
    private final ManagerControlReadSnapshots readSnapshots;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;

    public ManagerControlAutomationCardSweep(SchedulerLeaseService leases,
                                             ManagerDailyControlConcreteItemRepository cards,
                                             ManagerDailyControlEventRepository events,
                                             ManagerAutomationFailureService automationFailures,
                                             ManagerControlOrderDeliveryState deliveryState,
                                             ManagerControlCardLifecycle cardLifecycle,
                                             ManagerControlReadSnapshots readSnapshots,
                                             PlatformTransactionManager transactionManager) {
        this.leases = leases;
        this.cards = cards;
        this.events = events;
        this.automationFailures = automationFailures;
        this.deliveryState = deliveryState;
        this.cardLifecycle = cardLifecycle;
        this.readSnapshots = readSnapshots;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(60);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setTimeout(120);
    }

    @Scheduled(cron = "${manager-control.automation-card-sweep.cron:0 25 * * * *}",
            zone = "${manager-control.automation-card-sweep.zone:Asia/Irkutsk}")
    public void resolveRecoveredCards() {
        try {
            run(LocalDateTime.now());
        } catch (RuntimeException failure) {
            log.warn("Manager control automation card sweep failed: {}", failure.toString(), failure);
        }
    }

    /** Number of cards closed; 0 when another instance holds the lease. */
    int run(LocalDateTime now) {
        var acquired = leases.tryAcquire(LEASE_NAME, LEASE_DURATION);
        if (acquired.isEmpty()) {
            return 0;
        }
        SchedulerLeaseService.Lease lease = acquired.get();
        int closed = 0;
        try {
            LocalDate today = now.toLocalDate();
            LocalDate from = today.minusDays(ManagerControlCardExpiryJob.EXPIRY_DAYS);
            // Issues are computed once per manager per run; a failed computation leaves that manager's cards open.
            Map<Long, Set<String>> liveKeys = new HashMap<>();
            long afterId = 0;
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
                List<Long> ids = cards.findUnresolvedIdsOfTypesBetweenControlDays(
                        TYPES, from, today, ManagerDailyControlItemStatus.RESOLVED, afterId, PageRequest.of(0, BATCH_SIZE));
                if (ids == null || ids.isEmpty()) {
                    break;
                }
                afterId = ids.getLast();
                Set<Long> resolvable = readTransaction.execute(status -> resolvableIds(ids, liveKeys));
                if (resolvable == null || resolvable.isEmpty()) {
                    continue;
                }
                Integer done = transaction.execute(status -> {
                    leases.holdForTransaction(lease, LEASE_DURATION);
                    return resolve(resolvable, from, today, now);
                });
                closed += done == null ? 0 : done;
            }
            if (closed > 0) {
                log.info("Manager control automation cards of earlier days closed: {}", closed);
            }
            return closed;
        } finally {
            leases.release(lease);
        }
    }

    private Set<Long> resolvableIds(List<Long> ids, Map<Long, Set<String>> liveKeys) {
        Set<Long> resolvable = new LinkedHashSet<>();
        for (ManagerDailyControlConcreteItem card : cards.findAllById(ids)) {
            ManagerDailyControl control = card.getControl();
            Long managerId = control == null ? null : control.managerId();
            if (managerId == null || card.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
                continue;
            }
            if (!liveKeys.containsKey(managerId)) {
                liveKeys.put(managerId, currentIssueKeys(control));
            }
            Set<String> live = liveKeys.get(managerId);
            if (live != null && !live.contains(key(card))) {
                resolvable.add(card.getId());
            }
        }
        return resolvable;
    }

    private Set<String> currentIssueKeys(ManagerDailyControl control) {
        try {
            return automationFailures.issues(control.getManager(), MAX_ISSUES).stream()
                    .map(ManagerAutomationFailureService.AutomationFailureIssue::deduplicationKey)
                    .collect(Collectors.toSet());
        } catch (RuntimeException unavailable) {
            log.warn("Automation issues unavailable, cards kept: managerId={}, errorType={}",
                    control.managerId(), unavailable.getClass().getSimpleName());
            return null;
        }
    }

    /** Re-checked under the card lock: still unresolved, of an earlier day in the window, no pending delivery. */
    int resolve(Set<Long> ids, LocalDate from, LocalDate today, LocalDateTime now) {
        Set<Long> managerIds = new LinkedHashSet<>();
        int closed = 0;
        for (ManagerDailyControlConcreteItem card : cards.findAllByIdInForUpdate(ids)) {
            ManagerDailyControl control = card.getControl();
            if (card.getStatus() == ManagerDailyControlItemStatus.RESOLVED || control == null
                    || control.getControlDate() == null || control.getControlDate().isBefore(from)
                    || !control.getControlDate().isBefore(today) || !TYPES.contains(card.getEntityType())
                    || deliveryState.pending(card)) {
                continue;
            }
            cardLifecycle.recordConcreteEpisode(card, ManagerDailyControlItemStatus.RESOLVED, true);
            card.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            card.setActionType(ManagerDailyControlActionType.RESOLVED);
            card.setComment(RESOLVED_COMMENT);
            card.setResolvedAt(now);
            card.setAutomaticResolution(true);
            card.setFollowUpAt(null);
            card.setLastManualTouchAt(null);
            cards.save(card);
            events.save(event(control, card));
            cardLifecycle.updateParentItemFromConcreteItems(card.getParentItem());
            if (control.managerId() != null) {
                managerIds.add(control.managerId());
            }
            closed++;
        }
        if (!managerIds.isEmpty()) {
            readSnapshots.invalidate(managerIds);
            cardLifecycle.invalidateManagerPerformance();
        }
        return closed;
    }

    private static String key(ManagerDailyControlConcreteItem card) {
        return card.getEntityType() + ":" + card.getEntityId();
    }

    private static ManagerDailyControlEvent event(ManagerDailyControl control, ManagerDailyControlConcreteItem card) {
        ManagerDailyControlEvent event = new ManagerDailyControlEvent();
        event.setControl(control);
        event.setItem(card.getParentItem());
        event.setActorUserId(null);
        event.setEventType(ManagerDailyControlEventType.ITEM_RESOLVED);
        event.setActionType(ManagerDailyControlActionType.RESOLVED);
        String comment = RESOLVED_COMMENT + ": " + card.getTitle();
        event.setComment(comment.length() <= 1000 ? comment : comment.substring(0, 1000));
        return event;
    }
}
