package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonBillingAccountWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonBillingCompanyReconciliationWorkflow.*;
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
import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceBoardQueryRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

/** Preserves board filtering and the legacy duplicate/amount normalization before constructing views. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceBoardWorkflow {

    private final CommonInvoiceMembershipWorkflow invoiceMembershipWorkflow;

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceDeliveryService invoiceDelivery;

    private final CommonInvoiceInitializationService invoiceInitialization;

    private final CommonInvoicePresenter invoicePresenter;

    public static final String STATUS_WAITING_COMMON_INVOICE = CommonInvoiceSettlementService.STATUS_WAITING_COMMON_INVOICE;

    private final CommonInvoiceSettlementService settlementService;

    static final String STATUS_NEEDS_ATTENTION = "Требует внимания";

    static final String STATUS_NOT_PAID = "Не оплачено";

    static final String STATUS_ARCHIVE = "Архив";

    static final String STATUS_BAN = "Бан";

    static final Set<CommonInvoiceStatus> BOARD_INVOICE_STATUSES = Set.of(CommonInvoiceStatus.COLLECTING, CommonInvoiceStatus.READY, CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID, CommonInvoiceStatus.NEEDS_ATTENTION, CommonInvoiceStatus.UNPAID);

    private final CommonInvoiceBoardQueryRepository invoiceBoardQueryRepository;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final BadReviewTaskService badReviewTaskService;

    private final CommonInvoicePublicationBlockerService publicationBlockerService;

    @Transactional
    public List<OrderDTOList> managerBoardCards(String boardStatus, String keyword, Long companyId, Set<Long> visibleManagerIds, String sortDirection) {
        List<CommonInvoice> invoices = normalizedBoardInvoices();
        if (invoices.isEmpty()) {
            return List.of();
        }
        List<Long> invoiceIds = invoices.stream().map(CommonInvoice::getId).toList();
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoice = invoiceOrderRepository.findByInvoiceIdsWithOrders(invoiceIds).stream().collect(Collectors.groupingBy(item -> item.getInvoice().getId()));
        String normalizedStatus = normalize(boardStatus);
        String normalizedKeyword = normalize(keyword).toLowerCase(Locale.ROOT);
        boolean ascending = "asc".equalsIgnoreCase(sortDirection);
        return invoices.stream().filter(invoice -> visibleToManager(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), visibleManagerIds)).filter(invoice -> matchesBoardStatus(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), normalizedStatus)).filter(invoice -> matchesBoardCompany(itemsByInvoice.getOrDefault(invoice.getId(), List.of()), companyId)).filter(invoice -> matchesBoardKeyword(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), normalizedKeyword)).sorted(boardInvoiceComparator(ascending)).map(invoice -> {
            List<CommonInvoiceOrder> items = itemsByInvoice.getOrDefault(invoice.getId(), List.of());
            refreshInvoiceAmounts(invoice, items);
            return toManagerBoardCard(invoice, items);
        }).toList();
    }

    @Transactional(readOnly = true)
    public Set<Long> linkedBoardOrderIds(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(invoiceOrderRepository.findLinkedOrderIds(orderIds, BOARD_INVOICE_STATUSES));
    }

    @Transactional
    public int countLinkedBoardOrdersMatching(String orderStatus, String keyword, Long companyId, Set<Long> visibleManagerIds) {
        List<CommonInvoice> invoices = normalizedBoardInvoices();
        if (invoices.isEmpty()) {
            return 0;
        }
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoice = invoiceOrderRepository.findByInvoiceIdsWithOrders(invoices.stream().map(CommonInvoice::getId).toList()).stream().collect(Collectors.groupingBy(item -> item.getInvoice().getId()));
        String normalizedStatus = normalize(orderStatus);
        String normalizedKeyword = normalize(keyword).toLowerCase(Locale.ROOT);
        return (int) invoices.stream().filter(invoice -> visibleToManager(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), visibleManagerIds)).flatMap(invoice -> itemsByInvoice.getOrDefault(invoice.getId(), List.of()).stream()).filter(item -> itemVisibleInOrderMetrics(item, visibleManagerIds)).filter(item -> matchesLinkedOrderStatus(item, normalizedStatus)).filter(item -> matchesLinkedOrderCompany(item, companyId)).filter(item -> matchesLinkedOrderKeyword(item, normalizedKeyword)).map(CommonInvoiceOrder::getOrder).filter(order -> order != null && order.getId() != null).map(Order::getId).distinct().count();
    }

    @Transactional
    public Map<String, Integer> countManagerBoardCards(Set<Long> visibleManagerIds) {
        List<CommonInvoice> invoices = normalizedBoardInvoices();
        if (invoices.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoice = invoiceOrderRepository.findByInvoiceIdsWithOrders(invoices.stream().map(CommonInvoice::getId).toList()).stream().collect(Collectors.groupingBy(item -> item.getInvoice().getId()));
        Map<String, Integer> counts = new HashMap<>();
        invoices.stream().filter(invoice -> visibleToManager(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), visibleManagerIds)).map(invoice -> boardStatus(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()))).forEach(status -> counts.merge(status, 1, Integer::sum));
        return counts;
    }

    @Transactional
    public Map<String, Integer> countLinkedManagerBoardOrders(Set<Long> visibleManagerIds) {
        List<CommonInvoice> invoices = normalizedBoardInvoices();
        if (invoices.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoice = invoiceOrderRepository.findByInvoiceIdsWithOrders(invoices.stream().map(CommonInvoice::getId).toList()).stream().collect(Collectors.groupingBy(item -> item.getInvoice().getId()));
        Map<String, Integer> counts = new HashMap<>();
        invoices.stream().filter(invoice -> visibleToManager(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), visibleManagerIds)).flatMap(invoice -> itemsByInvoice.getOrDefault(invoice.getId(), List.of()).stream()).filter(item -> itemVisibleInOrderMetrics(item, visibleManagerIds)).map(item -> statusTitle(item.getOrder())).filter(status -> !status.isBlank()).forEach(status -> counts.merge(status, 1, Integer::sum));
        return counts;
    }

    /**
     * Loads only the requested board page after SQL filtering and counting.
     */
    @Transactional
    public ManagerBoardPage managerBoardPage(String boardStatus, String keyword, Long companyId, Set<Long> visibleManagerIds, String sortDirection, int pageNumber, int pageSize) {
        int safePageNumber = Math.max(0, pageNumber);
        int safePageSize = Math.max(1, pageSize);
        String normalizedStatus = normalize(boardStatus);
        String normalizedKeyword = normalize(keyword).toLowerCase(Locale.ROOT);
        normalizeBoardInvoiceDuplicates();
        CommonInvoiceBoardQueryRepository.PageSelection selection = invoiceBoardQueryRepository.findPage(normalizedStatus, normalizedKeyword, companyId, visibleManagerIds, "asc".equalsIgnoreCase(sortDirection), safePageNumber, safePageSize, LocalDateTime.now().minusHours(CommonInvoicePublicationBlockerService.ATTENTION_AFTER_HOURS));
        if (selection.invoiceIds().isEmpty()) {
            return new ManagerBoardPage(List.of(), selection.totalCards(), selection.linkedOrderCount());
        }
        Map<Long, CommonInvoice> invoicesById = invoiceRepository.findBoardInvoicesByIds(selection.invoiceIds()).stream().filter(invoice -> invoice != null && invoice.getId() != null).collect(Collectors.toMap(CommonInvoice::getId, Function.identity()));
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoiceId = invoiceOrderRepository.findByInvoiceIdsWithOrders(selection.invoiceIds()).stream().filter(item -> item != null && item.getInvoice() != null && item.getInvoice().getId() != null).collect(Collectors.groupingBy(item -> item.getInvoice().getId()));
        List<BoardInvoiceView> selectedCards = selection.invoiceIds().stream().map(invoicesById::get).filter(Objects::nonNull).map(invoice -> new BoardInvoiceView(invoice, itemsByInvoiceId.getOrDefault(invoice.getId(), List.of()))).toList();
        List<OrderDTOList> cards = selectedCards.stream().map(view -> {
            refreshInvoiceAmounts(view.invoice(), view.items());
            return toManagerBoardCard(view.invoice(), view.items());
        }).toList();
        return new ManagerBoardPage(cards, selection.totalCards(), selection.linkedOrderCount());
    }

    /**
     * Aggregates both common cards and linked orders in SQL.
     */
    @Transactional
    public ManagerBoardMetrics managerBoardMetrics(Set<Long> visibleManagerIds) {
        normalizeBoardInvoiceDuplicates();
        CommonInvoiceBoardQueryRepository.BoardMetrics metrics = invoiceBoardQueryRepository.metrics(visibleManagerIds, LocalDateTime.now().minusHours(CommonInvoicePublicationBlockerService.ATTENTION_AFTER_HOURS));
        return new ManagerBoardMetrics(metrics.cardCounts(), metrics.linkedOrderCounts());
    }

    Map<Long, Order> lockOrderAggregatesWithEntities(Collection<Long> orderIds) {
        return settlementService.lockOrderAggregatesWithEntities(orderIds);
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

    List<CommonInvoice> normalizedBoardInvoices() {
        List<CommonInvoice> invoices = invoiceRepository.findBoardInvoices(BOARD_INVOICE_STATUSES);
        if (!hasDuplicateAttachableInvoices(invoices)) {
            return invoices;
        }
        Set<Long> duplicateAccountIds = invoices.stream().filter(invoice -> invoice.getAccount() != null && invoice.getAccount().getId() != null).filter(invoice -> ATTACHABLE_INVOICE_STATUSES.contains(invoice.getStatus())).filter(this::isStandardInvoice).collect(Collectors.groupingBy(invoice -> invoice.getAccount().getId(), Collectors.counting())).entrySet().stream().filter(entry -> entry.getValue() > 1).map(Map.Entry::getKey).collect(Collectors.toCollection(TreeSet::new));
        boolean normalized = normalizeDuplicateInvoiceAccounts(duplicateAccountIds);
        return normalized ? invoiceRepository.findBoardInvoices(BOARD_INVOICE_STATUSES) : invoices;
    }

    void normalizeBoardInvoiceDuplicates() {
        normalizeDuplicateInvoiceAccounts(new TreeSet<>(invoiceRepository.findAccountIdsWithDuplicateCurrentInvoices(ATTACHABLE_INVOICE_STATUSES)));
    }

    boolean normalizeDuplicateInvoiceAccounts(Collection<Long> duplicateAccountIds) {
        Set<Long> accountIds = duplicateAccountIds == null ? Set.of() : duplicateAccountIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        if (accountIds.isEmpty()) {
            return false;
        }
        Map<Long, CommonBillingAccount> accountSnapshots = loadAccountSnapshots(accountIds);
        Map<Long, Set<Long>> expectedInvoiceIdsByAccount = new HashMap<>();
        Set<Long> allInvoiceIds = new TreeSet<>();
        for (Long accountId : accountIds) {
            Set<Long> ids = invoiceIds(currentInvoiceSnapshots(accountId));
            if (ids.size() > 1) {
                expectedInvoiceIdsByAccount.put(accountId, ids);
                allInvoiceIds.addAll(ids);
            }
        }
        if (expectedInvoiceIdsByAccount.isEmpty()) {
            return false;
        }
        Map<Long, InvoiceOrderBinding> expectedBindings = invoiceBindings(allInvoiceIds);
        Map<Long, CommonInvoice> invoiceSnapshots = loadInvoiceSnapshots(allInvoiceIds);
        lockOrderAggregatesWithEntities(expectedBindings.keySet());
        Map<Long, CommonBillingAccount> lockedAccounts = lockAccountsInCanonicalOrder(accountSnapshots);
        Map<Long, CommonInvoice> lockedInvoices = lockInvoicesInCanonicalOrder(invoiceSnapshots);
        if (lockedInvoices.values().stream().anyMatch(this::hasFrozenCommonPaymentRoute)) {
            // A route can be selected after discovery but before the invoice
            // locks. Duplicate normalization is best-effort: skip the entire
            // batch before its first item/invoice write.
            return false;
        }
        Set<Long> currentAllInvoiceIds = new TreeSet<>();
        for (Map.Entry<Long, Set<Long>> entry : expectedInvoiceIdsByAccount.entrySet()) {
            Set<Long> currentIds = invoiceIds(currentInvoiceSnapshots(entry.getKey()));
            if (!currentIds.equals(entry.getValue())) {
                throw invoiceMembershipChanged("набор дублей общих счетов изменился");
            }
            currentAllInvoiceIds.addAll(currentIds);
        }
        if (!invoiceBindings(currentAllInvoiceIds).equals(expectedBindings)) {
            throw invoiceMembershipChanged("состав заказов в дублях общих счетов изменился");
        }
        for (Long accountId : new TreeSet<>(expectedInvoiceIdsByAccount.keySet())) {
            CommonBillingAccount account = lockedAccounts.get(accountId);
            List<CommonInvoice> invoices = expectedInvoiceIdsByAccount.get(accountId).stream().sorted().map(lockedInvoices::get).filter(Objects::nonNull).toList();
            if (account == null || invoices.size() < 2) {
                throw invoiceMembershipChanged("дубли общих счетов исчезли во время нормализации");
            }
            normalizeAttachableInvoices(account, invoices);
        }
        return true;
    }

    boolean hasDuplicateAttachableInvoices(List<CommonInvoice> invoices) {
        Map<Long, Long> countsByAccount = invoices.stream().filter(invoice -> invoice.getAccount() != null && invoice.getAccount().getId() != null).filter(invoice -> ATTACHABLE_INVOICE_STATUSES.contains(invoice.getStatus())).filter(this::isStandardInvoice).filter(invoice -> !hasFrozenCommonPaymentRoute(invoice)).collect(Collectors.groupingBy(invoice -> invoice.getAccount().getId(), Collectors.counting()));
        return countsByAccount.values().stream().anyMatch(count -> count > 1);
    }

    CommonInvoice normalizeAttachableInvoices(CommonBillingAccount account, List<CommonInvoice> invoices) {
        return invoiceMembershipWorkflow.normalizeAttachableInvoices(account, invoices);
    }

    boolean isStandardInvoice(CommonInvoice invoice) {
        return invoice != null && "STANDARD".equals(invoice.getInvoicePurpose());
    }

    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.refreshInvoiceAmounts(invoice, items);
    }

    CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoicePresenter.toInvoiceSummary(invoice, items);
    }

    CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items, String tbankTerminalLabel) {
        return invoicePresenter.toInvoiceSummary(invoice, items, tbankTerminalLabel);
    }

    OrderDTOList toManagerBoardCard(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        CommonInvoiceSummaryResponse summary = toInvoiceSummary(invoice, items);
        BadReviewTaskSummary badReviewSummary = aggregateBadReviewSummary(items);
        Company company = chatCompany(invoice, items);
        Manager invoiceManager = manager(invoice, items);
        LocalDate changed = invoice.getUpdatedAt() == null ? LocalDate.now() : invoice.getUpdatedAt().toLocalDate();
        return OrderDTOList.builder().id(-invoice.getId()).companyId(company == null ? firstCompanyId(items) : company.getId()).companyTitle(invoice.getAccount().getName()).companyComments(company == null ? "" : normalize(company.getCommentsCompany())).filialTitle("Общий счет: " + items.size() + " заказов").filialUrl(summary.publicUrl()).filialCity("").status(boardStatus(invoice, items)).sum(summary.remaining()).totalSumWithBadReviews(summary.remaining()).badReviewTasksSum(badReviewSummary.doneSum()).badReviewTasksTotal(badReviewSummary.total()).badReviewTasksPending(badReviewSummary.pending()).badReviewTasksDone(badReviewSummary.done()).badReviewTasksCanceled(badReviewSummary.canceled()).companyUrlChat(company == null ? "" : normalize(company.getUrlChat())).companyTelephone(company == null ? "" : normalize(company.getTelephone())).managerPayText(invoiceManager == null ? "" : normalize(invoiceManager.getPayText())).amount(summary.totalOrders()).counter(summary.readyOrders()).waitingForClient(false).firstOrderForCompany(false).workerUserFio("Общий счет").categoryTitle("Общий счет").subCategoryTitle(companyCountLabel(items)).created(invoice.getCreatedAt() == null ? null : invoice.getCreatedAt().toLocalDate()).changed(changed).payDay(null).dayToChangeStatusAgo(Math.max(0, ChronoUnit.DAYS.between(changed, LocalDate.now()))).orderComments(commonInvoiceNote(summary)).commonInvoice(true).commonInvoiceId(invoice.getId()).commonBillingAccountId(invoice.getAccount().getId()).commonInvoiceStatus(invoice.getStatus().name()).commonInvoicePublicUrl(summary.publicUrl()).commonInvoiceTotalOrders(summary.totalOrders()).commonInvoiceReadyOrders(summary.readyOrders()).commonInvoicePaidOrders(summary.paidOrders()).commonInvoiceAmount(summary.amount()).commonInvoicePaid(summary.paid()).commonInvoiceRemaining(summary.remaining()).commonInvoiceSentAt(summary.sentAt()).commonInvoiceLastReminderAt(summary.lastReminderAt()).commonInvoiceNextReminderAt(summary.nextReminderAt()).commonInvoiceLastError(summary.lastError()).build();
    }

    BadReviewTaskSummary aggregateBadReviewSummary(List<CommonInvoiceOrder> items) {
        List<Long> orderIds = items == null ? List.of() : items.stream().map(CommonInvoiceOrder::getOrder).filter(order -> order != null && order.getId() != null).map(Order::getId).toList();
        if (orderIds.isEmpty()) {
            return BadReviewTaskSummary.empty();
        }
        Map<Long, BadReviewTaskSummary> summaries = badReviewTaskService.getSummaryByOrderIds(orderIds);
        if (summaries == null || summaries.isEmpty()) {
            return BadReviewTaskSummary.empty();
        }
        int total = 0;
        int pending = 0;
        int done = 0;
        int canceled = 0;
        BigDecimal doneSum = BigDecimal.ZERO;
        BigDecimal pendingSum = BigDecimal.ZERO;
        for (BadReviewTaskSummary summary : summaries.values()) {
            if (summary == null) {
                continue;
            }
            total += summary.total();
            pending += summary.pending();
            done += summary.done();
            canceled += summary.canceled();
            doneSum = doneSum.add(summary.doneSum());
            pendingSum = pendingSum.add(summary.pendingSum());
        }
        return new BadReviewTaskSummary(total, pending, done, canceled, doneSum, pendingSum);
    }

    Comparator<CommonInvoice> boardInvoiceComparator(boolean ascending) {
        Comparator<CommonInvoice> comparator = Comparator.comparing((CommonInvoice invoice) -> Optional.ofNullable(invoice.getUpdatedAt()).orElse(LocalDateTime.MIN)).thenComparing(CommonInvoice::getId);
        return ascending ? comparator.reversed() : comparator;
    }

    boolean matchesBoardStatus(CommonInvoice invoice, List<CommonInvoiceOrder> items, String boardStatus) {
        String invoiceBoardStatus = boardStatus(invoice, items);
        return boardStatus.isBlank() || "Все".equals(boardStatus) || invoiceBoardStatus.equals(boardStatus);
    }

    boolean matchesBoardCompany(List<CommonInvoiceOrder> items, Long companyId) {
        if (companyId == null) {
            return true;
        }
        return items.stream().map(CommonInvoiceOrder::getOrder).map(Order::getCompany).filter(company -> company != null && company.getId() != null).anyMatch(company -> companyId.equals(company.getId()));
    }

    boolean matchesLinkedOrderStatus(CommonInvoiceOrder item, String orderStatus) {
        return orderStatus.isBlank() || "Все".equals(orderStatus) || orderStatus.equals(statusTitle(item.getOrder()));
    }

    boolean matchesLinkedOrderCompany(CommonInvoiceOrder item, Long companyId) {
        if (companyId == null) {
            return true;
        }
        Order order = item.getOrder();
        Company company = order == null ? null : order.getCompany();
        return company != null && companyId.equals(company.getId());
    }

    boolean matchesLinkedOrderKeyword(CommonInvoiceOrder item, String keyword) {
        if (keyword.isBlank()) {
            return true;
        }
        Order order = item.getOrder();
        Company company = order == null ? null : order.getCompany();
        return containsKeyword(order == null ? "" : String.valueOf(order.getId()), keyword) || containsKeyword(company == null ? "" : company.getTitle(), keyword) || containsKeyword(order == null || order.getFilial() == null ? "" : order.getFilial().getTitle(), keyword);
    }

    boolean matchesBoardKeyword(CommonInvoice invoice, List<CommonInvoiceOrder> items, String keyword) {
        if (keyword.isBlank()) {
            return true;
        }
        if (containsKeyword(invoice.getAccount().getName(), keyword) || containsKeyword(invoice.getTitle(), keyword) || containsKeyword(String.valueOf(invoice.getId()), keyword)) {
            return true;
        }
        return items.stream().anyMatch(item -> {
            Order order = item.getOrder();
            Company company = order == null ? null : order.getCompany();
            return containsKeyword(order == null ? "" : String.valueOf(order.getId()), keyword) || containsKeyword(company == null ? "" : company.getTitle(), keyword) || containsKeyword(order == null || order.getFilial() == null ? "" : order.getFilial().getTitle(), keyword);
        });
    }

    boolean containsKeyword(String value, String keyword) {
        return normalize(value).toLowerCase(Locale.ROOT).contains(keyword);
    }

    boolean visibleToManager(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> visibleManagerIds) {
        return invoiceDelivery.visibleToManager(invoice, items, visibleManagerIds);
    }

    boolean itemVisibleInOrderMetrics(CommonInvoiceOrder item, Set<Long> visibleManagerIds) {
        if (item == null || !item.isActiveMembership()) {
            return false;
        }
        if (visibleManagerIds == null) {
            return true;
        }
        Manager manager = item.getOrder() == null ? null : item.getOrder().getManager();
        return manager != null && manager.getId() != null && visibleManagerIds.contains(manager.getId());
    }

    CommonInvoiceStatus effectiveInvoiceStatus(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        CommonInvoiceStatus status = invoice.getStatus();
        if (status != CommonInvoiceStatus.COLLECTING && status != CommonInvoiceStatus.READY) {
            return status;
        }
        return allOrdersReady(items) ? CommonInvoiceStatus.READY : CommonInvoiceStatus.COLLECTING;
    }

    boolean allOrdersReady(List<CommonInvoiceOrder> items) {
        return settlementService.allOrdersReady(items);
    }

    String boardStatus(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice != null && invoice.getStatus() == CommonInvoiceStatus.COLLECTING && publicationBlockerService.hasOverdueBlockers(items, LocalDateTime.now())) {
            return STATUS_NEEDS_ATTENTION;
        }
        return switch(effectiveInvoiceStatus(invoice, items)) {
            case COLLECTING ->
                STATUS_WAITING_COMMON_INVOICE;
            case READY ->
                STATUS_PUBLIC;
            case INVOICED ->
                STATUS_TO_PAY;
            case REMINDER, PARTIALLY_PAID ->
                STATUS_REMINDER;
            case NEEDS_ATTENTION ->
                STATUS_NEEDS_ATTENTION;
            case UNPAID ->
                STATUS_NOT_PAID;
            case BAN ->
                STATUS_BAN;
            case ARCHIVED ->
                STATUS_ARCHIVE;
            case PAID ->
                "Оплачено";
            case DISABLED ->
                "Архив";
        };
    }

    Long firstCompanyId(List<CommonInvoiceOrder> items) {
        return items.stream().map(CommonInvoiceOrder::getOrder).map(Order::getCompany).filter(company -> company != null && company.getId() != null).map(Company::getId).findFirst().orElse(null);
    }

    String companyCountLabel(List<CommonInvoiceOrder> items) {
        long count = items.stream().map(CommonInvoiceOrder::getOrder).map(Order::getCompany).filter(company -> company != null && company.getId() != null).map(Company::getId).distinct().count();
        return count + " компаний";
    }

    String commonInvoiceNote(CommonInvoiceSummaryResponse summary) {
        return "Готово " + summary.readyOrders() + "/" + summary.totalOrders() + ", оплачено " + summary.paidOrders() + "/" + summary.totalOrders();
    }

    boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return manualPaymentWorkflow.hasFrozenCommonPaymentRoute(invoice);
    }

    Company chatCompany(CommonInvoice invoice) {
        return invoiceInitialization.chatCompany(invoice);
    }

    Company chatCompany(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceInitialization.chatCompany(invoice, items);
    }

    Manager manager(CommonInvoice invoice) {
        return invoiceInitialization.manager(invoice);
    }

    Manager manager(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceInitialization.manager(invoice, items);
    }

    String statusTitle(Order order) {
        return settlementService.statusTitle(order);
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }

    public record ManagerBoardPage(List<OrderDTOList> cards, long totalCards, int linkedOrderCount) {

        public ManagerBoardPage {
            cards = cards == null ? List.of() : List.copyOf(cards);
        }
    }

    public record ManagerBoardMetrics(Map<String, Integer> cardCounts, Map<String, Integer> linkedOrderCounts) {

        public ManagerBoardMetrics {
            cardCounts = cardCounts == null ? Map.of() : Map.copyOf(cardCounts);
            linkedOrderCounts = linkedOrderCounts == null ? Map.of() : Map.copyOf(linkedOrderCounts);
        }
    }

    record BoardInvoiceView(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
    }
}
