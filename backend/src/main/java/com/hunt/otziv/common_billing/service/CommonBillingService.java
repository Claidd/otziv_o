package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountRequest;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountResponse;
import com.hunt.otziv.common_billing.dto.CommonBillingCompanyResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceOrderResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.dto.PublicCommonInvoiceResponse;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountCompanyRepository;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.mapper.OrderDtoMapper;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.NextOrderFailureNotifier;
import com.hunt.otziv.p_products.next_order.NextOrderRequestService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.p_products.status.OrderStatusTransitionService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankInitCommand;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.TbankRuntimeMode;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.payments.service.TbankTokenSigner;
import com.hunt.otziv.payments.service.ManualPaymentAutoConfirmationService;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryGateService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonBillingService {

    public static final String STATUS_WAITING_COMMON_INVOICE = "Ожидает общего счета";
    private static final String STATUS_NEEDS_ATTENTION = "Требует внимания";
    private static final String STATUS_NOT_PAID = "Не оплачено";
    private static final String STATUS_PUBLIC = "Опубликовано";
    private static final String STATUS_TO_CHECK = "В проверку";
    private static final String STATUS_IN_CHECK = "На проверке";
    private static final String STATUS_TO_PUBLISH = "Публикация";
    private static final String STATUS_TO_PAY = "Выставлен счет";
    private static final String STATUS_REMINDER = "Напоминание";
    private static final String STATUS_BAN = "Бан";
    private static final Set<String> ACTIVE_WORK_STATUSES = Set.of(
            "Новый",
            "Нагул",
            "В проверку",
            "Коррекция",
            "На проверке",
            "Публикация"
    );
    private static final Set<String> BACKFILL_STATUSES = Set.of(
            "Новый",
            "Нагул",
            "В проверку",
            "Коррекция",
            "На проверке",
            "Публикация",
            STATUS_PUBLIC,
            STATUS_TO_PAY,
            STATUS_REMINDER,
            STATUS_WAITING_COMMON_INVOICE
    );
    private static final Set<String> REVIEW_APPROVAL_STATUSES = Set.of(
            STATUS_TO_CHECK,
            STATUS_IN_CHECK
    );
    private static final Set<String> READY_ON_ATTACH_STATUSES = Set.of(
            STATUS_PUBLIC,
            STATUS_TO_PAY,
            STATUS_REMINDER,
            STATUS_WAITING_COMMON_INVOICE
    );
    private static final Set<CommonInvoiceStatus> CURRENT_INVOICE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID,
            CommonInvoiceStatus.NEEDS_ATTENTION
    );
    private static final Set<CommonInvoiceStatus> MUTABLE_INVOICE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );
    private static final Set<CommonInvoiceStatus> ATTACHABLE_INVOICE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY
    );
    private static final Set<CommonInvoiceStatus> BOARD_INVOICE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID,
            CommonInvoiceStatus.NEEDS_ATTENTION,
            CommonInvoiceStatus.UNPAID,
            CommonInvoiceStatus.BAN
    );
    private static final Set<CommonInvoiceStatus> REMINDER_STATUSES = Set.of(
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );
    private static final Set<CommonInvoiceStatus> PUBLIC_PAYABLE_STATUSES = Set.of(
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );
    private static final Set<CommonInvoiceStatus> SEND_INVOICE_STATUSES = Set.of(
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );
    private static final Set<CommonInvoiceStatus> MARK_PAID_STATUSES = Set.of(
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID,
            CommonInvoiceStatus.UNPAID
    );
    private static final Set<CommonInvoiceStatus> MARK_UNPAID_STATUSES = Set.of(
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );
    private static final int REMINDER_INTERVAL_DAYS = 2;
    private static final String PAYMENT_REF_CONFIRMED = "CONFIRMED";
    private static final String PAYMENT_REF_APPLYING = "APPLYING";
    private static final String PAYMENT_REF_APPLIED = "APPLIED";
    private static final String PAYMENT_REF_ARCHIVED = "ARCHIVED";
    private static final String PAYMENT_REF_CANCEL_PENDING = "CANCEL_PENDING";
    private static final String PAYMENT_REF_CANCELING = "CANCELING";
    private static final String PAYMENT_REF_CANCELED = "CANCELED";
    private static final String PAYMENT_REF_CANCEL_FAILED = "CANCEL_FAILED";
    private static final String PAYMENT_REF_CANCEL_FAILED_FINAL = "CANCEL_FAILED_FINAL";
    private static final String PAYMENT_REF_INIT_CONFLICT = "INIT_CONFLICT";
    private static final Set<String> PAYMENT_REF_REFUNDED_STATUSES = Set.of(
            "REFUNDED",
            "PARTIAL_REFUNDED",
            "REVERSED",
            "PARTIAL_REVERSED",
            "CANCELED"
    );
    private static final int PAYMENT_REF_CANCEL_MAX_ATTEMPTS = 144;
    private static final int TRANSACTION_LOCK_RETRY_ATTEMPTS = 3;
    private static final long TRANSACTION_LOCK_RETRY_DELAY_MS = 300L;
    private static final java.time.Duration PAYMENT_REF_CANCEL_RETRY_DELAY = java.time.Duration.ofMinutes(10);
    private static final java.time.Duration PAYMENT_REF_CANCELING_TIMEOUT = java.time.Duration.ofMinutes(30);
    private static final String MESSAGE_SEND_IN_PROGRESS = "message_send_in_progress";
    private static final String PAYMENT_INIT_IN_PROGRESS = "payment_init_in_progress";
    private static final String PAYMENT_INIT_STALE = "payment_init_stale";
    private static final String PAYMENT_CANCEL_FAILED_FINAL = "payment_cancel_failed_final";
    private static final String MESSAGE_SEND_STALE = "message_send_stale";
    private static final Set<String> RESOLVABLE_TECHNICAL_TAIL_ERROR_PREFIXES = Set.of(
            "disabled:",
            "empty:",
            "merged_into:",
            "manual_fix:"
    );
    private static final java.time.Duration OPERATION_IN_PROGRESS_TIMEOUT = java.time.Duration.ofMinutes(30);
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    private final PlatformTransactionManager transactionManager;
    private final CommonBillingAccountRepository accountRepository;
    private final CommonBillingAccountCompanyRepository accountCompanyRepository;
    private final CommonInvoiceRepository invoiceRepository;
    private final CommonInvoiceOrderRepository invoiceOrderRepository;
    private final CommonInvoicePaymentRefRepository paymentRefRepository;
    private final CompanyRepository companyRepository;
    private final ManagerRepository managerRepository;
    private final OrderRepository orderRepository;
    private final OrderDtoMapper orderDtoMapper;
    private final OrderStatusService orderStatusService;
    @Autowired
    @Lazy
    private OrderTransactionService orderTransactionService;
    @Autowired
    @Lazy
    private OrderStatusTransitionService orderStatusTransitionService;
    private final NextOrderFailureNotifier nextOrderFailureNotifier;
    @Autowired
    @Lazy
    private NextOrderRequestService nextOrderRequestService;
    private final BadReviewTaskService badReviewTaskService;
    private final ManagerPermissionService managerPermissionService;
    private final UserService userService;
    private final ClientChatMessageSender messageSender;
    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    private final ManualPaymentAutoConfirmationService manualPaymentAutoConfirmationService;
    private final AppSettingService appSettingService;
    private final TbankRuntimeSettingsService runtimeSettingsService;
    private final PaymentProfileService paymentProfileService;
    private final TbankPaymentProperties properties;
    private final TbankClient tbankClient;
    private final TbankTokenSigner tokenSigner;
    private final ReviewRecoveryGateService recoveryGateService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public List<CommonBillingAccountResponse> accounts() {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        List<CommonBillingAccount> accounts = accountRepository.findAllForAdmin();
        List<Long> ids = accounts.stream().map(CommonBillingAccount::getId).toList();
        Map<Long, List<CommonBillingAccountCompany>> companies = accountCompanyRepository.findByAccountIds(ids)
                .stream()
                .collect(Collectors.groupingBy(link -> link.getAccount().getId()));
        return accounts.stream()
                .filter(account -> accountVisibleToManager(account, companies.getOrDefault(account.getId(), List.of()), visibleManagerIds))
                .map(account -> toAccountResponse(account, companies.getOrDefault(account.getId(), List.of())))
                .toList();
    }

    @Transactional
    public List<CommonBillingAccountResponse> accountsForCompany(Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
        ensureCompanyVisibleForCurrentUser(company);

        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        List<CommonBillingAccount> accounts = accountCompanyRepository.findLinksForCompany(companyId)
                .stream()
                .map(CommonBillingAccountCompany::getAccount)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> ids = accounts.stream().map(CommonBillingAccount::getId).toList();
        Map<Long, List<CommonBillingAccountCompany>> companies = ids.isEmpty()
                ? Map.of()
                : accountCompanyRepository.findByAccountIds(ids)
                        .stream()
                        .collect(Collectors.groupingBy(link -> link.getAccount().getId()));
        return accounts.stream()
                .filter(account -> accountVisibleToManager(account, companies.getOrDefault(account.getId(), List.of()), visibleManagerIds))
                .map(account -> toAccountResponse(account, companies.getOrDefault(account.getId(), List.of())))
                .toList();
    }

    @Transactional
    public List<OrderDTOList> managerBoardCards(
            String boardStatus,
            String keyword,
            Long companyId,
            Set<Long> visibleManagerIds,
            String sortDirection
    ) {
        List<CommonInvoice> invoices = normalizedBoardInvoices();
        if (invoices.isEmpty()) {
            return List.of();
        }

        List<Long> invoiceIds = invoices.stream().map(CommonInvoice::getId).toList();
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoice = invoiceOrderRepository.findByInvoiceIdsWithOrders(invoiceIds)
                .stream()
                .collect(Collectors.groupingBy(item -> item.getInvoice().getId()));
        String normalizedStatus = normalize(boardStatus);
        String normalizedKeyword = normalize(keyword).toLowerCase(Locale.ROOT);
        boolean ascending = "asc".equalsIgnoreCase(sortDirection);

        return invoices.stream()
                .filter(invoice -> visibleToManager(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), visibleManagerIds))
                .filter(invoice -> matchesBoardStatus(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), normalizedStatus))
                .filter(invoice -> matchesBoardCompany(itemsByInvoice.getOrDefault(invoice.getId(), List.of()), companyId))
                .filter(invoice -> matchesBoardKeyword(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), normalizedKeyword))
                .sorted(boardInvoiceComparator(ascending))
                .map(invoice -> {
                    List<CommonInvoiceOrder> items = itemsByInvoice.getOrDefault(invoice.getId(), List.of());
                    refreshInvoiceAmounts(invoice, items);
                    return toManagerBoardCard(invoice, items);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Set<Long> linkedBoardOrderIds(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(invoiceOrderRepository.findLinkedOrderIds(orderIds, BOARD_INVOICE_STATUSES));
    }

    @Transactional
    public int countLinkedBoardOrdersMatching(
            String orderStatus,
            String keyword,
            Long companyId,
            Set<Long> visibleManagerIds
    ) {
        List<CommonInvoice> invoices = normalizedBoardInvoices();
        if (invoices.isEmpty()) {
            return 0;
        }
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoice = invoiceOrderRepository
                .findByInvoiceIdsWithOrders(invoices.stream().map(CommonInvoice::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(item -> item.getInvoice().getId()));
        String normalizedStatus = normalize(orderStatus);
        String normalizedKeyword = normalize(keyword).toLowerCase(Locale.ROOT);
        return (int) invoices.stream()
                .filter(invoice -> visibleToManager(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), visibleManagerIds))
                .flatMap(invoice -> itemsByInvoice.getOrDefault(invoice.getId(), List.of()).stream())
                .filter(item -> itemVisibleInOrderMetrics(item, visibleManagerIds))
                .filter(item -> matchesLinkedOrderStatus(item, normalizedStatus))
                .filter(item -> matchesLinkedOrderCompany(item, companyId))
                .filter(item -> matchesLinkedOrderKeyword(item, normalizedKeyword))
                .map(CommonInvoiceOrder::getOrder)
                .filter(order -> order != null && order.getId() != null)
                .map(Order::getId)
                .distinct()
                .count();
    }

    @Transactional
    public Map<String, Integer> countManagerBoardCards(Set<Long> visibleManagerIds) {
        List<CommonInvoice> invoices = normalizedBoardInvoices();
        if (invoices.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoice = invoiceOrderRepository
                .findByInvoiceIdsWithOrders(invoices.stream().map(CommonInvoice::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(item -> item.getInvoice().getId()));
        Map<String, Integer> counts = new HashMap<>();
        invoices.stream()
                .filter(invoice -> visibleToManager(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), visibleManagerIds))
                .map(invoice -> boardStatus(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of())))
                .forEach(status -> counts.merge(status, 1, Integer::sum));
        return counts;
    }

    @Transactional
    public Map<String, Integer> countLinkedManagerBoardOrders(Set<Long> visibleManagerIds) {
        List<CommonInvoice> invoices = normalizedBoardInvoices();
        if (invoices.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoice = invoiceOrderRepository
                .findByInvoiceIdsWithOrders(invoices.stream().map(CommonInvoice::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(item -> item.getInvoice().getId()));
        Map<String, Integer> counts = new HashMap<>();
        invoices.stream()
                .filter(invoice -> visibleToManager(invoice, itemsByInvoice.getOrDefault(invoice.getId(), List.of()), visibleManagerIds))
                .flatMap(invoice -> itemsByInvoice.getOrDefault(invoice.getId(), List.of()).stream())
                .filter(item -> itemVisibleInOrderMetrics(item, visibleManagerIds))
                .map(item -> statusTitle(item.getOrder()))
                .filter(status -> !status.isBlank())
                .forEach(status -> counts.merge(status, 1, Integer::sum));
        return counts;
    }

    @Transactional
    public CommonBillingAccountResponse createAccount(CommonBillingAccountRequest request) {
        CommonBillingAccount account = new CommonBillingAccount();
        applyAccountRequest(account, request);
        ensureAccountRequestVisibleForCurrentUser(account, request == null ? List.of() : request.companyIds(), true);
        account = accountRepository.save(account);
        replaceCompanies(account, request == null ? List.of() : request.companyIds());
        return account(account.getId());
    }

    @Transactional(readOnly = true)
    public CommonBillingAccountResponse account(Long accountId) {
        CommonBillingAccount account = accountRepository.findByIdWithRelations(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(account);
        return toAccountResponse(account, accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(accountId));
    }

    @Transactional
    public CommonBillingAccountResponse updateAccount(Long accountId, CommonBillingAccountRequest request) {
        CommonBillingAccount account = accountRepository.findByIdWithRelations(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(account);
        applyAccountRequest(account, request);
        ensureAccountRequestVisibleForCurrentUser(account, request == null ? null : request.companyIds(), false);
        accountRepository.save(account);
        if (!account.isEnabled()) {
            detachCurrentAccountOrders(account);
            disableAccountCompanies(account);
            return account(accountId);
        }
        if (request != null && request.companyIds() != null) {
            replaceCompanies(account, request.companyIds());
        }
        return account(accountId);
    }

    @Transactional
    public CommonBillingAccountResponse addCompany(Long accountId, Long companyId) {
        CommonBillingAccount account = accountRepository.findByIdWithRelations(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(account);
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
        addCompanyToAccount(account, company);
        return account(accountId);
    }

    @Transactional
    public CommonBillingAccountResponse removeCompany(Long accountId, Long companyId, boolean detachCurrent) {
        CommonBillingAccount account = accountRepository.findByIdWithRelations(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(account);
        accountCompanyRepository.findByAccount_IdAndCompany_Id(accountId, companyId).ifPresent(link -> {
            link.setEnabled(false);
            saveAccountCompany(link);
        });
        if (detachCurrent) {
            detachCurrentCompanyOrders(account, companyId);
        }
        return account(accountId);
    }

    @Transactional
    public boolean attachOrderIfNeeded(Order order) {
        if (order == null || order.getId() == null || order.getCompany() == null || order.getCompany().getId() == null) {
            return false;
        }
        if (invoiceOrderRepository.findByOrder_Id(order.getId()).isPresent()) {
            return true;
        }
        Optional<CommonBillingAccount> account = enabledAccountForCompany(order.getCompany().getId());
        if (account.isEmpty()) {
            return false;
        }
        CommonInvoiceOrder item = attachOrderToInvoice(account.get(), order);
        log.info("Order {} attached to common invoice {} for account {}", order.getId(), item.getInvoice().getId(), account.get().getId());
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isOrderInActiveCommonInvoice(Long orderId) {
        if (orderId == null) {
            return false;
        }
        return invoiceOrderRepository.findByOrderIdWithInvoice(orderId)
                .map(CommonInvoiceOrder::getInvoice)
                .map(invoice -> invoice.getStatus() != CommonInvoiceStatus.PAID
                        && invoice.getStatus() != CommonInvoiceStatus.BAN
                        && invoice.getStatus() != CommonInvoiceStatus.DISABLED)
                .orElse(false);
    }

    @Transactional
    public boolean refreshLinkedOrderAmount(Long orderId) {
        if (orderId == null) {
            return false;
        }
        Optional<CommonInvoiceOrder> optionalItem = invoiceOrderRepository.findByOrderIdWithInvoice(orderId);
        if (optionalItem.isEmpty()) {
            return false;
        }
        CommonInvoiceOrder item = optionalItem.get();
        CommonInvoice invoice = item.getInvoice();
        if (item.isPaid()
                || invoice.getStatus() == CommonInvoiceStatus.PAID
                || invoice.getStatus() == CommonInvoiceStatus.BAN
                || invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            return true;
        }
        Long payable = payableKopecksOrMarkAttention(invoice, item.getOrder());
        if (payable == null) {
            return true;
        }
        item.setAmountKopecks(payable);
        invoiceOrderRepository.save(item);
        recalculateInvoice(invoice, invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId()));
        return true;
    }

    @Transactional
    public boolean completePublishedOrderIntoCommonInvoice(Order order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        attachOrderIfNeeded(order);
        Optional<CommonInvoiceOrder> optionalItem = invoiceOrderRepository.findByOrderIdWithInvoice(order.getId());
        if (optionalItem.isEmpty()) {
            return false;
        }
        CommonInvoiceOrder item = optionalItem.get();
        CommonInvoice invoice = item.getInvoice();
        if (invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION) {
            markOrderWaitingCommonInvoice(order);
            return true;
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID
                || invoice.getStatus() == CommonInvoiceStatus.UNPAID
                || invoice.getStatus() == CommonInvoiceStatus.BAN) {
            return false;
        }
        Long payable = payableKopecksOrMarkAttention(invoice, item.getOrder());
        if (payable == null) {
            markOrderWaitingCommonInvoice(order);
            return true;
        }
        item.setReady(true);
        item.setAmountKopecks(payable);
        invoiceOrderRepository.save(item);
        markOrderWaitingCommonInvoice(order);
        recalculateInvoice(invoice);
        if (isInvoiceReady(invoice.getId())) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            invoiceRepository.save(invoice);
            markInvoiceOrdersPublished(invoice.getId());
            if (immediateClientMessagesEnabled()) {
                sendInvoiceAfterCommit(invoice.getId(), false);
            } else {
                invoice.setLastError("auto_send_disabled: моментальные клиентские сообщения выключены");
                invoiceRepository.save(invoice);
            }
        }
        return true;
    }

    public CommonInvoiceDetailsResponse sendInvoice(Long invoiceId, boolean manual) {
        PreparedCommonInvoiceMessage prepared = writeTransaction(() ->
                preparePaymentMessage(invoiceId, false, manual, false, null, true)
        );
        if (prepared != null) {
            ClientMessageSendResult result = sendPreparedPaymentMessage(prepared);
            writeTransaction(() -> {
                finishPaymentMessageSend(prepared, result);
                return null;
            });
        }
        return writeTransaction(() -> invoice(invoiceId));
    }

    private void resetToReadyOnlyBeforeFirstSend(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        if (invoice.getStatus() == CommonInvoiceStatus.COLLECTING || invoice.getStatus() == CommonInvoiceStatus.READY) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            invoice.setSentAt(null);
        }
    }

    private boolean shouldManualMarkInvoiceToPay(CommonInvoice invoice) {
        return invoice != null
                && (invoice.getStatus() == CommonInvoiceStatus.COLLECTING
                || invoice.getStatus() == CommonInvoiceStatus.READY);
    }

    public int sendDueReminders(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<CommonInvoice> invoices = invoiceRepository.findReminderCandidates(
                REMINDER_STATUSES,
                now,
                PageRequest.of(0, Math.max(1, limit))
        );
        int sent = 0;
        for (CommonInvoice candidate : invoices) {
            PreparedCommonInvoiceMessage prepared = writeTransaction(() ->
                    preparePaymentMessage(candidate.getId(), true, false, true, now, false)
            );
            if (prepared != null) {
                ClientMessageSendResult result = sendPreparedPaymentMessage(prepared);
                boolean delivered = writeTransaction(() -> finishPaymentMessageSend(prepared, result));
                if (delivered) {
                    sent++;
                }
            }
        }
        return sent;
    }

    public int cancelPendingArchivedPayments(int limit) {
        List<CommonInvoicePaymentRef> refs = paymentRefRepository.findCancelableRefs(
                PAYMENT_REF_CANCEL_PENDING,
                PAYMENT_REF_CANCEL_FAILED,
                PAYMENT_REF_INIT_CONFLICT,
                PAYMENT_REF_CANCELING,
                LocalDateTime.now().minus(PAYMENT_REF_CANCEL_RETRY_DELAY),
                LocalDateTime.now().minus(PAYMENT_REF_CANCELING_TIMEOUT),
                PAYMENT_REF_CANCEL_MAX_ATTEMPTS,
                PageRequest.of(0, Math.max(1, limit))
        );
        int processed = 0;
        for (CommonInvoicePaymentRef candidate : refs) {
            PreparedArchivedPaymentCancel prepared = writeTransaction(() -> prepareArchivedPaymentCancel(candidate.getId()));
            if (prepared == null) {
                continue;
            }
            String status = cancelArchivedPayment(prepared);
            writeTransaction(() -> {
                finishArchivedPaymentCancel(prepared.refId(), status);
                return null;
            });
            processed++;
        }
        return processed;
    }

    public CommonInvoiceDetailsResponse sendManualReminder(Long invoiceId) {
        PreparedCommonInvoiceMessage prepared = writeTransaction(() ->
                preparePaymentMessage(invoiceId, true, true, false, null, true)
        );
        if (prepared != null) {
            ClientMessageSendResult result = sendPreparedPaymentMessage(prepared);
            writeTransaction(() -> {
                finishPaymentMessageSend(prepared, result);
                return null;
            });
        }
        return writeTransaction(() -> invoice(invoiceId));
    }

    @Transactional
    public CommonInvoiceDetailsResponse invoice(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        return invoiceDetails(invoice, items);
    }

    @Transactional
    public PublicCommonInvoiceResponse publicInvoice(String token) {
        CommonInvoice invoice = lockedInvoiceByToken(cleanToken(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        refreshInvoiceAmounts(invoice, items);
        long remaining = remainingKopecks(invoice);
        return new PublicCommonInvoiceResponse(
                invoice.getToken(),
                invoice.getTitle(),
                invoice.getAccount().getName(),
                effectiveInvoiceStatus(invoice, items).name(),
                amountRubles(invoice.getAmountKopecks()),
                amountRubles(invoice.getPaidKopecks()),
                amountRubles(remaining),
                invoice.getAmountKopecks(),
                invoice.getPaidKopecks(),
                remaining,
                remaining > 0
                        && canAcceptPublicPayment(invoice),
                items.stream().map(this::toOrderResponse).toList()
        );
    }

    @Transactional
    public CommonInvoiceDetailsResponse markOrderPaid(Long invoiceId, Long orderId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanChangePositions(invoice);
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден в общем счете"));
        if (!invoice.getId().equals(item.getInvoice().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ относится к другому общему счету");
        }
        try {
            closeOrderAsPaidWithoutNextOrder(item.getOrder());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Заказ не удалось закрыть как оплаченный", e);
        }
        item.setPaid(true);
        item.setUnpaid(false);
        item.setPaidAt(LocalDateTime.now());
        invoiceOrderRepository.save(item);
        recalculateInvoice(invoice);
        closePaidIfAllItemsPaid(invoice);
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse approveReviewOrders(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanChangePositions(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        List<CommonInvoiceOrder> candidates = items.stream()
                .filter(item -> item.getOrder() != null
                        && REVIEW_APPROVAL_STATUSES.contains(statusTitle(item.getOrder())))
                .toList();
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "В общем счете нет заказов в статусе \"В проверку\" или \"На проверке\""
            );
        }

        List<String> failures = new ArrayList<>();
        for (CommonInvoiceOrder item : candidates) {
            Order order = item.getOrder();
            try {
                orderStatusTransitionService.changeStatusForCommonBillingOrder(order.getId(), STATUS_TO_PUBLISH);
            } catch (Exception e) {
                failures.add(orderFailureLabel(item));
                log.warn("Не удалось одобрить заказ {} из общего счета {}",
                        order == null ? null : order.getId(), invoiceId, e);
            }
        }
        if (!failures.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не все заказы общего счета удалось одобрить: " + String.join(", ", failures)
            );
        }
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse detachOrder(Long invoiceId, Long orderId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanChangePositions(invoice);
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден в общем счете"));
        if (!invoice.getId().equals(item.getInvoice().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ относится к другому общему счету");
        }

        Order order = item.getOrder();
        if (item.isPaid()) {
            try {
                closeOrderAsPaidWithoutNextOrder(order);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Заказ отмечен оплаченным, но не удалось закрыть его отдельно", e);
            }
        } else {
            restoreDetachedOrderStatus(order, item.getOriginalOrderStatusTitle());
        }

        invoiceOrderRepository.delete(item);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        recalculateInvoice(invoice, items);
        if (items.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.DISABLED);
            invoice.setNextReminderAt(null);
            invoice.setLastError("empty: в общем счете нет заказов");
            invoiceRepository.save(invoice);
            return new CommonInvoiceDetailsResponse(toInvoiceSummary(invoice, List.of()), List.of(), List.of());
        }
        if (isInvoiceReady(invoiceId) && invoice.getStatus() == CommonInvoiceStatus.COLLECTING) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            invoiceRepository.save(invoice);
            markInvoiceOrdersPublished(items);
        }
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markPaid(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        if (invoice.getStatus() == CommonInvoiceStatus.PAID
                || invoice.getStatus() == CommonInvoiceStatus.DISABLED
                || invoice.getStatus() == CommonInvoiceStatus.BAN) {
            ensureCommonInvoiceCanBeMarkedPaid(invoice);
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        promoteCollectingInvoiceToReadyIfPossible(invoice, items);
        ensureCommonInvoiceCanBeMarkedPaid(invoice);
        archiveAndClearCurrentPaymentRef(invoice, "manual_paid");
        closePaidInvoice(invoice, items);
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse retryAttention(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        ensureAttentionCanBeRetried(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        closePaidInvoice(invoice, items);
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse resolveAttention(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureAttentionCanBeResolved(invoice, items);
        resolveAttentionByCurrentItems(invoice, items);
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse confirmFinalPaymentCancelCheck(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!attentionError(invoice).startsWith(PAYMENT_CANCEL_FAILED_FINAL)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета нет финальной ошибки отмены T-Bank ссылки");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureNoRecordedFullPaymentWithOpenItems(invoice, items);
        resolveAttentionByCurrentItems(invoice, items);
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse confirmPaymentInitCheck(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!isPaymentInitManualCheckAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета нет ручной проверки создания T-Bank ссылки");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureNoRecordedFullPaymentWithOpenItems(invoice, items);
        resolveAttentionByCurrentItems(invoice, items);
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse resolveTechnicalTail(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureCommonInvoiceTechnicalTailCanBeResolved(invoice, items);
        invoice.setLastError(null);
        invoice.setNextReminderAt(null);
        invoiceRepository.save(invoice);
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse resolvePaymentSuccessNotification(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if (normalize(invoice.getPaymentSuccessNotificationError()).isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета нет ошибки уведомления об оплате");
        }
        invoice.setPaymentSuccessNotificationError(null);
        if (invoice.getPaymentSuccessNotifiedAt() == null) {
            invoice.setPaymentSuccessNotifiedAt(LocalDateTime.now());
        }
        invoiceRepository.save(invoice);
        return invoice(invoiceId);
    }

    private void resolveAttentionByCurrentItems(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        recalculateInvoice(invoice, items);
        if (items.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.DISABLED);
            invoice.setNextReminderAt(null);
            invoice.setLastError("empty: в общем счете нет заказов");
            invoiceRepository.save(invoice);
            return;
        }
        boolean allPaid = !items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isPaid);
        if (allPaid || remainingKopecks(invoice) <= 0) {
            invoice.setStatus(CommonInvoiceStatus.PAID);
            invoice.setPaidKopecks(invoice.getAmountKopecks());
            if (invoice.getPaidAt() == null) {
                invoice.setPaidAt(LocalDateTime.now());
            }
            invoice.setNextReminderAt(null);
        } else if (invoice.getPaidKopecks() > 0) {
            invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        } else if (isInvoiceReady(invoice.getId())) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            markInvoiceOrdersPublished(items);
        } else {
            invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        }
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
    }

    @Transactional
    public CommonInvoiceDetailsResponse applyLatePayment(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!isLatePaymentAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета нет позднего T-Bank платежа для распределения");
        }

        List<CommonInvoicePaymentRef> refs = paymentRefRepository
                .findByInvoiceIdAndStatusForUpdate(invoiceId, PAYMENT_REF_CONFIRMED);
        long availableKopecks = refs.stream()
                .map(CommonInvoicePaymentRef::getAmountKopecks)
                .filter(amount -> amount != null && amount > 0)
                .mapToLong(Long::longValue)
                .sum();
        if (availableKopecks <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не найдена подтвержденная сумма старой T-Bank ссылки");
        }
        setPaymentRefsStatus(refs, PAYMENT_REF_APPLYING);

        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        long remainingPaymentKopecks = availableKopecks;
        if (remainingPaymentKopecks <= 0) {
            finishLatePaymentApply(invoice, refs, items, 0);
            return invoice(invoiceId);
        }

        List<CommonInvoiceOrder> sortedItems = items.stream()
                .filter(item -> !item.isPaid())
                .sorted(Comparator.comparing(item -> {
                    Order order = item.getOrder();
                    return order == null || order.getId() == null ? Long.MAX_VALUE : order.getId();
                }))
                .toList();
        List<String> closeFailures = new ArrayList<>();
        for (CommonInvoiceOrder item : sortedItems) {
            long itemAmount = Math.max(0, item.getAmountKopecks());
            if (itemAmount > remainingPaymentKopecks) {
                break;
            }
            try {
                closeOrderAsPaidWithoutNextOrder(item.getOrder());
                item.setPaid(true);
                item.setUnpaid(false);
                item.setPaidAt(LocalDateTime.now());
                remainingPaymentKopecks -= itemAmount;
            } catch (Exception e) {
                closeFailures.add(orderFailureLabel(item));
                log.warn("Не удалось закрыть заказ {} поздним платежом старой ссылки общего счета {}",
                        item.getOrder() == null ? null : item.getOrder().getId(), invoiceId, e);
                break;
            }
        }
        invoiceOrderRepository.saveAll(items);
        refreshInvoiceAmounts(invoice, items);

        if (!closeFailures.isEmpty()) {
            setPaymentRefsStatus(refs, PAYMENT_REF_CONFIRMED);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setLastError(limit(
                    "late_payment_close_failed: поздний платеж найден, но заказы не закрылись: "
                            + String.join(", ", closeFailures),
                    512
            ));
            invoiceRepository.save(invoice);
            return invoice(invoiceId);
        }

        finishLatePaymentApply(invoice, refs, items, remainingPaymentKopecks);
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markUnpaid(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanBeMarkedUnpaid(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (items.stream().noneMatch(item -> !item.isPaid())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В общем счете нет неоплаченных заказов");
        }
        List<String> failures = new ArrayList<>();
        List<CommonInvoiceOrder> changedItems = new ArrayList<>();
        for (CommonInvoiceOrder item : items) {
            if (item.isPaid()) {
                continue;
            }
            try {
                Order order = item.getOrder();
                if (order == null || order.getId() == null) {
                    throw new IllegalStateException("Заказ не найден");
                }
                orderStatusTransitionService.changeStatusForCommonBillingOrder(order.getId(), STATUS_NOT_PAID);
                changedItems.add(item);
            } catch (Exception e) {
                failures.add(orderFailureLabel(item));
                log.warn("Не удалось перевести заказ {} из общего счета {} в Не оплачено",
                        item.getOrder() == null ? null : item.getOrder().getId(), invoiceId, e);
            }
        }
        if (!failures.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не все заказы общего счета удалось перевести в Не оплачено: " + String.join(", ", failures)
            );
        }
        ensureBadReviewTasksForItems(changedItems);
        changedItems.forEach(item -> item.setUnpaid(true));
        archiveAndClearCurrentPaymentRef(invoice, "manual_unpaid");
        invoice.setStatus(CommonInvoiceStatus.UNPAID);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
        invoiceOrderRepository.saveAll(items);
        invoiceRepository.save(invoice);
        return invoice(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markBan(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        if (invoice.getStatus() != CommonInvoiceStatus.UNPAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В Бан можно перевести только общий счет в статусе Не оплачено");
        }

        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        List<CommonInvoiceOrder> unpaidItems = items.stream()
                .filter(item -> !item.isPaid())
                .toList();
        if (unpaidItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В общем счете нет неоплаченных заказов для Бана");
        }
        ensureBadReviewTasksForItems(unpaidItems);

        BadReviewTaskSummary summary = aggregateBadReviewSummary(unpaidItems);
        boolean privileged = currentUserCanForceBan();
        if (!privileged && summary.total() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Бан менеджеру доступен после создания плохих задач");
        }
        if (!privileged && summary.pending() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала выполните все плохие задачи общего счета");
        }

        List<String> failures = new ArrayList<>();
        for (CommonInvoiceOrder item : unpaidItems) {
            try {
                Order order = item.getOrder();
                if (order == null || order.getId() == null) {
                    throw new IllegalStateException("Заказ не найден");
                }
                if (privileged) {
                    badReviewTaskService.cancelPendingTasksForOrder(order);
                    orderStatusTransitionService.changeStatusForPrivilegedCommonBillingOrder(order.getId(), STATUS_BAN);
                } else {
                    orderStatusTransitionService.changeStatusForCommonBillingOrder(order.getId(), STATUS_BAN);
                }
            } catch (Exception e) {
                failures.add(orderFailureLabel(item));
                log.warn("Не удалось перевести заказ {} из общего счета {} в Бан",
                        item.getOrder() == null ? null : item.getOrder().getId(), invoiceId, e);
            }
        }
        if (!failures.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не все заказы общего счета удалось перевести в Бан: " + String.join(", ", failures)
            );
        }

        archiveAndClearCurrentPaymentRef(invoice, "manual_ban");
        invoice.setStatus(CommonInvoiceStatus.BAN);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        return invoice(invoiceId);
    }

    private void ensureBadReviewTasksForItems(List<CommonInvoiceOrder> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (CommonInvoiceOrder item : items) {
            Order order = item == null ? null : item.getOrder();
            if (order == null || order.getId() == null) {
                continue;
            }
            try {
                badReviewTaskService.createTasksForUnpaidOrder(order);
            } catch (RuntimeException e) {
                log.warn("Не удалось создать плохие задачи для заказа {} из общего счета", order.getId(), e);
            }
        }
    }

    public PublicPaymentInitResponse initPublicPayment(
            String token,
            String email,
            boolean offerConsent,
            boolean privacyConsent,
            boolean receiptConsent
    ) {
        if (!offerConsent || !privacyConsent || !receiptConsent) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвердите согласия для оплаты");
        }
        if (!runtimeSettingsService.isPaymentLinksEnabled() || !runtimeSettingsService.isTbankEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежные ссылки выключены в настройках");
        }
        String cleanEmail = normalize(email);
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите e-mail для электронного чека");
        }

        PreparedCommonPaymentInit prepared = writeTransaction(() -> preparePaymentInit(cleanToken(token), cleanEmail));
        if (prepared.cachedResponse() != null) {
            return prepared.cachedResponse();
        }
        TbankInitResponse response;
        try {
            response = tbankClient.init(prepared.runtimeProfile(), new TbankInitCommand(
                    prepared.tbankOrderId(),
                    prepared.remainingKopecks(),
                    "Репутационные услуги",
                    prepared.email(),
                    properties.notificationUrl(),
                    properties.successUrl(),
                    properties.failUrl(),
                    OffsetDateTime.now(MOSCOW_ZONE).plus(properties.getRedirectDue())
            ));
        } catch (RuntimeException e) {
            writeTransaction(() -> {
                failPaymentInit(prepared, "payment_init_exception: " + readableException(e));
                return null;
            });
            throw e;
        }
        return writeTransaction(() -> finishPaymentInit(prepared, response));
    }

    private PreparedCommonPaymentInit preparePaymentInit(String token, String cleanEmail) {
        CommonInvoice invoice = lockedInvoiceByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureNoOperationInProgress(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        refreshInvoiceAmounts(invoice, items);
        long remaining = remainingKopecks(invoice);
        if (!canAcceptPublicPayment(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет еще не готов к оплате");
        }
        if (remaining <= 0) {
            closePaidInvoice(invoice, items);
            return new PreparedCommonPaymentInit(
                    invoice.getId(),
                    cleanEmail,
                    0,
                    null,
                    null,
                    new PublicPaymentInitResponse("", "", invoice.getStatus().name())
            );
        }

        if (invoice.getPaymentUrl() != null
                && invoice.getTbankPaymentAmountKopecks() != null
                && invoice.getTbankPaymentAmountKopecks() == remaining
                && invoice.getTbankPaymentCreatedAt() != null
                && invoice.getTbankPaymentCreatedAt().plus(properties.getRedirectDue()).isAfter(LocalDateTime.now())) {
            return new PreparedCommonPaymentInit(
                    invoice.getId(),
                    cleanEmail,
                    remaining,
                    null,
                    null,
                    new PublicPaymentInitResponse(invoice.getPaymentUrl(), invoice.getTbankPaymentId(), invoice.getStatus().name())
            );
        }

        Manager manager = manager(invoice);
        var profile = paymentProfileService.selectForManager(manager);
        profile = paymentProfileService.lockForRouting(profile);
        TbankPaymentProfile runtimeProfile = paymentProfileService.toRuntime(profile);
        if (!runtimeProfile.hasCredentials()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не заданы TerminalKey или Password Т-Банка");
        }

        String tbankOrderId = groupTbankOrderId(invoice);
        invoice.setPayerEmail(cleanEmail);
        invoice.setLastError(PAYMENT_INIT_IN_PROGRESS);
        invoiceRepository.save(invoice);
        return new PreparedCommonPaymentInit(
                invoice.getId(),
                cleanEmail,
                remaining,
                runtimeProfile,
                tbankOrderId,
                null
        );
    }

    private PublicPaymentInitResponse finishPaymentInit(PreparedCommonPaymentInit prepared, TbankInitResponse response) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (!PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError()))) {
            if (response.success()) {
                recordInitializedPaymentRef(invoice, prepared, response, "init_finalized_after_invoice_changed");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет изменился во время создания платежной ссылки");
        }
        if (!response.success()) {
            invoice.setLastError(limit("tbank_init_failed: " + response.errorText(), 512));
            invoiceRepository.save(invoice);
            throw new ResponseStatusException(HttpStatus.CONFLICT, response.errorText());
        }

        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        refreshInvoiceAmounts(invoice, items);
        if (!canAcceptPublicPayment(invoice) || remainingKopecks(invoice) != prepared.remainingKopecks()) {
            recordInitializedPaymentRef(invoice, prepared, response, "init_conflict_after_amount_changed");
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    "payment_init_conflict: T-Bank создал ссылку "
                            + paymentRefLabel(prepared.tbankOrderId(), response.paymentId())
                            + " на " + amountRubles(prepared.remainingKopecks())
                            + " руб., но состав или сумма общего счета изменились; нужна ручная сверка",
                    512
            ));
            invoiceRepository.save(invoice);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав или сумма общего счета изменились. Повторите оплату.");
        }

        archiveCurrentPaymentRef(invoice, "new_payment_created");
        invoice.setPayerEmail(prepared.email());
        invoice.setTbankOrderId(prepared.tbankOrderId());
        invoice.setTbankPaymentId(response.paymentId());
        invoice.setTbankTerminalKey(prepared.runtimeProfile().terminalKey());
        invoice.setTbankPaymentAmountKopecks(prepared.remainingKopecks());
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now());
        invoice.setPaymentUrl(response.paymentUrl());
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        return new PublicPaymentInitResponse(response.paymentUrl(), response.paymentId(), invoice.getStatus().name());
    }

    private void failPaymentInit(PreparedCommonPaymentInit prepared, String error) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null || !PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError()))) {
            return;
        }
        recordPreparedPaymentInitRef(invoice, prepared, "init_exception_before_response");
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(error + "; проверьте банк вручную перед повторной оплатой", 512));
        invoiceRepository.save(invoice);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public boolean handleTbankWebhook(Map<String, String> payload) {
        VerifiedWebhookProfile verified = verifyWebhook(payload);
        String orderId = normalize(payload.get("OrderId"));
        String paymentId = normalize(payload.get("PaymentId"));
        Optional<CommonInvoice> candidate = !orderId.isBlank()
                ? invoiceRepository.findByTbankOrderId(orderId)
                : Optional.empty();
        if (candidate.isEmpty() && !paymentId.isBlank()) {
            candidate = invoiceRepository.findByTbankPaymentId(paymentId);
        }
        if (candidate.isEmpty()) {
            return handleArchivedPaymentWebhook(payload, orderId, paymentId, verified.runtimeProfile());
        }

        CommonInvoice invoice = lockedInvoice(candidate.get().getId()).orElse(candidate.get());
        if (!matchesCurrentPaymentRef(invoice, orderId, paymentId)) {
            return handleArchivedPaymentWebhook(payload, orderId, paymentId, verified.runtimeProfile());
        }
        validateWebhookTerminal(invoice, verified.runtimeProfile());
        validateWebhookAmount(invoice, payload);
        invoice.setTbankPaymentId(paymentId.isBlank() ? invoice.getTbankPaymentId() : paymentId);
        invoice.setTbankTerminalKey(verified.runtimeProfile().terminalKey());

        String status = normalize(payload.get("Status")).toUpperCase(Locale.ROOT);
        boolean success = "true".equalsIgnoreCase(normalize(payload.get("Success")));
        String errorCode = normalize(payload.get("ErrorCode"));
        if ("CONFIRMED".equals(status)) {
            if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
                return true;
            }
            if (invoice.getStatus() == CommonInvoiceStatus.UNPAID
                    || invoice.getStatus() == CommonInvoiceStatus.BAN
                    || invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
                recordCurrentPaymentRef(invoice, PAYMENT_REF_CONFIRMED, "confirmed_after_terminal_status");
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
                invoice.setLastError(limit(
                        "late_tbank_payment: оплачена ссылка после закрытия общего счета; требуется ручная сверка",
                        512
                ));
                invoiceRepository.save(invoice);
                return true;
            }
            List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
            closePaidInvoice(invoice, items);
        } else if ("REJECTED".equals(status) || (!success && !errorCode.isBlank() && !"0".equals(errorCode))) {
            recordCurrentPaymentRef(invoice, status.isBlank() ? "REJECTED" : status, "current_payment_rejected");
            invoice.setLastError(limit("tbank_payment_rejected: " + (errorCode.isBlank() ? status : errorCode), 512));
            invoiceRepository.save(invoice);
        }
        return true;
    }

    private boolean handleArchivedPaymentWebhook(
            Map<String, String> payload,
            String orderId,
            String paymentId,
            TbankPaymentProfile runtimeProfile
    ) {
        Optional<CommonInvoicePaymentRef> ref = !orderId.isBlank()
                ? paymentRefRepository.findByTbankOrderId(orderId)
                : Optional.empty();
        if (ref.isEmpty() && !paymentId.isBlank()) {
            ref = paymentRefRepository.findByTbankPaymentId(paymentId);
        }
        if (ref.isEmpty()) {
            return false;
        }

        CommonInvoicePaymentRef paymentRef = ref.get();
        CommonInvoice invoice = lockedInvoice(paymentRef.getInvoice().getId()).orElse(paymentRef.getInvoice());
        paymentRef = lockedPaymentRef(paymentRef).orElse(paymentRef);
        validateArchivedWebhookTerminal(paymentRef, runtimeProfile);
        validateArchivedWebhookAmount(paymentRef, payload);

        String status = normalize(payload.get("Status")).toUpperCase(Locale.ROOT);
        boolean success = "true".equalsIgnoreCase(normalize(payload.get("Success")));
        String errorCode = normalize(payload.get("ErrorCode"));
        if (isPaymentRefAlreadyHandled(paymentRef)) {
            log.info("Повторный webhook старой ссылки общего счета {} уже обработан: {} ({})",
                    invoice.getId(), paymentRefLabel(paymentRef), status);
            return true;
        }
        paymentRef.setStatus(status.isBlank() ? "WEBHOOK" : status);
        paymentRefRepository.save(paymentRef);

        if (PAYMENT_REF_CONFIRMED.equals(status)) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    "late_tbank_payment: оплачена старая ссылка " + paymentRefLabel(paymentRef)
                            + ", сумма " + amountRubles(paymentRef.getAmountKopecks() == null ? 0 : paymentRef.getAmountKopecks())
                            + " руб.; требуется ручная сверка",
                    512
            ));
            invoiceRepository.save(invoice);
        } else if (PAYMENT_REF_REFUNDED_STATUSES.contains(status)
                && invoice.getStatus() == CommonInvoiceStatus.PAID) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    "tbank_payment_refunded: оплаченный общий счет получил статус " + status
                            + " по T-Bank ссылке " + paymentRefLabel(paymentRef)
                            + "; проверьте банк и оплату вручную",
                    512
            ));
            invoiceRepository.save(invoice);
        } else if ("REJECTED".equals(status) || (!success && !errorCode.isBlank() && !"0".equals(errorCode))) {
            invoice.setLastError(limit("archived_payment_" + (errorCode.isBlank() ? status : errorCode), 512));
            invoiceRepository.save(invoice);
        }
        return true;
    }

    private void applyAccountRequest(CommonBillingAccount account, CommonBillingAccountRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заполните параметры общего плательщика");
        }
        String name = normalize(request.name());
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Название общего плательщика обязательно");
        }
        account.setName(limit(name, 160));
        if (request.enabled() != null) {
            account.setEnabled(request.enabled());
        }
        if (request.autoRepeatOrders() != null) {
            account.setAutoRepeatOrders(request.autoRepeatOrders());
        }
        account.setManager(request.managerId() == null ? null : managerRepository.findById(request.managerId()).orElse(null));
        account.setInvoiceCompany(request.invoiceCompanyId() == null
                ? null
                : companyRepository.findById(request.invoiceCompanyId()).orElse(null));
    }

    private void replaceCompanies(CommonBillingAccount account, List<Long> companyIds) {
        if (companyIds == null) {
            return;
        }
        if (account != null && !account.isEnabled()) {
            disableAccountCompanies(account);
            return;
        }
        Map<Long, CommonBillingAccountCompany> existing = accountCompanyRepository
                .findByAccount_IdOrderByCompany_TitleAsc(account.getId())
                .stream()
                .collect(Collectors.toMap(link -> link.getCompany().getId(), Function.identity()));
        Set<Long> requested = companyIds.stream().filter(id -> id != null && id > 0).collect(Collectors.toSet());
        requested.forEach(companyId -> ensureCompanyNotEnabledInAnotherAccount(account.getId(), companyId));
        for (Map.Entry<Long, CommonBillingAccountCompany> entry : existing.entrySet()) {
            boolean wasEnabled = entry.getValue().isEnabled();
            boolean shouldEnable = requested.contains(entry.getKey());
            if (wasEnabled && !shouldEnable && account.getId() != null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Исключайте компанию из общего счета отдельной кнопкой, чтобы явно выбрать судьбу текущих позиций"
                );
            }
            entry.getValue().setEnabled(shouldEnable);
            saveAccountCompany(entry.getValue());
        }
        for (Long companyId : requested) {
            if (existing.containsKey(companyId)) {
                continue;
            }
            Company company = companyRepository.findById(companyId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
            addCompanyToAccount(account, company);
        }
    }

    private void addCompanyToAccount(CommonBillingAccount account, Company company) {
        if (account == null || account.getId() == null || company == null || company.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не выбраны общий плательщик или компания");
        }
        if (!account.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нельзя включить компанию в отключенный общий счет");
        }
        ensureCompanyVisibleForCurrentUser(company);
        ensureCompanyNotEnabledInAnotherAccount(account.getId(), company.getId());
        CommonBillingAccountCompany link = accountCompanyRepository
                .findByAccount_IdAndCompany_Id(account.getId(), company.getId())
                .orElseGet(CommonBillingAccountCompany::new);
        link.setAccount(account);
        link.setCompany(company);
        link.setEnabled(true);
        saveAccountCompany(link);
        if (account.getInvoiceCompany() == null) {
            account.setInvoiceCompany(company);
            accountRepository.save(account);
        }
        absorbDetachedCompanyOpenItems(account, company.getId());
        backfillCompanyOrders(account, company.getId());
    }

    private void saveAccountCompany(CommonBillingAccountCompany link) {
        try {
            accountCompanyRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Компания уже включена в другой активный общий счет",
                    e
            );
        }
    }

    private void disableAccountCompanies(CommonBillingAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        for (CommonBillingAccountCompany link : accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(account.getId())) {
            if (link.isEnabled()) {
                link.setEnabled(false);
                saveAccountCompany(link);
            }
        }
    }

    private void detachCurrentCompanyOrders(CommonBillingAccount account, Long companyId) {
        Optional<CommonInvoice> optionalInvoice = activeInvoice(account);
        if (optionalInvoice.isEmpty()) {
            return;
        }
        CommonInvoice invoice = optionalInvoice.get();
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        List<CommonInvoiceOrder> detachItems = items.stream()
                .filter(item -> item.getOrder() != null
                        && item.getOrder().getCompany() != null
                        && companyId.equals(item.getOrder().getCompany().getId())
                        && !item.isPaid())
                .toList();
        if (detachItems.isEmpty()) {
            return;
        }
        detachItems.forEach(item -> restoreDetachedOrderStatus(item.getOrder(), item.getOriginalOrderStatusTitle()));
        invoiceOrderRepository.deleteAll(detachItems);
        List<CommonInvoiceOrder> remainingItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        recalculateInvoice(invoice, remainingItems);
        if (remainingItems.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.DISABLED);
            invoice.setNextReminderAt(null);
            invoice.setLastError("empty: в общем счете нет заказов");
            invoiceRepository.save(invoice);
        } else if (isInvoiceReady(invoice.getId()) && invoice.getStatus() == CommonInvoiceStatus.COLLECTING) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            invoiceRepository.save(invoice);
            markInvoiceOrdersPublished(remainingItems);
        }
    }

    private void detachCurrentAccountOrders(CommonBillingAccount account) {
        Optional<CommonInvoice> optionalInvoice = activeInvoice(account);
        if (optionalInvoice.isEmpty()) {
            return;
        }
        CommonInvoice invoice = optionalInvoice.get();
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        List<CommonInvoiceOrder> detachItems = items.stream()
                .filter(item -> item.getOrder() != null && !item.isPaid())
                .toList();
        detachItems.forEach(item -> restoreDetachedOrderStatus(item.getOrder(), item.getOriginalOrderStatusTitle()));
        if (!detachItems.isEmpty()) {
            invoiceOrderRepository.deleteAll(detachItems);
            recalculateInvoice(invoice, invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId()));
        }
        invoice.setStatus(CommonInvoiceStatus.DISABLED);
        invoice.setNextReminderAt(null);
        invoice.setLastError("disabled: общий счет выключен, неоплаченные заказы отключены");
        invoiceRepository.save(invoice);
    }

    private Optional<CommonBillingAccount> enabledAccountForCompany(Long companyId) {
        return accountCompanyRepository.findEnabledLinksForCompany(companyId)
                .stream()
                .map(CommonBillingAccountCompany::getAccount)
                .findFirst();
    }

    private Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return invoiceRepository.findByIdWithAccountForUpdate(invoiceId)
                .or(() -> invoiceRepository.findByIdWithAccount(invoiceId));
    }

    private Optional<CommonInvoice> lockedInvoiceByToken(String token) {
        return invoiceRepository.findByTokenWithAccountForUpdate(token)
                .or(() -> invoiceRepository.findByTokenWithAccount(token));
    }

    private <T> T writeTransaction(Supplier<T> action) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= TRANSACTION_LOCK_RETRY_ATTEMPTS; attempt++) {
            try {
                TransactionTemplate template = new TransactionTemplate(transactionManager);
                template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return template.execute(status -> action.get());
            } catch (RuntimeException e) {
                lastException = e;
                if (!isRetryableLockFailure(e) || attempt == TRANSACTION_LOCK_RETRY_ATTEMPTS) {
                    throw e;
                }
                log.warn("Транзакция общего счета упала на блокировке, повтор {}/{}",
                        attempt + 1, TRANSACTION_LOCK_RETRY_ATTEMPTS, e);
                sleepBeforeTransactionRetry();
            }
        }
        throw lastException;
    }

    private boolean isRetryableLockFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("CannotAcquireLock")
                    || className.contains("Deadlock")
                    || className.contains("LockAcquisition")
                    || className.contains("MySQLTransactionRollback")
                    || (message != null && message.toLowerCase(Locale.ROOT).contains("deadlock found"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepBeforeTransactionRetry() {
        try {
            Thread.sleep(TRANSACTION_LOCK_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendInvoiceAfterCommit(Long invoiceId, boolean manual) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendInvoice(invoiceId, manual);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    sendInvoice(invoiceId, manual);
                } catch (RuntimeException e) {
                    log.warn("Не удалось автоотправить общий счет {} после коммита", invoiceId, e);
                }
            }
        });
    }

    private CommonBillingAccount lockedAccount(CommonBillingAccount account) {
        if (account == null || account.getId() == null) {
            return account;
        }
        return accountRepository.findByIdWithRelationsForUpdate(account.getId()).orElse(account);
    }

    private void ensureCompanyNotEnabledInAnotherAccount(Long accountId, Long companyId) {
        accountCompanyRepository.findEnabledLinksForCompany(companyId)
                .stream()
                .filter(link -> !link.getAccount().getId().equals(accountId))
                .findFirst()
                .ifPresent(link -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Компания уже включена в общий счет: " + link.getAccount().getName()
                    );
                });
    }

    private void absorbDetachedCompanyOpenItems(CommonBillingAccount account, Long companyId) {
        if (account == null || account.getId() == null || companyId == null) {
            return;
        }
        List<CommonInvoiceOrder> movableItems = invoiceOrderRepository.findMovableOpenItemsForCompany(
                companyId,
                account.getId(),
                ATTACHABLE_INVOICE_STATUSES
        );
        if (movableItems.isEmpty()) {
            return;
        }

        CommonInvoice targetInvoice = currentInvoice(account);
        Set<CommonInvoice> sourceInvoices = movableItems.stream()
                .map(CommonInvoiceOrder::getInvoice)
                .filter(invoice -> invoice != null && invoice.getId() != null)
                .collect(Collectors.toSet());
        for (CommonInvoiceOrder item : movableItems) {
            item.setInvoice(targetInvoice);
        }
        invoiceOrderRepository.saveAll(movableItems);

        List<CommonInvoiceOrder> targetItems = invoiceOrderRepository.findByInvoiceIdWithOrders(targetInvoice.getId());
        recalculateInvoice(targetInvoice, targetItems);
        promoteCollectingInvoiceToReadyIfPossible(targetInvoice, targetItems);

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
        }

        log.info("Moved detached common invoice items to account {} invoice {} for company {}: moved={}, sources={}",
                account.getId(),
                targetInvoice.getId(),
                companyId,
                movableItems.size(),
                sourceInvoices.stream().map(CommonInvoice::getId).toList());
    }

    private void disableEmptySourceAccount(CommonBillingAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        boolean hasEnabledCompanies = accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(account.getId())
                .stream()
                .anyMatch(CommonBillingAccountCompany::isEnabled);
        if (hasEnabledCompanies) {
            return;
        }
        account.setEnabled(false);
        accountRepository.save(account);
    }

    private void backfillCompanyOrders(CommonBillingAccount account, Long companyId) {
        List<Order> orders = orderRepository.findCommonBillingBackfillOrders(companyId, BACKFILL_STATUSES);
        if (orders.isEmpty()) {
            return;
        }

        CommonInvoice invoice = currentInvoice(account);
        int attached = 0;
        int ready = 0;
        for (Order order : orders) {
            if (invoiceOrderRepository.findByOrder_Id(order.getId()).isPresent()) {
                continue;
            }
            CommonInvoiceOrder item = attachOrderToInvoice(invoice, order);
            attached++;
            if (markBackfilledOrderReadyIfPublished(item)) {
                ready++;
            }
        }
        if (attached > 0) {
            recalculateInvoice(invoice);
            if (isInvoiceReady(invoice.getId())) {
                invoice.setStatus(CommonInvoiceStatus.READY);
                invoiceRepository.save(invoice);
                markInvoiceOrdersPublished(invoice.getId());
            }
            log.info("Backfilled common invoice {} for account {}: attached={}, ready={}",
                    invoice.getId(), account.getId(), attached, ready);
        }
    }

    private CommonInvoiceOrder attachOrderToInvoice(CommonBillingAccount account, Order order) {
        return attachOrderToInvoice(currentInvoice(account), order);
    }

    private CommonInvoiceOrder attachOrderToInvoice(CommonInvoice invoice, Order order) {
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setInvoice(invoice);
        item.setOrder(order);
        Long payable = payableKopecksOrMarkAttention(invoice, order);
        item.setAmountKopecks(payable == null ? 0 : payable);
        item.setOriginalOrderStatusTitle(limit(statusTitle(order), 64));
        item = invoiceOrderRepository.save(item);
        recalculateInvoice(invoice);
        return item;
    }

    private boolean markBackfilledOrderReadyIfPublished(CommonInvoiceOrder item) {
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

    private CommonInvoice currentInvoice(CommonBillingAccount account) {
        CommonBillingAccount lockedAccount = lockedAccount(account);
        List<CommonInvoice> invoices = invoiceRepository.findCurrentForAccountForUpdate(
                        lockedAccount.getId(),
                        ATTACHABLE_INVOICE_STATUSES,
                        PageRequest.of(0, 50)
                );
        if (invoices.isEmpty()) {
            return createInvoice(lockedAccount);
        }
        return normalizeAttachableInvoices(lockedAccount, invoices);
    }

    private List<CommonInvoice> normalizedBoardInvoices() {
        List<CommonInvoice> invoices = invoiceRepository.findBoardInvoices(BOARD_INVOICE_STATUSES);
        if (!hasDuplicateAttachableInvoices(invoices)) {
            return invoices;
        }
        boolean normalized = false;
        Map<Long, List<CommonInvoice>> invoicesByAccount = invoices.stream()
                .filter(invoice -> invoice.getAccount() != null && invoice.getAccount().getId() != null)
                .filter(invoice -> ATTACHABLE_INVOICE_STATUSES.contains(invoice.getStatus()))
                .collect(Collectors.groupingBy(invoice -> invoice.getAccount().getId()));
        for (Map.Entry<Long, List<CommonInvoice>> entry : invoicesByAccount.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            Optional<CommonBillingAccount> lockedAccount = accountRepository.findByIdWithRelationsForUpdate(entry.getKey());
            if (lockedAccount.isEmpty()) {
                continue;
            }
            List<CommonInvoice> currentInvoices = invoiceRepository.findCurrentForAccountForUpdate(
                    entry.getKey(),
                    ATTACHABLE_INVOICE_STATUSES,
                    PageRequest.of(0, 50)
            );
            if (currentInvoices.size() > 1) {
                normalizeAttachableInvoices(lockedAccount.get(), currentInvoices);
                normalized = true;
            }
        }
        return normalized ? invoiceRepository.findBoardInvoices(BOARD_INVOICE_STATUSES) : invoices;
    }

    private boolean hasDuplicateAttachableInvoices(List<CommonInvoice> invoices) {
        Map<Long, Long> countsByAccount = invoices.stream()
                .filter(invoice -> invoice.getAccount() != null && invoice.getAccount().getId() != null)
                .filter(invoice -> ATTACHABLE_INVOICE_STATUSES.contains(invoice.getStatus()))
                .collect(Collectors.groupingBy(invoice -> invoice.getAccount().getId(), Collectors.counting()));
        return countsByAccount.values().stream().anyMatch(count -> count > 1);
    }

    private CommonInvoice normalizeAttachableInvoices(CommonBillingAccount account, List<CommonInvoice> invoices) {
        if (invoices == null || invoices.isEmpty()) {
            return createInvoice(account);
        }
        CommonInvoice target = invoices.stream()
                .min(Comparator.comparing(invoice -> invoice.getId() == null ? Long.MAX_VALUE : invoice.getId()))
                .orElseGet(() -> createInvoice(account));
        List<CommonInvoice> duplicates = invoices.stream()
                .filter(invoice -> invoice.getId() != null && !invoice.getId().equals(target.getId()))
                .toList();
        if (duplicates.isEmpty()) {
            return target;
        }

        List<Long> duplicateIds = duplicates.stream().map(CommonInvoice::getId).toList();
        List<CommonInvoiceOrder> movedItems = invoiceOrderRepository.findByInvoiceIdsWithOrders(duplicateIds);
        for (CommonInvoiceOrder item : movedItems) {
            item.setInvoice(target);
        }
        if (!movedItems.isEmpty()) {
            invoiceOrderRepository.saveAll(movedItems);
        }
        for (CommonInvoice duplicate : duplicates) {
            duplicate.setStatus(CommonInvoiceStatus.DISABLED);
            duplicate.setNextReminderAt(null);
            duplicate.setLastError("merged_into: common_invoice_" + target.getId());
            invoiceRepository.save(duplicate);
        }
        List<CommonInvoiceOrder> targetItems = invoiceOrderRepository.findByInvoiceIdWithOrders(target.getId());
        recalculateInvoice(target, targetItems);
        promoteCollectingInvoiceToReadyIfPossible(target, targetItems);
        log.warn("Объединены дубли открытых общих счетов accountId={}, targetInvoice={}, duplicates={}",
                account == null ? null : account.getId(), target.getId(), duplicateIds);
        return target;
    }

    private Optional<CommonInvoice> activeInvoice(CommonBillingAccount account) {
        account = lockedAccount(account);
        if (account == null || account.getId() == null) {
            return Optional.empty();
        }
        return invoiceRepository.findCurrentForAccount(
                        account.getId(),
                        MUTABLE_INVOICE_STATUSES,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst();
    }

    private CommonInvoice createInvoice(CommonBillingAccount account) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setAccount(account);
        invoice.setToken(randomToken());
        invoice.setTitle(account.getName() + " - общий счет");
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        return invoiceRepository.save(invoice);
    }

    private boolean isInvoiceReady(Long invoiceId) {
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        return areInvoiceItemsReady(items);
    }

    private void promoteCollectingInvoiceToReadyIfPossible(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.COLLECTING || !areInvoiceItemsReady(items)) {
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoiceRepository.save(invoice);
    }

    private boolean areInvoiceItemsReady(List<CommonInvoiceOrder> items) {
        return items != null
                && !items.isEmpty()
                && items.stream().allMatch(CommonInvoiceOrder::isReady)
                && items.stream().map(CommonInvoiceOrder::getOrder).noneMatch(order -> ACTIVE_WORK_STATUSES.contains(statusTitle(order)))
                && !hasActiveRecovery(items);
    }

    private PreparedCommonInvoiceMessage preparePaymentMessage(
            Long invoiceId,
            boolean reminder,
            boolean manual,
            boolean dueOnly,
            LocalDateTime dueNow,
            boolean checkVisibility
    ) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (dueOnly && !isStillDueReminderCandidate(invoice, dueNow)) {
            return null;
        }
        if (checkVisibility) {
            ensureCommonInvoiceVisibleForCurrentUser(invoice);
        }
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanSendPaymentMessages(invoice);
        ensureNoOperationInProgress(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        if (hasActiveRecovery(items)) {
            if (dueOnly) {
                postponeInvoiceForRecovery(invoice);
                return null;
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Общий счет ждет завершения задач восстановления отзывов"
            );
        }
        if (reminder) {
            ensureCommonInvoiceReadyForReminder(invoice, items);
        } else {
            ensureCommonInvoiceReadyForInvoiceSend(invoice, items);
        }
        if (remainingKopecks(invoice) <= 0) {
            closePaidInvoice(invoice, items);
            return null;
        }
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)) {
            if (reminder && manual) {
                invoice.setStatus(CommonInvoiceStatus.REMINDER);
                invoice.setLastReminderAt(LocalDateTime.now());
                markInvoiceOrdersReminder(invoice.getId());
            } else if (!reminder && manual && shouldManualMarkInvoiceToPay(invoice)) {
                invoice.setStatus(CommonInvoiceStatus.INVOICED);
                invoice.setSentAt(LocalDateTime.now());
                markInvoiceOrdersToPay(invoice.getId());
            } else if (!reminder) {
                resetToReadyOnlyBeforeFirstSend(invoice);
            }
            invoice.setNextReminderAt(null);
            invoice.setLastError(reminder
                    ? "dry_run: напоминание общего счета не отправлено, live-режим выключен"
                    : "dry_run: сообщение общего счета не отправлено, live-режим выключен");
            invoiceRepository.save(invoice);
            return null;
        }

        Company chatCompany = chatCompany(invoice);
        Manager manager = manager(invoice);
        invoice.setLastError(MESSAGE_SEND_IN_PROGRESS);
        invoiceRepository.save(invoice);
        return new PreparedCommonInvoiceMessage(
                invoice.getId(),
                chatCompany,
                manager == null ? null : manager.getClientId(),
                chatCompany == null ? null : chatCompany.getGroupId(),
                invoiceMessage(invoice, items, reminder),
                reminder,
                manual
        );
    }

    private ClientMessageSendResult sendPreparedPaymentMessage(PreparedCommonInvoiceMessage prepared) {
        try {
            return messageSender.send(
                    prepared.chatCompany(),
                    prepared.managerClientId(),
                    prepared.groupId(),
                    prepared.message()
            );
        } catch (RuntimeException e) {
            return ClientMessageSendResult.failed("send_exception", readableException(e));
        }
    }

    private boolean finishPaymentMessageSend(PreparedCommonInvoiceMessage prepared, ClientMessageSendResult result) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (!MESSAGE_SEND_IN_PROGRESS.equals(normalize(invoice.getLastError()))) {
            return false;
        }
        if (result.sent()) {
            if (prepared.reminder()) {
                invoice.setStatus(CommonInvoiceStatus.REMINDER);
                invoice.setLastReminderAt(LocalDateTime.now());
                markInvoiceOrdersReminder(invoice.getId());
            } else {
                invoice.setStatus(CommonInvoiceStatus.INVOICED);
                invoice.setSentAt(LocalDateTime.now());
                markInvoiceOrdersToPay(invoice.getId());
            }
            invoice.setNextReminderAt(LocalDateTime.now().plusDays(REMINDER_INTERVAL_DAYS));
            invoice.setLastError(null);
            invoiceRepository.save(invoice);
            return true;
        }

        if (prepared.reminder()) {
            if (prepared.manual()) {
                invoice.setStatus(CommonInvoiceStatus.REMINDER);
                invoice.setLastReminderAt(LocalDateTime.now());
                markInvoiceOrdersReminder(invoice.getId());
            }
            invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        } else if (prepared.manual()) {
            invoice.setStatus(CommonInvoiceStatus.INVOICED);
            invoice.setSentAt(LocalDateTime.now());
            markInvoiceOrdersToPay(invoice.getId());
        } else {
            resetToReadyOnlyBeforeFirstSend(invoice);
        }
        invoice.setLastError(limit(result.errorCode() + ": " + result.errorMessage(), 512));
        if (!prepared.reminder()) {
            if (!prepared.manual()) {
                log.warn("Common invoice {} was ready but not sent: {}", prepared.invoiceId(), invoice.getLastError());
            }
        }
        invoiceRepository.save(invoice);
        return false;
    }

    private boolean isStillDueReminderCandidate(CommonInvoice invoice, LocalDateTime now) {
        return invoice != null
                && REMINDER_STATUSES.contains(invoice.getStatus())
                && invoice.getNextReminderAt() != null
                && !invoice.getNextReminderAt().isAfter(now);
    }

    private void closePaidIfAllItemsPaid(CommonInvoice invoice) {
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        if (items.stream().allMatch(CommonInvoiceOrder::isPaid)) {
            closePaidInvoice(invoice, items);
        } else {
            invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
            invoiceRepository.save(invoice);
        }
    }

    private void ensureCommonInvoiceNotNeedsAttention(CommonInvoice invoice) {
        ensureNoOperationInProgress(invoice);
        if (invoice != null && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Общий счет требует ручной проверки. Обычные действия оплаты и напоминаний временно заблокированы."
            );
        }
    }

    private void ensureNoOperationInProgress(CommonInvoice invoice) {
        recoverStaleOperationInProgress(invoice);
        String error = normalize(invoice == null ? null : invoice.getLastError());
        if (MESSAGE_SEND_IN_PROGRESS.equals(error)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отправка сообщения общего счета уже выполняется");
        }
        if (PAYMENT_INIT_IN_PROGRESS.equals(error)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Создание платежной ссылки общего счета уже выполняется");
        }
    }

    private void recoverStaleOperationInProgress(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        String error = normalize(invoice.getLastError());
        if (!MESSAGE_SEND_IN_PROGRESS.equals(error) && !PAYMENT_INIT_IN_PROGRESS.equals(error)) {
            return;
        }
        LocalDateTime updatedAt = invoice.getUpdatedAt();
        if (updatedAt == null || updatedAt.plus(OPERATION_IN_PROGRESS_TIMEOUT).isAfter(LocalDateTime.now())) {
            return;
        }
        if (PAYMENT_INIT_IN_PROGRESS.equals(error)) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    PAYMENT_INIT_STALE + ": создание T-Bank ссылки зависло; проверьте банк вручную перед повторной оплатой",
                    512
            ));
        } else {
            invoice.setLastError(limit(
                    MESSAGE_SEND_STALE + ": отправка сообщения зависла; можно повторить отправку вручную",
                    512
            ));
        }
        invoiceRepository.save(invoice);
    }

    private void ensureCommonInvoiceCanBeMarkedUnpaid(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        if (!MARK_UNPAID_STATUSES.contains(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В Не оплачено можно перевести только уже выставленный общий счет");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Оплаченный общий счет нельзя перевести в Не оплачено");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.UNPAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже находится в статусе Не оплачено");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.BAN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже находится в статусе Бан");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отключенный общий счет нельзя перевести в Не оплачено");
        }
    }

    private void ensureCommonInvoiceCanSendPaymentMessages(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Оплаченный общий счет нельзя отправлять клиенту");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.UNPAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет в статусе Не оплачено нельзя отправлять клиенту");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.BAN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет в статусе Бан нельзя отправлять клиенту");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отключенный общий счет нельзя отправлять клиенту");
        }
    }

    private void ensureCommonInvoiceCanBeMarkedPaid(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        if (!MARK_PAID_STATUSES.contains(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Оплаченным можно отметить только уже выставленный общий счет");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже оплачен");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отключенный общий счет нельзя отметить оплаченным");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.BAN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет в статусе Бан нельзя отметить оплаченным");
        }
    }

    private void ensureCommonInvoiceCanChangePositions(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID
                || invoice.getStatus() == CommonInvoiceStatus.UNPAID
                || invoice.getStatus() == CommonInvoiceStatus.BAN
                || invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Закрытый общий счет нельзя менять");
        }
    }

    private boolean canAcceptPublicPayment(CommonInvoice invoice) {
        return invoice != null && PUBLIC_PAYABLE_STATUSES.contains(invoice.getStatus());
    }

    private void ensureCommonInvoiceReadyForInvoiceSend(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null) {
            return;
        }
        if (!SEND_INVOICE_STATUSES.contains(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет еще не готов к отправке");
        }
        ensureAllItemsReady(items, "Общий счет еще собирается: не все заказы готовы к оплате");
    }

    private void ensureCommonInvoiceReadyForReminder(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null) {
            return;
        }
        if (!REMINDER_STATUSES.contains(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Напоминание можно отправить только по уже выставленному счету");
        }
        ensureAllItemsReady(items, "Общий счет еще собирается: не все заказы готовы к напоминанию");
    }

    private void ensureAllItemsReady(List<CommonInvoiceOrder> items, String message) {
        if (items == null || items.isEmpty() || items.stream().anyMatch(item -> !item.isReady())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    private void ensureCommonInvoiceNeedsAttention(CommonInvoice invoice) {
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет не находится в ручной проверке");
        }
    }

    private void ensureAttentionCanBeRetried(CommonInvoice invoice) {
        String error = attentionError(invoice);
        if (isLatePaymentAttention(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "По старой T-Bank ссылке пришел поздний платеж. Его нельзя автоматически применить: нужна ручная сверка суммы."
            );
        }
        if (error.startsWith(PAYMENT_CANCEL_FAILED_FINAL)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Старую T-Bank ссылку не удалось отменить автоматически. Проверьте банк вручную."
            );
        }
        if (!error.startsWith("close_failed") && !error.startsWith("next_order_failed")) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Эту ручную проверку нельзя закрывать повторным автозакрытием заказов. Проверьте причину и используйте ручное разрешение."
            );
        }
    }

    private void ensureAttentionCanBeResolved(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        String error = attentionError(invoice);
        if (isLatePaymentAttention(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Поздний платеж по старой T-Bank ссылке нельзя закрыть без распределения оплаты вручную"
            );
        }
        if (error.startsWith(PAYMENT_CANCEL_FAILED_FINAL)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Старую T-Bank ссылку не удалось отменить автоматически. Проверьте банк вручную."
            );
        }
        if (isPaymentInitManualCheckAttention(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Создание T-Bank ссылки требует ручной сверки банка."
            );
        }

        boolean allItemsPaid = items != null && !items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isPaid);
        if (error.startsWith("close_failed") && !allItemsPaid) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платеж получен, но часть заказов еще не закрыта. Исправьте причину и нажмите \"Повторить\"."
            );
        }
        ensureNoRecordedFullPaymentWithOpenItems(invoice, items);
    }

    private void ensureCommonInvoiceTechnicalTailCanBeResolved(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Можно закрыть только отключенный технический хвост общего счета");
        }
        if (!isResolvableTechnicalTail(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Эту ошибку общего счета нельзя закрыть как технический хвост. Нужно исправить причину."
            );
        }
        boolean hasUnpaidPosition = items != null && items.stream().anyMatch(item -> !item.isPaid());
        if (hasUnpaidPosition) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "В отключенном общем счете остались неоплаченные позиции. Нельзя скрывать его из контроля."
            );
        }
    }

    private boolean isResolvableTechnicalTail(CommonInvoice invoice) {
        String error = attentionError(invoice);
        if (error.isBlank()) {
            return false;
        }
        return RESOLVABLE_TECHNICAL_TAIL_ERROR_PREFIXES.stream().anyMatch(error::startsWith);
    }

    private void ensureNoRecordedFullPaymentWithOpenItems(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        boolean allItemsPaid = items != null && !items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isPaid);
        boolean fullPaymentRecorded = invoice != null
                && invoice.getAmountKopecks() > 0
                && invoice.getPaidKopecks() >= invoice.getAmountKopecks();
        if (fullPaymentRecorded && !allItemsPaid) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У счета зафиксирована полная оплата, но не все позиции закрыты. Нельзя очищать проверку без закрытия заказов."
            );
        }
    }

    private boolean hasAttentionError(CommonInvoice invoice, String prefix) {
        return attentionError(invoice).startsWith(prefix);
    }

    private boolean isLatePaymentAttention(CommonInvoice invoice) {
        String error = attentionError(invoice);
        return error.startsWith("late_tbank_payment") || error.startsWith("late_payment_");
    }

    private boolean isPaymentInitManualCheckAttention(CommonInvoice invoice) {
        String error = attentionError(invoice);
        return error.startsWith(PAYMENT_INIT_STALE)
                || error.startsWith("payment_init_conflict")
                || error.startsWith("payment_init_exception");
    }

    private String attentionError(CommonInvoice invoice) {
        return normalize(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
    }

    private void finishLatePaymentApply(
            CommonInvoice invoice,
            List<CommonInvoicePaymentRef> refs,
            List<CommonInvoiceOrder> items,
            long remainingPaymentKopecks
    ) {
        boolean allPaid = !items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isPaid);
        if (remainingPaymentKopecks > 0) {
            setPaymentRefsStatus(refs, PAYMENT_REF_CONFIRMED);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setLastError(limit(
                    (allPaid ? "late_overpayment: переплата позднего платежа " : "late_payment_unallocated: остаток позднего платежа ")
                            + amountRubles(remainingPaymentKopecks)
                            + (allPaid
                                    ? " руб.; все позиции закрыты, нужна ручная сверка"
                                    : " руб. меньше следующей неоплаченной позиции; нужна ручная сверка"),
                    512
            ));
            invoiceRepository.save(invoice);
            return;
        }

        setPaymentRefsStatus(refs, PAYMENT_REF_APPLIED);
        if (allPaid) {
            closePaidInvoice(invoice, items);
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        invoice.setLastError(null);
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(REMINDER_INTERVAL_DAYS));
        invoiceRepository.save(invoice);
    }

    private void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        List<String> closeFailures = new ArrayList<>();
        for (CommonInvoiceOrder item : items) {
            try {
                if (!item.isPaid()) {
                    closeOrderAsPaidWithoutNextOrder(item.getOrder());
                } else {
                    cleanupPaidOrderAfterCommonBilling(item.getOrder());
                }
                if (!item.isPaid()) {
                    item.setPaid(true);
                    item.setPaidAt(LocalDateTime.now());
                }
                item.setUnpaid(false);
            } catch (Exception e) {
                Long orderId = item.getOrder() == null ? null : item.getOrder().getId();
                closeFailures.add(String.valueOf(orderId));
                log.warn("Не удалось закрыть заказ {} оплатой общего счета {}", orderId, invoice.getId(), e);
            }
        }
        invoiceOrderRepository.saveAll(items);
        invoice.setPaidKopecks(invoice.getAmountKopecks());
        invoice.setPaidAt(LocalDateTime.now());
        invoice.setNextReminderAt(null);
        if (!closeFailures.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setLastError(limit(
                    "close_failed: платеж получен, но заказы не закрылись: " + String.join(", ", closeFailures),
                    512
            ));
            invoiceRepository.save(invoice);
            return;
        }

        invoice.setStatus(CommonInvoiceStatus.PAID);
        invoice.setLastError(null);
        notifyPaymentSuccessIfNeeded(invoice, items);
        invoiceRepository.save(invoice);
        List<String> nextOrderFailures = openNextOrdersIfEnabled(invoice, items);
        if (!nextOrderFailures.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setLastError(limit(
                    "next_order_failed: платеж закрыт, но следующие заказы не создались: " + String.join(", ", nextOrderFailures),
                    512
            ));
            invoiceRepository.save(invoice);
        }
    }

    private void closeOrderAsPaidWithoutNextOrder(Order order) throws Exception {
        orderTransactionService.handlePaymentStatus(order, false);
        cleanupPaidOrderAfterCommonBilling(order);
    }

    private void cleanupPaidOrderAfterCommonBilling(Order order) {
        try {
            manualPaymentAutoConfirmationService.retireOpenLinksForPaidOrder(order);
        } catch (RuntimeException e) {
            log.warn("Не удалось закрыть открытые платежные ссылки заказа {} после оплаты общего счета",
                    order == null ? null : order.getId(), e);
        }
        try {
            paymentInvoiceRetryScheduler.cancelBadReviewAutoBan(order, "Оплата общего счета");
        } catch (RuntimeException e) {
            log.warn("Не удалось отменить авто-бан плохих отзывов заказа {} после оплаты общего счета",
                    order == null ? null : order.getId(), e);
        }
    }

    private List<String> openNextOrdersIfEnabled(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null || invoice.getAccount() == null || !invoice.getAccount().isAutoRepeatOrders()) {
            return List.of();
        }
        List<String> failures = new ArrayList<>();
        for (CommonInvoiceOrder item : items) {
            try {
                nextOrderRequestService.openForPaidOrder(item.getOrder());
            } catch (RuntimeException e) {
                String label = orderFailureLabel(item);
                failures.add(label);
                log.warn("Не удалось создать следующий заказ после полной оплаты общего счета {} для заказа {}",
                        invoice.getId(), item.getOrder() == null ? null : item.getOrder().getId(), e);
                nextOrderFailureNotifier.notifyManager(
                        item.getOrder(),
                        manager(invoice),
                        "полная оплата общего счета #" + invoice.getId(),
                        e
                );
            }
        }
        return failures;
    }

    private void notifyPaymentSuccessIfNeeded(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null || invoice.getPaymentSuccessNotifiedAt() != null) {
            return;
        }
        if (!immediateClientMessagesEnabled()) {
            invoice.setPaymentSuccessNotificationError("immediate_messages_disabled: моментальные клиентские сообщения выключены");
            return;
        }

        try {
            Company company = chatCompany(invoice, items);
            Manager manager = manager(invoice, items);
            ClientMessageSendResult result = messageSender.send(
                    company,
                    manager == null ? null : manager.getClientId(),
                    company == null ? null : company.getGroupId(),
                    paymentSuccessMessage(invoice, items)
            );
            if (result != null && result.sent()) {
                invoice.setPaymentSuccessNotifiedAt(LocalDateTime.now());
                invoice.setPaymentSuccessNotificationError(null);
                log.info("Common invoice payment success notification sent: invoiceId={}, channel={}",
                        invoice.getId(), result.channel());
                return;
            }

            String error = clientMessageError(result);
            invoice.setPaymentSuccessNotificationError(limit(error, 512));
            log.warn("Common invoice payment success notification was not sent: invoiceId={}, error={}",
                    invoice.getId(), error);
        } catch (RuntimeException e) {
            String error = readableException(e);
            invoice.setPaymentSuccessNotificationError(limit(error, 512));
            log.warn("Common invoice payment success notification failed: invoiceId={}", invoice.getId(), e);
        }
    }

    private String paymentSuccessMessage(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        String payerEmail = normalize(invoice.getPayerEmail());
        return new StringBuilder()
                .append("Оплата прошла успешно.")
                .append("\n\nОбщий счет: ").append(invoice.getAccount().getName())
                .append("\nЗаказов: ").append(items == null ? 0 : items.size())
                .append("\nСумма: ").append(money(amountRubles(invoice.getPaidKopecks()))).append(" руб.")
                .append("\nСтраница оплаты: ").append(publicInvoiceUrl(invoice))
                .append("\n\n")
                .append(payerEmail.isBlank()
                        ? "Чек будет отправлен на e-mail."
                        : "Чек будет отправлен на e-mail: " + payerEmail + ".")
                .toString();
    }

    private String clientMessageError(ClientMessageSendResult result) {
        if (result == null) {
            return "notification_result_empty";
        }
        String code = normalize(result.errorCode());
        String message = normalize(result.errorMessage());
        if (code.isBlank()) {
            return message.isBlank() ? "notification_not_sent" : message;
        }
        return message.isBlank() ? code : code + ": " + message;
    }

    private String orderFailureLabel(CommonInvoiceOrder item) {
        return orderFailureLabel(item == null ? null : item.getOrder());
    }

    private String orderFailureLabel(Order order) {
        String companyTitle = companyTitle(order);
        Long orderId = order == null ? null : order.getId();
        return companyTitle + " #" + (orderId == null ? "-" : orderId);
    }

    private String companyTitle(Order order) {
        Company company = order == null ? null : order.getCompany();
        String title = company == null ? "" : normalize(company.getTitle());
        return title.isBlank() ? "компания не указана" : title;
    }

    private void recalculateInvoice(CommonInvoice invoice) {
        recalculateInvoice(invoice, invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId()));
    }

    private void recalculateInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        boolean preserveRecordedAttentionPayment = invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && !hasAttentionError(invoice, "late_tbank_payment")
                && invoice.getAmountKopecks() > 0
                && invoice.getPaidKopecks() >= invoice.getAmountKopecks();
        long recordedPaid = invoice.getPaidKopecks();
        long amount = items.stream().mapToLong(CommonInvoiceOrder::getAmountKopecks).sum();
        long paid = items.stream().filter(CommonInvoiceOrder::isPaid).mapToLong(CommonInvoiceOrder::getAmountKopecks).sum();
        invoice.setAmountKopecks(amount);
        invoice.setPaidKopecks(Math.min(amount, preserveRecordedAttentionPayment ? Math.max(recordedPaid, paid) : paid));
        if (invoice.getStatus() != CommonInvoiceStatus.PAID
                && invoice.getTbankPaymentAmountKopecks() != null
                && invoice.getTbankPaymentAmountKopecks() != remainingKopecks(invoice)) {
            archiveCurrentPaymentRef(invoice, "remaining_changed");
            clearCurrentPaymentRef(invoice);
        }
        if (invoice.getStatus() != CommonInvoiceStatus.PAID
                && invoice.getStatus() != CommonInvoiceStatus.UNPAID
                && invoice.getStatus() != CommonInvoiceStatus.BAN
                && invoice.getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION) {
            if (paid > 0 && paid < amount) {
                invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
            }
        }
        invoiceRepository.save(invoice);
    }

    private void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        boolean changed = false;
        List<String> amountFailures = new ArrayList<>();
        for (CommonInvoiceOrder item : items) {
            if (item.isPaid()) {
                continue;
            }
            long payable;
            try {
                payable = amountKopecks(payableSum(item.getOrder()));
            } catch (AmountCalculationException e) {
                amountFailures.add(orderFailureLabel(item));
                log.warn("Не удалось посчитать сумму общего счета {} для заказа {}",
                        invoice == null ? null : invoice.getId(),
                        item.getOrder() == null ? null : item.getOrder().getId(),
                        e);
                continue;
            }
            if (item.getAmountKopecks() != payable) {
                item.setAmountKopecks(payable);
                changed = true;
            }
            if (!item.isReady() && canMarkCommonInvoiceItemReady(item.getOrder())) {
                item.setReady(true);
                changed = true;
            }
        }
        if (!amountFailures.isEmpty()) {
            markAmountCalculationFailed(invoice, amountFailures);
            return;
        }
        if (changed) {
            invoiceOrderRepository.saveAll(items);
        }
        recalculateInvoice(invoice, items);
        if (invoice != null) {
            if (invoice.getStatus() == CommonInvoiceStatus.COLLECTING && isInvoiceReady(invoice.getId())) {
                invoice.setStatus(CommonInvoiceStatus.READY);
                invoiceRepository.save(invoice);
                markInvoiceOrdersPublished(items);
            } else if (invoice.getStatus() == CommonInvoiceStatus.READY && !allOrdersReady(items)) {
                invoice.setStatus(CommonInvoiceStatus.COLLECTING);
                invoiceRepository.save(invoice);
            }
        }
    }

    private boolean canMarkCommonInvoiceItemReady(Order order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        if (recoveryGateService.hasActiveRecoveryTasks(order.getId())) {
            return false;
        }
        String status = statusTitle(order);
        if (READY_ON_ATTACH_STATUSES.contains(status)) {
            return true;
        }
        return order.getAmount() > 0
                && order.getCounter() >= order.getAmount()
                && !ACTIVE_WORK_STATUSES.contains(status);
    }

    private Long payableKopecksOrMarkAttention(CommonInvoice invoice, Order order) {
        try {
            return amountKopecks(payableSum(order));
        } catch (AmountCalculationException e) {
            log.warn("Не удалось посчитать сумму общего счета {} для заказа {}",
                    invoice == null ? null : invoice.getId(),
                    order == null ? null : order.getId(),
                    e);
            markAmountCalculationFailed(invoice, List.of(orderFailureLabel(order)));
            return null;
        }
    }

    private void markAmountCalculationFailed(CommonInvoice invoice, List<String> amountFailures) {
        if (invoice == null) {
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(
                "amount_calc_failed: не удалось посчитать сумму по заказам: "
                        + String.join(", ", amountFailures == null ? List.of("неизвестный заказ") : amountFailures),
                512
        ));
        invoiceRepository.save(invoice);
    }

    private void setPaymentRefsStatus(List<CommonInvoicePaymentRef> refs, String status) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (CommonInvoicePaymentRef ref : refs) {
            ref.setStatus(status);
        }
        paymentRefRepository.saveAll(refs);
    }

    private boolean isPaymentRefAlreadyHandled(CommonInvoicePaymentRef ref) {
        String status = normalize(ref == null ? null : ref.getStatus()).toUpperCase(Locale.ROOT);
        return PAYMENT_REF_APPLIED.equals(status) || PAYMENT_REF_APPLYING.equals(status);
    }

    private Optional<CommonInvoicePaymentRef> lockedPaymentRef(CommonInvoicePaymentRef ref) {
        if (ref == null || ref.getId() == null) {
            return Optional.ofNullable(ref);
        }
        return paymentRefRepository.findByIdForUpdate(ref.getId());
    }

    private CommonInvoiceDetailsResponse invoiceDetails(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return new CommonInvoiceDetailsResponse(
                toInvoiceSummary(invoice, items),
                items.stream().map(this::toOrderResponse).toList(),
                toOrderCards(items)
        );
    }

    private List<OrderDTOList> toOrderCards(List<CommonInvoiceOrder> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Long> ids = items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(order -> order != null && order.getId() != null)
                .map(Order::getId)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> orderById = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            orderById.put(ids.get(i), i);
        }
        List<OrderDTOList> cards = orderRepository.findOrderListRows(ids).stream()
                .map(orderDtoMapper::toBoardDTO)
                .filter(card -> card != null && card.getId() != null)
                .sorted(Comparator.comparingInt(card -> orderById.getOrDefault(card.getId(), Integer.MAX_VALUE)))
                .toList();
        badReviewTaskService.enrichOrderList(cards);
        return cards;
    }

    private CommonBillingAccountResponse toAccountResponse(
            CommonBillingAccount account,
            List<CommonBillingAccountCompany> companyLinks
    ) {
        CommonInvoiceSummaryResponse current = invoiceRepository.findCurrentForAccount(
                        account.getId(),
                        CURRENT_INVOICE_STATUSES,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst()
                .map(invoice -> {
                    List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
                    refreshInvoiceAmounts(invoice, items);
                    return toInvoiceSummary(invoice, items);
                })
                .orElse(null);
        return new CommonBillingAccountResponse(
                account.getId(),
                account.getName(),
                account.isEnabled(),
                account.isAutoRepeatOrders(),
                account.getManager() == null ? null : account.getManager().getId(),
                managerName(account.getManager()),
                account.getInvoiceCompany() == null ? null : account.getInvoiceCompany().getId(),
                account.getInvoiceCompany() == null ? null : account.getInvoiceCompany().getTitle(),
                companyLinks.stream().map(this::toCompanyResponse).toList(),
                current
        );
    }

    private CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        long remaining = remainingKopecks(invoice);
        return new CommonInvoiceSummaryResponse(
                invoice.getId(),
                invoice.getAccount().getId(),
                invoice.getAccount().getName(),
                invoice.getTitle(),
                invoice.getToken(),
                publicInvoiceUrl(invoice),
                invoice.getStatus().name(),
                items.size(),
                (int) items.stream().filter(CommonInvoiceOrder::isReady).count(),
                (int) items.stream().filter(CommonInvoiceOrder::isPaid).count(),
                amountRubles(invoice.getAmountKopecks()),
                amountRubles(invoice.getPaidKopecks()),
                amountRubles(remaining),
                invoice.getAmountKopecks(),
                invoice.getPaidKopecks(),
                remaining,
                invoice.getSentAt(),
                invoice.getLastReminderAt(),
                invoice.getNextReminderAt(),
                invoice.getLastError(),
                invoice.getPaymentSuccessNotificationError()
        );
    }

    private OrderDTOList toManagerBoardCard(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        CommonInvoiceSummaryResponse summary = toInvoiceSummary(invoice, items);
        BadReviewTaskSummary badReviewSummary = aggregateBadReviewSummary(items);
        Company company = chatCompany(invoice);
        LocalDate changed = invoice.getUpdatedAt() == null ? LocalDate.now() : invoice.getUpdatedAt().toLocalDate();
        return OrderDTOList.builder()
                .id(-invoice.getId())
                .companyId(company == null ? firstCompanyId(items) : company.getId())
                .companyTitle(invoice.getAccount().getName())
                .companyComments(company == null ? "" : normalize(company.getCommentsCompany()))
                .filialTitle("Общий счет: " + items.size() + " заказов")
                .filialUrl(summary.publicUrl())
                .filialCity("")
                .status(boardStatus(invoice, items))
                .sum(summary.remaining())
                .totalSumWithBadReviews(summary.remaining())
                .badReviewTasksSum(badReviewSummary.doneSum())
                .badReviewTasksTotal(badReviewSummary.total())
                .badReviewTasksPending(badReviewSummary.pending())
                .badReviewTasksDone(badReviewSummary.done())
                .badReviewTasksCanceled(badReviewSummary.canceled())
                .companyUrlChat(company == null ? "" : normalize(company.getUrlChat()))
                .companyTelephone(company == null ? "" : normalize(company.getTelephone()))
                .managerPayText(manager(invoice) == null ? "" : normalize(manager(invoice).getPayText()))
                .amount(summary.totalOrders())
                .counter(summary.readyOrders())
                .waitingForClient(false)
                .firstOrderForCompany(false)
                .workerUserFio("Общий счет")
                .categoryTitle("Общий счет")
                .subCategoryTitle(companyCountLabel(items))
                .created(invoice.getCreatedAt() == null ? null : invoice.getCreatedAt().toLocalDate())
                .changed(changed)
                .payDay(null)
                .dayToChangeStatusAgo(Math.max(0, ChronoUnit.DAYS.between(changed, LocalDate.now())))
                .orderComments(commonInvoiceNote(summary))
                .commonInvoice(true)
                .commonInvoiceId(invoice.getId())
                .commonBillingAccountId(invoice.getAccount().getId())
                .commonInvoiceStatus(invoice.getStatus().name())
                .commonInvoicePublicUrl(summary.publicUrl())
                .commonInvoiceTotalOrders(summary.totalOrders())
                .commonInvoiceReadyOrders(summary.readyOrders())
                .commonInvoicePaidOrders(summary.paidOrders())
                .commonInvoiceAmount(summary.amount())
                .commonInvoicePaid(summary.paid())
                .commonInvoiceRemaining(summary.remaining())
                .commonInvoiceSentAt(summary.sentAt())
                .commonInvoiceLastReminderAt(summary.lastReminderAt())
                .commonInvoiceNextReminderAt(summary.nextReminderAt())
                .commonInvoiceLastError(summary.lastError())
                .build();
    }

    private BadReviewTaskSummary aggregateBadReviewSummary(List<CommonInvoiceOrder> items) {
        List<Long> orderIds = items == null ? List.of() : items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(order -> order != null && order.getId() != null)
                .map(Order::getId)
                .toList();
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

    private Comparator<CommonInvoice> boardInvoiceComparator(boolean ascending) {
        Comparator<CommonInvoice> comparator = Comparator
                .comparing((CommonInvoice invoice) -> Optional.ofNullable(invoice.getUpdatedAt()).orElse(LocalDateTime.MIN))
                .thenComparing(CommonInvoice::getId);
        return ascending ? comparator.reversed() : comparator;
    }

    private boolean matchesBoardStatus(CommonInvoice invoice, List<CommonInvoiceOrder> items, String boardStatus) {
        return boardStatus.isBlank() || "Все".equals(boardStatus) || boardStatus(invoice, items).equals(boardStatus);
    }

    private boolean matchesBoardCompany(List<CommonInvoiceOrder> items, Long companyId) {
        if (companyId == null) {
            return true;
        }
        return items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .map(Order::getCompany)
                .filter(company -> company != null && company.getId() != null)
                .anyMatch(company -> companyId.equals(company.getId()));
    }

    private boolean matchesLinkedOrderStatus(CommonInvoiceOrder item, String orderStatus) {
        return orderStatus.isBlank() || "Все".equals(orderStatus) || orderStatus.equals(statusTitle(item.getOrder()));
    }

    private boolean matchesLinkedOrderCompany(CommonInvoiceOrder item, Long companyId) {
        if (companyId == null) {
            return true;
        }
        Order order = item.getOrder();
        Company company = order == null ? null : order.getCompany();
        return company != null && companyId.equals(company.getId());
    }

    private boolean matchesLinkedOrderKeyword(CommonInvoiceOrder item, String keyword) {
        if (keyword.isBlank()) {
            return true;
        }
        Order order = item.getOrder();
        Company company = order == null ? null : order.getCompany();
        return containsKeyword(order == null ? "" : String.valueOf(order.getId()), keyword)
                || containsKeyword(company == null ? "" : company.getTitle(), keyword)
                || containsKeyword(order == null || order.getFilial() == null ? "" : order.getFilial().getTitle(), keyword);
    }

    private boolean matchesBoardKeyword(CommonInvoice invoice, List<CommonInvoiceOrder> items, String keyword) {
        if (keyword.isBlank()) {
            return true;
        }
        if (containsKeyword(invoice.getAccount().getName(), keyword)
                || containsKeyword(invoice.getTitle(), keyword)
                || containsKeyword(String.valueOf(invoice.getId()), keyword)) {
            return true;
        }
        return items.stream().anyMatch(item -> {
            Order order = item.getOrder();
            Company company = order == null ? null : order.getCompany();
            return containsKeyword(order == null ? "" : String.valueOf(order.getId()), keyword)
                    || containsKeyword(company == null ? "" : company.getTitle(), keyword)
                    || containsKeyword(order == null || order.getFilial() == null ? "" : order.getFilial().getTitle(), keyword);
        });
    }

    private boolean containsKeyword(String value, String keyword) {
        return normalize(value).toLowerCase(Locale.ROOT).contains(keyword);
    }

    private boolean visibleToManager(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> visibleManagerIds) {
        if (visibleManagerIds == null) {
            return true;
        }
        if (visibleManagerIds.isEmpty()) {
            return false;
        }
        Manager accountManager = invoice.getAccount().getManager();
        if (accountManager != null && visibleManagerIds.contains(accountManager.getId())) {
            return true;
        }
        return items != null && !items.isEmpty() && items.stream()
                .map(item -> item.getOrder() == null ? null : item.getOrder().getManager())
                .allMatch(manager -> manager != null
                        && manager.getId() != null
                        && visibleManagerIds.contains(manager.getId()));
    }

    private boolean accountVisibleToManager(
            CommonBillingAccount account,
            List<CommonBillingAccountCompany> companyLinks,
            Set<Long> visibleManagerIds
    ) {
        if (visibleManagerIds == null) {
            return true;
        }
        if (visibleManagerIds.isEmpty() || account == null) {
            return false;
        }
        Manager accountManager = account.getManager();
        if (accountManager != null && accountManager.getId() != null && visibleManagerIds.contains(accountManager.getId())) {
            return true;
        }
        List<CommonBillingAccountCompany> enabledLinks = companyLinks == null
                ? List.of()
                : companyLinks.stream()
                .filter(CommonBillingAccountCompany::isEnabled)
                .toList();
        return !enabledLinks.isEmpty() && enabledLinks.stream()
                .map(CommonBillingAccountCompany::getCompany)
                .map(company -> company == null ? null : company.getManager())
                .allMatch(manager -> manager != null
                        && manager.getId() != null
                        && visibleManagerIds.contains(manager.getId()));
    }

    private void ensureAccountVisibleForCurrentUser(CommonBillingAccount account) {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        if (visibleManagerIds == null) {
            return;
        }
        List<CommonBillingAccountCompany> companyLinks = account == null || account.getId() == null
                ? List.of()
                : accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(account.getId());
        if (!accountVisibleToManager(account, companyLinks, visibleManagerIds)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Общий плательщик недоступен текущему пользователю");
        }
    }

    private void ensureAccountRequestVisibleForCurrentUser(
            CommonBillingAccount account,
            List<Long> companyIds,
            boolean requireVisibleAnchor
    ) {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        if (visibleManagerIds == null) {
            return;
        }
        if (visibleManagerIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Общий плательщик недоступен текущему пользователю");
        }

        boolean visibleAnchor = false;
        Manager accountManager = account == null ? null : account.getManager();
        if (accountManager != null) {
            ensureManagerVisible(accountManager, visibleManagerIds);
            visibleAnchor = true;
        }

        Company invoiceCompany = account == null ? null : account.getInvoiceCompany();
        if (invoiceCompany != null) {
            ensureCompanyVisible(invoiceCompany, visibleManagerIds);
        }

        for (Long companyId : safeCompanyIds(companyIds)) {
            Company company = companyRepository.findById(companyId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
            ensureCompanyVisible(company, visibleManagerIds);
            visibleAnchor = true;
        }

        if (requireVisibleAnchor && !visibleAnchor) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Укажите доступного менеджера или доступную компанию для общего плательщика"
            );
        }
    }

    private Set<Long> safeCompanyIds(List<Long> companyIds) {
        if (companyIds == null) {
            return Set.of();
        }
        return companyIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
    }

    private void ensureCompanyVisibleForCurrentUser(Company company) {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        if (visibleManagerIds == null) {
            return;
        }
        ensureCompanyVisible(company, visibleManagerIds);
    }

    private void ensureManagerVisible(Manager manager, Set<Long> visibleManagerIds) {
        if (manager == null || manager.getId() == null || !visibleManagerIds.contains(manager.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер недоступен текущему пользователю");
        }
    }

    private void ensureCompanyVisible(Company company, Set<Long> visibleManagerIds) {
        Manager manager = company == null ? null : company.getManager();
        if (manager == null || manager.getId() == null || !visibleManagerIds.contains(manager.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Компания недоступна текущему пользователю");
        }
    }

    private void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        if (visibleManagerIds == null) {
            return;
        }
        List<CommonInvoiceOrder> items = invoice == null || invoice.getId() == null
                ? List.of()
                : invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        if (!visibleToManager(invoice, items, visibleManagerIds)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Общий счет недоступен текущему пользователю");
        }
    }

    private boolean currentUserCanForceBan() {
        Authentication authentication = currentAuthentication();
        return managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER");
    }

    private Set<Long> visibleManagerIdsForCurrentUser() {
        Authentication authentication = currentAuthentication();
        if (authentication == null) {
            return null;
        }
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return null;
        }
        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            return userService.findManagersByUserName(authentication.getName()).stream()
                    .map(Manager::getId)
                    .filter(id -> id != null)
                    .collect(Collectors.toSet());
        }
        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            return userService.findByUserName(authentication.getName())
                    .flatMap(user -> managerRepository.findByUserId(user.getId()))
                    .map(Manager::getId)
                    .map(Set::of)
                    .orElse(Set.of());
        }
        return Set.of();
    }

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext() == null ? null : SecurityContextHolder.getContext().getAuthentication();
    }

    private boolean itemVisibleInOrderMetrics(CommonInvoiceOrder item, Set<Long> visibleManagerIds) {
        if (visibleManagerIds == null) {
            return true;
        }
        Manager manager = item.getOrder() == null ? null : item.getOrder().getManager();
        return manager != null && manager.getId() != null && visibleManagerIds.contains(manager.getId());
    }

    private CommonInvoiceStatus effectiveInvoiceStatus(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        CommonInvoiceStatus status = invoice.getStatus();
        if (status != CommonInvoiceStatus.COLLECTING && status != CommonInvoiceStatus.READY) {
            return status;
        }

        return allOrdersReady(items) ? CommonInvoiceStatus.READY : CommonInvoiceStatus.COLLECTING;
    }

    private boolean allOrdersReady(List<CommonInvoiceOrder> items) {
        return items != null
                && !items.isEmpty()
                && items.stream().allMatch(CommonInvoiceOrder::isReady)
                && !hasActiveRecovery(items);
    }

    private boolean hasActiveRecovery(List<CommonInvoiceOrder> items) {
        return items != null && items.stream().anyMatch(this::hasActiveRecovery);
    }

    private boolean hasActiveRecovery(CommonInvoiceOrder item) {
        Order order = item == null ? null : item.getOrder();
        return order != null && order.getId() != null && recoveryGateService.hasActiveRecoveryTasks(order.getId());
    }

    private void postponeInvoiceForRecovery(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        invoice.setLastError("review_recovery_active: есть активные задачи восстановления отзывов");
        invoiceRepository.save(invoice);
    }

    private String boardStatus(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return switch (effectiveInvoiceStatus(invoice, items)) {
            case COLLECTING -> STATUS_WAITING_COMMON_INVOICE;
            case READY -> STATUS_PUBLIC;
            case INVOICED -> STATUS_TO_PAY;
            case REMINDER, PARTIALLY_PAID -> STATUS_REMINDER;
            case NEEDS_ATTENTION -> STATUS_NEEDS_ATTENTION;
            case UNPAID -> STATUS_NOT_PAID;
            case BAN -> STATUS_BAN;
            case PAID -> "Оплачено";
            case DISABLED -> "Архив";
        };
    }

    private Long firstCompanyId(List<CommonInvoiceOrder> items) {
        return items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .map(Order::getCompany)
                .filter(company -> company != null && company.getId() != null)
                .map(Company::getId)
                .findFirst()
                .orElse(null);
    }

    private String companyCountLabel(List<CommonInvoiceOrder> items) {
        long count = items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .map(Order::getCompany)
                .filter(company -> company != null && company.getId() != null)
                .map(Company::getId)
                .distinct()
                .count();
        return count + " компаний";
    }

    private String commonInvoiceNote(CommonInvoiceSummaryResponse summary) {
        return "Готово " + summary.readyOrders() + "/" + summary.totalOrders()
                + ", оплачено " + summary.paidOrders() + "/" + summary.totalOrders();
    }

    private CommonBillingCompanyResponse toCompanyResponse(CommonBillingAccountCompany link) {
        return new CommonBillingCompanyResponse(
                link.getCompany().getId(),
                link.getCompany().getTitle(),
                link.isEnabled()
        );
    }

    private CommonInvoiceOrderResponse toOrderResponse(CommonInvoiceOrder item) {
        Order order = item.getOrder();
        Company company = order.getCompany();
        return new CommonInvoiceOrderResponse(
                order.getId(),
                company == null ? null : company.getId(),
                company == null ? "" : company.getTitle(),
                order.getFilial() == null ? "" : order.getFilial().getTitle(),
                statusTitle(order),
                normalize(item.getOriginalOrderStatusTitle()),
                amountRubles(item.getAmountKopecks()),
                item.getAmountKopecks(),
                item.isReady(),
                item.isPaid(),
                item.isUnpaid(),
                item.getInvoice().getStatus() != CommonInvoiceStatus.PAID
                        && item.getInvoice().getStatus() != CommonInvoiceStatus.UNPAID
                        && item.getInvoice().getStatus() != CommonInvoiceStatus.BAN
                        && item.getInvoice().getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION,
                item.getPaidAt()
        );
    }

    private String invoiceMessage(CommonInvoice invoice, List<CommonInvoiceOrder> items, boolean reminder) {
        StringBuilder builder = new StringBuilder();
        builder.append(invoice.getAccount().getName()).append("\n\n");
        builder.append(reminder ? "Напоминаем об оплате общего счета." : "Все заказы из общего счета выполнены.");
        builder.append("\n\nЗаказов: ").append(items.size());
        builder.append("\nК оплате: ").append(money(amountRubles(remainingKopecks(invoice)))).append(" руб.");
        builder.append("\nСсылка на оплату: ").append(publicInvoiceUrl(invoice));
        builder.append("\n\nСостав:");
        items.stream().limit(12).forEach(item -> builder
                .append("\n- №").append(item.getOrder().getId())
                .append(" ")
                .append(item.getOrder().getCompany() == null ? "" : item.getOrder().getCompany().getTitle())
                .append(item.getOrder().getFilial() == null ? "" : " / " + item.getOrder().getFilial().getTitle())
                .append(" - ").append(money(amountRubles(item.getAmountKopecks()))).append(" руб."));
        if (items.size() > 12) {
            builder.append("\n- еще ").append(items.size() - 12).append(" заказов");
        }
        return builder.toString();
    }

    private Company chatCompany(CommonInvoice invoice) {
        return chatCompany(invoice, null);
    }

    private Company chatCompany(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice.getAccount().getInvoiceCompany() != null) {
            return invoice.getAccount().getInvoiceCompany();
        }
        List<CommonInvoiceOrder> resolvedItems = items == null
                ? invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId())
                : items;
        return resolvedItems.stream()
                .map(CommonInvoiceOrder::getOrder)
                .map(Order::getCompany)
                .filter(c -> c != null)
                .findFirst()
                .orElse(null);
    }

    private Manager manager(CommonInvoice invoice) {
        return manager(invoice, null);
    }

    private Manager manager(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice.getAccount().getManager() != null) {
            return invoice.getAccount().getManager();
        }
        Company company = chatCompany(invoice, items);
        return company == null ? null : company.getManager();
    }

    private boolean immediateClientMessagesEnabled() {
        return appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true);
    }

    private void restoreDetachedOrderStatus(Order order, String originalStatus) {
        if (order == null || order.getId() == null) {
            return;
        }
        String targetStatus = normalize(originalStatus);
        if (targetStatus.isBlank() || STATUS_WAITING_COMMON_INVOICE.equals(targetStatus)) {
            targetStatus = STATUS_PUBLIC;
        }
        order.setStatus(orderStatusService.getOrderStatusByTitle(targetStatus));
        orderRepository.save(order);
    }

    private void markOrderWaitingCommonInvoice(Order order) {
        if (order == null || STATUS_WAITING_COMMON_INVOICE.equals(statusTitle(order))) {
            return;
        }
        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_WAITING_COMMON_INVOICE));
        orderRepository.save(order);
    }

    private void markInvoiceOrdersPublished(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        markInvoiceOrdersPublished(invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId));
    }

    private void markInvoiceOrdersPublished(List<CommonInvoiceOrder> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        OrderStatus publicStatus = orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC);
        for (CommonInvoiceOrder item : items) {
            Order order = item == null ? null : item.getOrder();
            if (order == null || STATUS_PUBLIC.equals(statusTitle(order))) {
                continue;
            }
            order.setStatus(publicStatus);
            orderRepository.save(order);
        }
    }

    private void markInvoiceOrdersToPay(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        markInvoiceOrdersToStatus(invoiceId, STATUS_TO_PAY);
    }

    private void markInvoiceOrdersReminder(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        markInvoiceOrdersToStatus(invoiceId, STATUS_REMINDER);
    }

    private void markInvoiceOrdersToStatus(Long invoiceId, String status) {
        List<String> failures = new ArrayList<>();
        for (CommonInvoiceOrder item : invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId)) {
            Order order = item == null ? null : item.getOrder();
            if (order == null || order.getId() == null || status.equals(statusTitle(order))) {
                continue;
            }
            try {
                orderStatusTransitionService.changeStatusForCommonBillingOrder(order.getId(), status);
            } catch (Exception e) {
                failures.add(orderFailureLabel(item));
                log.warn("Не удалось перевести заказ {} из общего счета {} в {}",
                        order.getId(), invoiceId, status, e);
            }
        }
        if (!failures.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не все заказы общего счета удалось перевести в " + status + ": " + String.join(", ", failures)
            );
        }
    }

    private BigDecimal payableSum(Order order) {
        try {
            return badReviewTaskService.getPayableSum(order);
        } catch (RuntimeException e) {
            throw new AmountCalculationException(order == null ? null : order.getId(), e);
        }
    }

    private long amountKopecks(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount)
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValue();
    }

    private BigDecimal amountRubles(long kopecks) {
        return BigDecimal.valueOf(kopecks, 2);
    }

    private long remainingKopecks(CommonInvoice invoice) {
        return Math.max(0, invoice.getAmountKopecks() - invoice.getPaidKopecks());
    }

    private String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    private String statusTitle(Order order) {
        return order == null || order.getStatus() == null || order.getStatus().getTitle() == null
                ? ""
                : order.getStatus().getTitle();
    }

    private String publicInvoiceUrl(CommonInvoice invoice) {
        return trimTrailingSlash(properties.getPublicBaseUrl()) + "/pay/group/" + invoice.getToken();
    }

    private String trimTrailingSlash(String value) {
        String result = normalize(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String cleanToken(String token) {
        String clean = normalize(token);
        if (clean.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден");
        }
        return clean;
    }

    private String groupTbankOrderId(CommonInvoice invoice) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return ("g" + invoice.getId() + "-" + suffix).substring(0, Math.min(36, ("g" + invoice.getId() + "-" + suffix).length()));
    }

    private void archiveCurrentPaymentRef(CommonInvoice invoice, String reason) {
        if (invoice == null
                || (normalize(invoice.getTbankOrderId()).isBlank() && normalize(invoice.getTbankPaymentId()).isBlank())) {
            return;
        }
        if (!normalize(invoice.getTbankOrderId()).isBlank()
                && paymentRefRepository.findByTbankOrderId(invoice.getTbankOrderId()).isPresent()) {
            return;
        }
        if (!normalize(invoice.getTbankPaymentId()).isBlank()
                && paymentRefRepository.findByTbankPaymentId(invoice.getTbankPaymentId()).isPresent()) {
            return;
        }

        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setTbankOrderId(normalize(invoice.getTbankOrderId()).isBlank() ? null : invoice.getTbankOrderId());
        ref.setTbankPaymentId(normalize(invoice.getTbankPaymentId()).isBlank() ? null : invoice.getTbankPaymentId());
        ref.setTbankTerminalKey(normalize(invoice.getTbankTerminalKey()).isBlank() ? null : invoice.getTbankTerminalKey());
        ref.setAmountKopecks(invoice.getTbankPaymentAmountKopecks());
        ref.setStatus(canCancelCurrentPaymentRef(invoice) ? PAYMENT_REF_CANCEL_PENDING : PAYMENT_REF_ARCHIVED);
        ref.setReason(limit(reason, 160));
        paymentRefRepository.save(ref);
    }

    private boolean canCancelCurrentPaymentRef(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() != CommonInvoiceStatus.PAID
                && !normalize(invoice.getTbankPaymentId()).isBlank()
                && !normalize(invoice.getTbankTerminalKey()).isBlank()
                && invoice.getTbankPaymentAmountKopecks() != null
                && invoice.getTbankPaymentAmountKopecks() > 0;
    }

    private PreparedArchivedPaymentCancel prepareArchivedPaymentCancel(Long refId) {
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(refId).orElse(null);
        String status = normalize(ref == null ? null : ref.getStatus());
        if (ref == null
                || (!PAYMENT_REF_CANCEL_PENDING.equals(status)
                && !PAYMENT_REF_CANCEL_FAILED.equals(status)
                && !PAYMENT_REF_INIT_CONFLICT.equals(status)
                && !PAYMENT_REF_CANCELING.equals(status))) {
            return null;
        }
        if (PAYMENT_REF_CANCELING.equals(status) && !isStaleArchivedPaymentCancel(ref)) {
            return null;
        }
        if ((PAYMENT_REF_CANCEL_FAILED.equals(status) || PAYMENT_REF_CANCELING.equals(status))
                && cancelAttempts(ref) >= PAYMENT_REF_CANCEL_MAX_ATTEMPTS) {
            markArchivedPaymentCancelFailedFinal(ref);
            return null;
        }
        if (ref.getInvoice() != null && ref.getInvoice().getStatus() == CommonInvoiceStatus.PAID) {
            ref.setStatus(PAYMENT_REF_ARCHIVED);
            ref.setReason(limit("paid_invoice_cancel_skipped", 160));
            paymentRefRepository.save(ref);
            log.warn("Автоотмена архивной T-Bank ссылки общего счета пропущена: ref={}, invoice={} уже PAID",
                    ref.getId(), ref.getInvoice().getId());
            return null;
        }
        String paymentId = normalize(ref.getTbankPaymentId());
        String terminalKey = normalize(ref.getTbankTerminalKey());
        Long amount = ref.getAmountKopecks();
        if (paymentId.isBlank() || terminalKey.isBlank() || amount == null || amount <= 0) {
            ref.setStatus(PAYMENT_REF_ARCHIVED);
            paymentRefRepository.save(ref);
            return null;
        }
        ref.setStatus(PAYMENT_REF_CANCELING);
        ref.setCancelAttempts(cancelAttempts(ref) + 1);
        paymentRefRepository.save(ref);
        return new PreparedArchivedPaymentCancel(ref.getId(), paymentId, terminalKey, amount);
    }

    private String cancelArchivedPayment(PreparedArchivedPaymentCancel prepared) {
        try {
            Optional<PaymentProfile> profile = paymentProfileService.findByTerminalKey(prepared.terminalKey());
            if (profile.isEmpty()) {
                return PAYMENT_REF_CANCEL_FAILED;
            }
            TbankPaymentProfile runtimeProfile = paymentProfileService.toRuntimeForTerminal(profile.get(), prepared.terminalKey());
            if (!runtimeProfile.hasCredentials()) {
                return PAYMENT_REF_CANCEL_FAILED;
            }
            TbankCancelResponse response = tbankClient.cancel(
                    runtimeProfile,
                    new TbankCancelCommand(prepared.paymentId(), prepared.amountKopecks())
            );
            if (response.success()) {
                return PAYMENT_REF_CANCELED;
            }
            log.warn("T-Bank Cancel для архивной ссылки общего счета ref={} вернул отказ: {}",
                    prepared.refId(), response.errorText());
            return PAYMENT_REF_CANCEL_FAILED;
        } catch (RuntimeException e) {
            log.warn("Не удалось отменить архивную T-Bank ссылку общего счета ref={}", prepared.refId(), e);
            return PAYMENT_REF_CANCEL_FAILED;
        }
    }

    private void finishArchivedPaymentCancel(Long refId, String status) {
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(refId).orElse(null);
        if (ref == null || !PAYMENT_REF_CANCELING.equals(normalize(ref.getStatus()))) {
            return;
        }
        String normalizedStatus = normalize(status);
        if (PAYMENT_REF_CANCEL_FAILED.equals(normalizedStatus)
                && cancelAttempts(ref) >= PAYMENT_REF_CANCEL_MAX_ATTEMPTS) {
            normalizedStatus = PAYMENT_REF_CANCEL_FAILED_FINAL;
        }
        ref.setStatus(limit(normalizedStatus, 32));
        paymentRefRepository.save(ref);
        if (PAYMENT_REF_CANCEL_FAILED_FINAL.equals(normalizedStatus)) {
            markInvoiceNeedsAttentionForFinalCancelFailure(ref);
        }
    }

    private void markArchivedPaymentCancelFailedFinal(CommonInvoicePaymentRef ref) {
        if (ref == null) {
            return;
        }
        ref.setStatus(PAYMENT_REF_CANCEL_FAILED_FINAL);
        paymentRefRepository.save(ref);
        markInvoiceNeedsAttentionForFinalCancelFailure(ref);
    }

    private void markInvoiceNeedsAttentionForFinalCancelFailure(CommonInvoicePaymentRef ref) {
        CommonInvoice refInvoice = ref == null ? null : ref.getInvoice();
        Long invoiceId = refInvoice == null ? null : refInvoice.getId();
        CommonInvoice invoice = invoiceId == null
                ? refInvoice
                : lockedInvoice(invoiceId).orElse(refInvoice);
        if (invoice == null) {
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(
                PAYMENT_CANCEL_FAILED_FINAL + ": старая T-Bank ссылка "
                        + paymentRefLabel(ref)
                        + " не отменена после " + cancelAttempts(ref)
                        + " попыток; проверьте банк вручную",
                512
        ));
        invoiceRepository.save(invoice);
    }

    private void archiveAndClearCurrentPaymentRef(CommonInvoice invoice, String reason) {
        archiveCurrentPaymentRef(invoice, reason);
        clearCurrentPaymentRef(invoice);
    }

    private void recordCurrentPaymentRef(CommonInvoice invoice, String status, String reason) {
        if (invoice == null
                || (normalize(invoice.getTbankOrderId()).isBlank() && normalize(invoice.getTbankPaymentId()).isBlank())) {
            return;
        }
        if (!normalize(invoice.getTbankOrderId()).isBlank()
                && paymentRefRepository.findByTbankOrderId(invoice.getTbankOrderId()).isPresent()) {
            clearCurrentPaymentRef(invoice);
            return;
        }
        if (!normalize(invoice.getTbankPaymentId()).isBlank()
                && paymentRefRepository.findByTbankPaymentId(invoice.getTbankPaymentId()).isPresent()) {
            clearCurrentPaymentRef(invoice);
            return;
        }

        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setTbankOrderId(normalize(invoice.getTbankOrderId()).isBlank() ? null : invoice.getTbankOrderId());
        ref.setTbankPaymentId(normalize(invoice.getTbankPaymentId()).isBlank() ? null : invoice.getTbankPaymentId());
        ref.setTbankTerminalKey(normalize(invoice.getTbankTerminalKey()).isBlank() ? null : invoice.getTbankTerminalKey());
        ref.setAmountKopecks(invoice.getTbankPaymentAmountKopecks());
        ref.setStatus(limit(status, 32));
        ref.setReason(limit(reason, 160));
        paymentRefRepository.save(ref);
        clearCurrentPaymentRef(invoice);
    }

    private void recordInitializedPaymentRef(
            CommonInvoice invoice,
            PreparedCommonPaymentInit prepared,
            TbankInitResponse response,
            String reason
    ) {
        if (invoice == null || prepared == null || response == null) {
            return;
        }
        String orderId = normalize(prepared.tbankOrderId());
        String paymentId = normalize(response.paymentId());
        if (orderId.isBlank() && paymentId.isBlank()) {
            return;
        }
        if (!orderId.isBlank() && paymentRefRepository.findByTbankOrderId(orderId).isPresent()) {
            return;
        }
        if (!paymentId.isBlank() && paymentRefRepository.findByTbankPaymentId(paymentId).isPresent()) {
            return;
        }

        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setTbankOrderId(orderId.isBlank() ? null : orderId);
        ref.setTbankPaymentId(paymentId.isBlank() ? null : paymentId);
        ref.setTbankTerminalKey(prepared.runtimeProfile() == null ? null : prepared.runtimeProfile().terminalKey());
        ref.setAmountKopecks(prepared.remainingKopecks() > 0 ? prepared.remainingKopecks() : response.amount());
        ref.setStatus(canCancelInitializedPaymentRef(ref) ? PAYMENT_REF_CANCEL_PENDING : PAYMENT_REF_INIT_CONFLICT);
        ref.setReason(limit(reason, 160));
        paymentRefRepository.save(ref);
    }

    private void recordPreparedPaymentInitRef(
            CommonInvoice invoice,
            PreparedCommonPaymentInit prepared,
            String reason
    ) {
        if (invoice == null || prepared == null) {
            return;
        }
        String orderId = normalize(prepared.tbankOrderId());
        if (orderId.isBlank() || paymentRefRepository.findByTbankOrderId(orderId).isPresent()) {
            return;
        }

        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setTbankOrderId(orderId);
        ref.setTbankTerminalKey(prepared.runtimeProfile() == null ? null : prepared.runtimeProfile().terminalKey());
        ref.setAmountKopecks(prepared.remainingKopecks() > 0 ? prepared.remainingKopecks() : null);
        ref.setStatus(PAYMENT_REF_INIT_CONFLICT);
        ref.setReason(limit(reason, 160));
        paymentRefRepository.save(ref);
    }

    private int cancelAttempts(CommonInvoicePaymentRef ref) {
        Integer attempts = ref == null ? null : ref.getCancelAttempts();
        return attempts == null ? 0 : Math.max(0, attempts);
    }

    private boolean isStaleArchivedPaymentCancel(CommonInvoicePaymentRef ref) {
        LocalDateTime updatedAt = ref == null ? null : ref.getUpdatedAt();
        return updatedAt != null && !updatedAt.plus(PAYMENT_REF_CANCELING_TIMEOUT).isAfter(LocalDateTime.now());
    }

    private boolean canCancelInitializedPaymentRef(CommonInvoicePaymentRef ref) {
        return ref != null
                && !normalize(ref.getTbankPaymentId()).isBlank()
                && !normalize(ref.getTbankTerminalKey()).isBlank()
                && ref.getAmountKopecks() != null
                && ref.getAmountKopecks() > 0;
    }

    private void clearCurrentPaymentRef(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        invoice.setPaymentUrl(null);
        invoice.setTbankOrderId(null);
        invoice.setTbankPaymentId(null);
        invoice.setTbankTerminalKey(null);
        invoice.setTbankPaymentAmountKopecks(null);
        invoice.setTbankPaymentCreatedAt(null);
    }

    private VerifiedWebhookProfile verifyWebhook(Map<String, String> payload) {
        String terminalKey = normalize(payload.get("TerminalKey"));
        if (terminalKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TerminalKey не передан");
        }
        var profile = paymentProfileService.findByTerminalKey(terminalKey)
                .orElseGet(() -> paymentProfileService.defaultEntityProfile());
        TbankPaymentProfile runtimeProfile = paymentProfileService.toRuntimeForTerminal(profile, terminalKey);
        if (!runtimeProfile.hasCredentials()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не заданы TerminalKey или Password Т-Банка");
        }
        if (!tokenSigner.matches(payload, runtimeProfile.password(), payload.get("Token"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная подпись уведомления Т-Банка");
        }
        return new VerifiedWebhookProfile(runtimeProfile);
    }

    private void validateWebhookTerminal(CommonInvoice invoice, TbankPaymentProfile runtimeProfile) {
        String invoiceTerminal = normalize(invoice.getTbankTerminalKey());
        if (!invoiceTerminal.isBlank() && !invoiceTerminal.equals(runtimeProfile.terminalKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TerminalKey webhook не совпадает с общим счетом");
        }
    }

    private boolean matchesCurrentPaymentRef(CommonInvoice invoice, String orderId, String paymentId) {
        String invoiceOrderId = normalize(invoice == null ? null : invoice.getTbankOrderId());
        String invoicePaymentId = normalize(invoice == null ? null : invoice.getTbankPaymentId());
        boolean orderProvided = !normalize(orderId).isBlank();
        boolean paymentProvided = !normalize(paymentId).isBlank();
        boolean orderMatches = orderProvided && !invoiceOrderId.isBlank() && invoiceOrderId.equals(orderId);
        boolean paymentMatches = paymentProvided && !invoicePaymentId.isBlank() && invoicePaymentId.equals(paymentId);
        if (!orderMatches && !paymentMatches) {
            return false;
        }
        if (orderProvided && !invoiceOrderId.isBlank() && !invoiceOrderId.equals(orderId)) {
            return false;
        }
        return !paymentProvided || invoicePaymentId.isBlank() || invoicePaymentId.equals(paymentId);
    }

    private void validateArchivedWebhookTerminal(CommonInvoicePaymentRef ref, TbankPaymentProfile runtimeProfile) {
        String refTerminal = normalize(ref.getTbankTerminalKey());
        if (!refTerminal.isBlank() && !refTerminal.equals(runtimeProfile.terminalKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TerminalKey webhook не совпадает с архивной ссылкой общего счета");
        }
    }

    private void validateWebhookAmount(CommonInvoice invoice, Map<String, String> payload) {
        String amount = normalize(payload.get("Amount"));
        if (amount.isBlank() || invoice.getTbankPaymentAmountKopecks() == null) {
            return;
        }
        try {
            long webhookAmount = Long.parseLong(amount);
            if (webhookAmount != invoice.getTbankPaymentAmountKopecks()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сумма webhook не совпадает с общим счетом");
            }
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная сумма webhook", e);
        }
    }

    private void validateArchivedWebhookAmount(CommonInvoicePaymentRef ref, Map<String, String> payload) {
        String amount = normalize(payload.get("Amount"));
        if (amount.isBlank() || ref.getAmountKopecks() == null) {
            return;
        }
        try {
            long webhookAmount = Long.parseLong(amount);
            if (webhookAmount != ref.getAmountKopecks()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сумма webhook не совпадает с архивной ссылкой общего счета");
            }
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная сумма webhook", e);
        }
    }

    private String paymentRefLabel(CommonInvoicePaymentRef ref) {
        return paymentRefLabel(ref == null ? null : ref.getTbankOrderId(), ref == null ? null : ref.getTbankPaymentId());
    }

    private String paymentRefLabel(String tbankOrderId, String tbankPaymentId) {
        String orderId = normalize(tbankOrderId);
        String paymentId = normalize(tbankPaymentId);
        if (!orderId.isBlank() && !paymentId.isBlank()) {
            return orderId + "/" + paymentId;
        }
        return orderId.isBlank() ? paymentId : orderId;
    }

    private String managerName(Manager manager) {
        if (manager == null || manager.getUser() == null) {
            return "";
        }
        return normalize(manager.getUser().getFio());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int max) {
        String clean = normalize(value);
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private String readableException(RuntimeException e) {
        if (e == null) {
            return "unknown_error";
        }
        String message = normalize(e.getMessage());
        return message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private record PreparedCommonInvoiceMessage(
            Long invoiceId,
            Company chatCompany,
            String managerClientId,
            String groupId,
            String message,
            boolean reminder,
            boolean manual
    ) {
    }

    private record PreparedCommonPaymentInit(
            Long invoiceId,
            String email,
            long remainingKopecks,
            TbankPaymentProfile runtimeProfile,
            String tbankOrderId,
            PublicPaymentInitResponse cachedResponse
    ) {
    }

    private record PreparedArchivedPaymentCancel(
            Long refId,
            String paymentId,
            String terminalKey,
            long amountKopecks
    ) {
    }

    private record VerifiedWebhookProfile(TbankPaymentProfile runtimeProfile) {
    }

    private static class AmountCalculationException extends RuntimeException {
        private AmountCalculationException(Long orderId, RuntimeException cause) {
            super("failed_to_calculate_common_invoice_amount: orderId=" + orderId, cause);
        }
    }
}
