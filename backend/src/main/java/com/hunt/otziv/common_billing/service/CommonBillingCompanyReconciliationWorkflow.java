package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceMembershipWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePaymentRouteWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceManualPaymentWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceTochkaReconciliationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDeliveryService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDetailsAssembler.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceInitializationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountCompanyRepository;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.PaymentLink;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

/** Owns durable reconciliation of enabled company links and their bounded, fenced jobs. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonBillingCompanyReconciliationWorkflow {

    private final CommonInvoiceMembershipWorkflow invoiceMembershipWorkflow;

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceInitializationService invoiceInitialization;

    public static final String STATUS_WAITING_COMMON_INVOICE = CommonInvoiceSettlementService.STATUS_WAITING_COMMON_INVOICE;

    private final CommonInvoiceSettlementService settlementService;

    static final Set<String> BACKFILL_STATUSES = Set.of("Новый", "Нагул", "В проверку", "Коррекция", "На проверке", "Публикация", STATUS_PUBLIC, STATUS_TO_PAY, STATUS_REMINDER, STATUS_WAITING_COMMON_INVOICE);

    static final int COMPANY_RECONCILE_MAX_ATTEMPTS = 20;

    static final java.time.Duration COMPANY_RECONCILE_LEASE = java.time.Duration.ofMinutes(5);

    static final java.time.Duration COMPANY_RECONCILE_MAX_BACKOFF = java.time.Duration.ofMinutes(30);

    private final CommonBillingAccountRepository accountRepository;

    private final CommonBillingAccountCompanyRepository accountCompanyRepository;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final OrderRepository orderRepository;

    private final CommonInvoicePublicationBlockerService publicationBlockerService;

    Map<Long, Order> lockOrderAggregatesWithEntities(Collection<Long> orderIds) {
        return settlementService.lockOrderAggregatesWithEntities(orderIds);
    }

    Map<Long, List<PaymentLink>> lockPaymentLinksForOrders(Collection<Long> orderIds) {
        return invoiceInitialization.lockPaymentLinksForOrders(orderIds);
    }

    int closeProvablyUnstartedStandaloneRoutesOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder, Long invoiceId) {
        return invoiceInitialization.closeProvablyUnstartedStandaloneRoutesOrThrow(paymentLinksByOrder, invoiceId);
    }

    <T> T writeTransaction(Supplier<T> action) {
        return settlementService.writeTransaction(action);
    }

    void ensureCompanyNotEnabledInAnotherAccount(Long accountId, Long companyId) {
        invoiceMembershipWorkflow.ensureCompanyNotEnabledInAnotherAccount(accountId, companyId);
    }

    void scheduleCompanyReconcileAfterCommit(Long linkId) {
        Runnable reconcile = () -> {
            try {
                processCompanyReconcileJob(linkId);
            } catch (RuntimeException e) {
                // The committed link remains reconcile_pending and will be
                // reclaimed by the bounded scheduler after a crash/failure.
                log.error("Не удалось запустить сверку связи общего счета {} после коммита", linkId, e);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    reconcile.run();
                }
            });
            return;
        }
        reconcile.run();
    }

    public int reconcilePendingCompanyLinks(int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        List<Long> candidateIds = accountCompanyRepository.findPendingReconciliationIds(LocalDateTime.now(), PageRequest.of(0, limit));
        int processed = 0;
        for (Long linkId : candidateIds) {
            try {
                if (processCompanyReconcileJob(linkId)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                log.error("Не удалось захватить задачу сверки связи общего счета {}", linkId, e);
            }
        }
        return processed;
    }

    boolean processCompanyReconcileJob(Long linkId) {
        PreparedCompanyReconcile job = writeTransaction(() -> claimCompanyReconcile(linkId));
        if (job == null) {
            return false;
        }
        try {
            writeTransaction(() -> {
                reconcileEnabledCompany(job);
                return null;
            });
            return true;
        } catch (RuntimeException failure) {
            try {
                writeTransaction(() -> {
                    failCompanyReconcile(job, failure);
                    return null;
                });
            } catch (RuntimeException finalizationFailure) {
                failure.addSuppressed(finalizationFailure);
            }
            log.error("Не удалось свести заказы компании {} с общим плательщиком {}, попытка {}", job.companyId(), job.accountId(), job.attempt(), failure);
            return false;
        }
    }

    PreparedCompanyReconcile claimCompanyReconcile(Long linkId) {
        CommonBillingAccountCompany link = linkId == null ? null : accountCompanyRepository.findByIdForUpdate(linkId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (link == null || !link.isEnabled() || link.getAccount() == null || !link.getAccount().isEnabled() || !link.isReconcilePending() || (link.getReconcileNextAttemptAt() != null && link.getReconcileNextAttemptAt().isAfter(now)) || (link.getReconcileLeaseUntil() != null && link.getReconcileLeaseUntil().isAfter(now))) {
            return null;
        }
        if (link.getReconcileAttempts() >= COMPANY_RECONCILE_MAX_ATTEMPTS) {
            link.setEnabled(false);
            link.setReconcilePending(false);
            link.setReconcileLeaseToken(null);
            link.setReconcileLeaseUntil(null);
            link.setReconcileLastError(limit("company_reconcile_failed_final: исчерпаны попытки", 512));
            accountCompanyRepository.save(link);
            return null;
        }
        String leaseToken = UUID.randomUUID().toString();
        int attempt = link.getReconcileAttempts() + 1;
        link.setReconcileAttempts(attempt);
        link.setReconcileLeaseToken(leaseToken);
        link.setReconcileLeaseUntil(now.plus(COMPANY_RECONCILE_LEASE));
        accountCompanyRepository.save(link);
        return new PreparedCompanyReconcile(link.getId(), link.getAccount().getId(), link.getCompany().getId(), leaseToken, attempt);
    }

    void failCompanyReconcile(PreparedCompanyReconcile job, RuntimeException failure) {
        if (job == null) {
            return;
        }
        CommonBillingAccountCompany link = accountCompanyRepository.findByIdForUpdate(job.linkId()).orElse(null);
        if (link == null || !normalize(job.leaseToken()).equals(normalize(link.getReconcileLeaseToken()))) {
            return;
        }
        String error = "company_reconcile_failed: " + readableException(failure);
        link.setReconcileLeaseToken(null);
        link.setReconcileLeaseUntil(null);
        if (!link.isEnabled()) {
            clearCompanyReconcileState(link);
        } else if (link.getReconcileAttempts() >= COMPANY_RECONCILE_MAX_ATTEMPTS) {
            link.setEnabled(false);
            link.setReconcilePending(false);
            link.setReconcileNextAttemptAt(null);
            link.setReconcileLastError(limit("company_reconcile_failed_final: " + readableException(failure), 512));
        } else {
            link.setReconcilePending(true);
            link.setReconcileNextAttemptAt(LocalDateTime.now().plus(companyReconcileBackoff(job.attempt())));
            link.setReconcileLastError(limit(error, 512));
        }
        accountCompanyRepository.save(link);
    }

    java.time.Duration companyReconcileBackoff(int attempt) {
        long multiplier = 1L << Math.min(10, Math.max(0, attempt - 1));
        java.time.Duration delay = java.time.Duration.ofSeconds(30L * multiplier);
        return delay.compareTo(COMPANY_RECONCILE_MAX_BACKOFF) > 0 ? COMPANY_RECONCILE_MAX_BACKOFF : delay;
    }

    /**
     * Fresh-transaction reconciliation. Discovery is scalar and non-locking;
     * every involved Order is then locked once in id order, followed by every
     * Account and Invoice. Exact topology is re-read before the first write.
     */
    void reconcileEnabledCompany(PreparedCompanyReconcile job) {
        if (job == null) {
            return;
        }
        Long accountId = job.accountId();
        Long companyId = job.companyId();
        CommonBillingAccount targetAccountSnapshot = accountRepository.findByIdWithRelations(accountId).orElse(null);
        if (!isEnabledCompanyLink(accountId, companyId, targetAccountSnapshot)) {
            return;
        }
        List<CommonInvoice> targetInvoiceSnapshots = currentInvoiceSnapshots(accountId);
        Set<Long> expectedTargetInvoiceIds = invoiceIds(targetInvoiceSnapshots);
        Map<Long, InvoiceOrderBinding> expectedTargetBindings = invoiceBindings(expectedTargetInvoiceIds);
        Map<Long, InvoiceOrderBinding> expectedMovableBindings = projectionBindings(invoiceOrderRepository.findMovableOpenBindingsForCompany(companyId, accountId, ATTACHABLE_INVOICE_STATUSES));
        Set<Long> expectedFrozenInvoiceIds = new TreeSet<>(invoiceRepository.findFrozenCompositionInvoiceIdsForCompanyReconcile(companyId, accountId, ATTACHABLE_INVOICE_STATUSES));
        Map<Long, InvoiceOrderBinding> expectedFrozenBindings = invoiceBindings(expectedFrozenInvoiceIds);
        Set<Long> expectedBackfillOrderIds = new TreeSet<>(orderRepository.findCommonBillingBackfillOrderIds(companyId, BACKFILL_STATUSES));
        Set<Long> invoiceIdsToLock = new TreeSet<>(expectedTargetInvoiceIds);
        expectedMovableBindings.values().stream().map(InvoiceOrderBinding::invoiceId).filter(Objects::nonNull).forEach(invoiceIdsToLock::add);
        invoiceIdsToLock.addAll(expectedFrozenInvoiceIds);
        Map<Long, CommonInvoice> invoiceSnapshots = loadInvoiceSnapshots(invoiceIdsToLock);
        Set<Long> accountIdsToLock = new TreeSet<>();
        accountIdsToLock.add(accountId);
        expectedMovableBindings.values().stream().map(InvoiceOrderBinding::accountId).filter(Objects::nonNull).forEach(accountIdsToLock::add);
        invoiceSnapshots.values().stream().filter(Objects::nonNull).map(CommonInvoice::getAccount).filter(Objects::nonNull).map(CommonBillingAccount::getId).filter(Objects::nonNull).forEach(accountIdsToLock::add);
        Map<Long, CommonBillingAccount> accountSnapshots = loadAccountSnapshots(accountIdsToLock);
        accountSnapshots.put(accountId, targetAccountSnapshot);
        Set<Long> orderIdsToLock = new TreeSet<>(expectedTargetBindings.keySet());
        orderIdsToLock.addAll(expectedMovableBindings.keySet());
        orderIdsToLock.addAll(expectedFrozenBindings.keySet());
        orderIdsToLock.addAll(expectedBackfillOrderIds);
        Map<Long, Order> lockedOrders = lockOrderAggregatesWithEntities(orderIdsToLock);
        Map<Long, List<PaymentLink>> lockedBackfillPaymentLinks = lockPaymentLinksForOrders(expectedBackfillOrderIds);
        Map<Long, CommonBillingAccount> lockedAccounts = lockAccountsInCanonicalOrder(accountSnapshots);
        Map<Long, CommonInvoice> lockedInvoices = lockInvoicesInCanonicalOrder(invoiceSnapshots);
        CommonBillingAccount lockedTargetAccount = lockedAccounts.get(accountId);
        if (!isEnabledCompanyLink(accountId, companyId, lockedTargetAccount)) {
            throw invoiceMembershipChanged("связь компании с целевым плательщиком изменилась");
        }
        ensureCompanyNotEnabledInAnotherAccount(accountId, companyId);
        CommonBillingAccountCompany reconcileLink = lockedCompanyReconcileLink(job);
        Set<Long> lockedFrozenInvoiceIds = lockedInvoices.values().stream().filter(this::hasFrozenCommonPaymentRoute).map(CommonInvoice::getId).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        Set<Long> currentFrozenInvoiceIds = new TreeSet<>(invoiceRepository.findFrozenCompositionInvoiceIdsForCompanyReconcile(companyId, accountId, ATTACHABLE_INVOICE_STATUSES));
        if (!lockedFrozenInvoiceIds.isEmpty() || !currentFrozenInvoiceIds.isEmpty()) {
            deferCompanyReconcileForFrozenRoute(reconcileLink, job, lockedFrozenInvoiceIds.isEmpty() ? currentFrozenInvoiceIds : lockedFrozenInvoiceIds);
            return;
        }
        if (!currentFrozenInvoiceIds.equals(expectedFrozenInvoiceIds)) {
            throw invoiceMembershipChanged("набор зафиксированных платежных маршрутов изменился");
        }
        List<CommonInvoice> currentTargetSnapshots = currentInvoiceSnapshots(accountId);
        Set<Long> currentTargetInvoiceIds = invoiceIds(currentTargetSnapshots);
        if (!currentTargetInvoiceIds.equals(expectedTargetInvoiceIds)) {
            throw invoiceMembershipChanged("набор открытых счетов целевого плательщика изменился");
        }
        if (!invoiceBindings(currentTargetInvoiceIds).equals(expectedTargetBindings)) {
            throw invoiceMembershipChanged("состав открытых счетов целевого плательщика изменился");
        }
        List<CommonInvoiceOrder> movableItems = invoiceOrderRepository.findMovableOpenItemsForCompany(companyId, accountId, ATTACHABLE_INVOICE_STATUSES);
        if (!itemBindings(movableItems).equals(expectedMovableBindings)) {
            throw invoiceMembershipChanged("состав переносимых заказов изменился");
        }
        Set<Long> currentBackfillOrderIds = new TreeSet<>(orderRepository.findCommonBillingBackfillOrderIds(companyId, BACKFILL_STATUSES));
        if (!currentBackfillOrderIds.equals(expectedBackfillOrderIds)) {
            throw invoiceMembershipChanged("состав непривязанных заказов компании изменился");
        }
        closeProvablyUnstartedStandaloneRoutesOrThrow(lockedBackfillPaymentLinks, null);
        if (expectedTargetInvoiceIds.isEmpty() && movableItems.isEmpty() && expectedBackfillOrderIds.isEmpty()) {
            clearCompanyReconcileState(reconcileLink);
            accountCompanyRepository.save(reconcileLink);
            return;
        }
        List<CommonInvoice> lockedTargetInvoices = expectedTargetInvoiceIds.stream().sorted().map(lockedInvoices::get).filter(Objects::nonNull).toList();
        CommonInvoice targetInvoice = lockedTargetInvoices.isEmpty() ? createInvoice(lockedTargetAccount) : normalizeAttachableInvoices(lockedTargetAccount, lockedTargetInvoices);
        Set<CommonInvoice> sourceInvoices = expectedMovableBindings.values().stream().map(InvoiceOrderBinding::invoiceId).distinct().map(lockedInvoices::get).filter(Objects::nonNull).collect(Collectors.toSet());
        LocalDateTime movedAt = LocalDateTime.now();
        for (CommonInvoiceOrder item : movableItems) {
            item.setInvoice(targetInvoice);
            item.setInvoiceLinkedAt(movedAt);
            item.setPublicationBlockerSince(null);
        }
        if (!movableItems.isEmpty()) {
            invoiceOrderRepository.saveAll(movableItems);
        }
        int ready = 0;
        for (Long orderId : expectedBackfillOrderIds) {
            Order lockedOrder = lockedOrders.get(orderId);
            if (lockedOrder == null || lockedOrder.getCompany() == null || !Objects.equals(companyId, lockedOrder.getCompany().getId()) || lockedOrder.isComplete() || !BACKFILL_STATUSES.contains(statusTitle(lockedOrder)) || invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(orderId).isPresent()) {
                throw invoiceMembershipChanged("заказ " + orderId + " перестал подходить для автопривязки");
            }
            CommonInvoiceOrder item = attachOrderWithoutInvoiceRefresh(targetInvoice, lockedOrder);
            if (markBackfilledOrderReadyIfPublished(item)) {
                ready++;
            }
        }
        consumeVerifiedManualRouteAfterAttach(targetInvoice, lockedBackfillPaymentLinks);
        List<CommonInvoiceOrder> targetItems = invoiceOrderRepository.findByInvoiceIdWithOrders(targetInvoice.getId());
        recalculateInvoice(targetInvoice, targetItems);
        promoteCollectingInvoiceToReadyIfPossible(targetInvoice, targetItems);
        publicationBlockerService.reconcileInvoice(targetInvoice.getId());
        for (CommonInvoice sourceInvoice : sourceInvoices) {
            List<CommonInvoiceOrder> remainingItems = invoiceOrderRepository.findByInvoiceIdWithOrders(sourceInvoice.getId());
            if (remainingItems.isEmpty()) {
                sourceInvoice.setStatus(CommonInvoiceStatus.DISABLED);
                sourceInvoice.setAmountKopecks(0);
                sourceInvoice.setPaidKopecks(0);
                sourceInvoice.setNextReminderAt(null);
                sourceInvoice.setLastError("merged_into: common_invoice_" + targetInvoice.getId());
                invoiceRepository.save(sourceInvoice);
                disableEmptySourceAccount(sourceInvoice.getAccount());
                continue;
            }
            recalculateInvoice(sourceInvoice, remainingItems);
            promoteCollectingInvoiceToReadyIfPossible(sourceInvoice, remainingItems);
            publicationBlockerService.reconcileInvoice(sourceInvoice.getId());
        }
        clearCompanyReconcileState(reconcileLink);
        accountCompanyRepository.save(reconcileLink);
        log.info("Reconciled company {} with common account {} invoice {}: moved={}, backfilled={}, ready={}, sources={}", companyId, accountId, targetInvoice.getId(), movableItems.size(), expectedBackfillOrderIds.size(), ready, sourceInvoices.stream().map(CommonInvoice::getId).toList());
    }

    boolean isEnabledCompanyLink(Long accountId, Long companyId, CommonBillingAccount account) {
        return invoiceMembershipWorkflow.isEnabledCompanyLink(accountId, companyId, account);
    }

    void markCompanyReconcilePending(CommonBillingAccountCompany link) {
        if (link == null) {
            return;
        }
        link.setReconcilePending(true);
        link.setReconcileAttempts(0);
        link.setReconcileNextAttemptAt(LocalDateTime.now());
        link.setReconcileLeaseToken(null);
        link.setReconcileLeaseUntil(null);
        link.setReconcileLastError(null);
    }

    void clearCompanyReconcileState(CommonBillingAccountCompany link) {
        if (link == null) {
            return;
        }
        link.setReconcilePending(false);
        link.setReconcileAttempts(0);
        link.setReconcileNextAttemptAt(null);
        link.setReconcileLeaseToken(null);
        link.setReconcileLeaseUntil(null);
        link.setReconcileLastError(null);
    }

    void deferCompanyReconcileForFrozenRoute(CommonBillingAccountCompany link, PreparedCompanyReconcile job, Collection<Long> frozenInvoiceIds) {
        if (link == null) {
            throw invoiceMembershipChanged("задача сверки компании исчезла");
        }
        link.setReconcilePending(true);
        link.setReconcileAttempts(Math.max(0, link.getReconcileAttempts() - 1));
        link.setReconcileNextAttemptAt(LocalDateTime.now().plus(companyReconcileBackoff(Math.max(1, job == null ? 1 : job.attempt()))));
        link.setReconcileLeaseToken(null);
        link.setReconcileLeaseUntil(null);
        link.setReconcileLastError(limit("company_reconcile_deferred_frozen_route: invoices=" + new TreeSet<>(frozenInvoiceIds == null ? List.of() : frozenInvoiceIds), 512));
        accountCompanyRepository.save(link);
    }

    CommonBillingAccountCompany lockedCompanyReconcileLink(PreparedCompanyReconcile job) {
        CommonBillingAccountCompany link = job == null ? null : accountCompanyRepository.findByIdForUpdate(job.linkId()).orElse(null);
        if (link == null || !link.isEnabled() || !link.isReconcilePending() || link.getAccount() == null || link.getCompany() == null || !Objects.equals(job.accountId(), link.getAccount().getId()) || !Objects.equals(job.companyId(), link.getCompany().getId()) || !normalize(job.leaseToken()).equals(normalize(link.getReconcileLeaseToken()))) {
            throw invoiceMembershipChanged("задача сверки компании изменилась или потеряла lease");
        }
        return link;
    }

    List<CommonInvoice> currentInvoiceSnapshots(Long accountId) {
        return invoiceMembershipWorkflow.currentInvoiceSnapshots(accountId);
    }

    Set<Long> invoiceIds(Collection<CommonInvoice> invoices) {
        return invoiceMembershipWorkflow.invoiceIds(invoices);
    }

    Map<Long, InvoiceOrderBinding> invoiceBindings(Collection<Long> invoiceIds) {
        return settlementService.invoiceBindings(invoiceIds);
    }

    Map<Long, InvoiceOrderBinding> projectionBindings(Collection<CommonInvoiceOrderRepository.OrderInvoiceBindingView> views) {
        return settlementService.projectionBindings(views);
    }

    Map<Long, InvoiceOrderBinding> itemBindings(Collection<CommonInvoiceOrder> items) {
        Map<Long, InvoiceOrderBinding> bindings = new HashMap<>();
        if (items == null) {
            return bindings;
        }
        for (CommonInvoiceOrder item : items) {
            Long orderId = item == null || item.getOrder() == null ? null : item.getOrder().getId();
            CommonInvoice invoice = item == null ? null : item.getInvoice();
            Long invoiceId = invoice == null ? null : invoice.getId();
            Long accountId = invoice == null || invoice.getAccount() == null ? null : invoice.getAccount().getId();
            InvoiceOrderBinding previous = orderId == null ? null : bindings.put(orderId, new InvoiceOrderBinding(invoiceId, accountId));
            if (orderId == null || invoiceId == null || accountId == null || previous != null) {
                throw invoiceMembershipChanged("обнаружена неоднозначная связь заказа и общего счета");
            }
        }
        return bindings;
    }

    Map<Long, CommonInvoice> loadInvoiceSnapshots(Collection<Long> invoiceIds) {
        return settlementService.loadInvoiceSnapshots(invoiceIds);
    }

    Map<Long, CommonBillingAccount> loadAccountSnapshots(Collection<Long> accountIds) {
        return settlementService.loadAccountSnapshots(accountIds);
    }

    Map<Long, CommonBillingAccount> lockAccountsInCanonicalOrder(Map<Long, CommonBillingAccount> snapshots) {
        return settlementService.lockAccountsInCanonicalOrder(snapshots);
    }

    Map<Long, CommonInvoice> lockInvoicesInCanonicalOrder(Map<Long, CommonInvoice> snapshots) {
        return settlementService.lockInvoicesInCanonicalOrder(snapshots);
    }

    ResponseStatusException invoiceMembershipChanged(String detail) {
        return settlementService.invoiceMembershipChanged(detail);
    }

    void disableEmptySourceAccount(CommonBillingAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        boolean hasEnabledCompanies = accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(account.getId()).stream().anyMatch(CommonBillingAccountCompany::isEnabled);
        if (hasEnabledCompanies) {
            return;
        }
        account.setEnabled(false);
        accountRepository.save(account);
    }

    CommonInvoiceOrder attachOrderWithoutInvoiceRefresh(CommonInvoice invoice, Order order) {
        return invoiceMembershipWorkflow.attachOrderWithoutInvoiceRefresh(invoice, order);
    }

    void consumeVerifiedManualRouteAfterAttach(CommonInvoice invoice, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        invoiceMembershipWorkflow.consumeVerifiedManualRouteAfterAttach(invoice, paymentLinksByOrder);
    }

    boolean markBackfilledOrderReadyIfPublished(CommonInvoiceOrder item) {
        Order order = item.getOrder();
        String status = statusTitle(order);
        if (!READY_ON_ATTACH_STATUSES.contains(status)) {
            return false;
        }
        Long payable = payableKopecksOrMarkAttention(item.getInvoice(), order);
        if (payable == null) {
            return false;
        }
        item.setReady(true);
        item.setAmountKopecks(payable);
        invoiceOrderRepository.save(item);
        return true;
    }

    CommonInvoice normalizeAttachableInvoices(CommonBillingAccount account, List<CommonInvoice> invoices) {
        return invoiceMembershipWorkflow.normalizeAttachableInvoices(account, invoices);
    }

    CommonInvoice createInvoice(CommonBillingAccount account) {
        return invoiceMembershipWorkflow.createInvoice(account);
    }

    void promoteCollectingInvoiceToReadyIfPossible(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        manualPaymentWorkflow.promoteCollectingInvoiceToReadyIfPossible(invoice, items);
    }

    void recalculateInvoice(CommonInvoice invoice) {
        settlementService.recalculateInvoice(invoice);
    }

    void recalculateInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.recalculateInvoice(invoice, items);
    }

    Long payableKopecksOrMarkAttention(CommonInvoice invoice, Order order) {
        return invoiceMembershipWorkflow.payableKopecksOrMarkAttention(invoice, order);
    }

    boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return manualPaymentWorkflow.hasFrozenCommonPaymentRoute(invoice);
    }

    String statusTitle(Order order) {
        return settlementService.statusTitle(order);
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }

    String limit(String value, int max) {
        return settlementService.limit(value, max);
    }

    String readableException(RuntimeException e) {
        return settlementService.readableException(e);
    }

    record PreparedCompanyReconcile(Long linkId, Long accountId, Long companyId, String leaseToken, int attempt) {
    }
}
