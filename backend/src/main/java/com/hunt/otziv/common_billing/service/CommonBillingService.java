package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountRequest;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountResponse;
import com.hunt.otziv.common_billing.dto.CommonBillingCompanyResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchivePreviewResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchivePreviewResponse.CommonInvoiceArchiveOrderPreview;
import com.hunt.otziv.common_billing.dto.CommonInvoiceCloseRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceManualCardPaymentRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceNextCycleResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceOrderResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentRefResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentInitCheckRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.dto.ManualPaymentConfirmationRequest;
import com.hunt.otziv.common_billing.dto.PublicCommonInvoiceResponse;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountCompanyRepository;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceBoardQueryRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.metrics.R0ObservabilityMetrics;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRequisitesSnapshot;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService.FrozenCommonRouteAction;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.mapper.OrderDtoMapper;
import com.hunt.otziv.p_products.deletion.service.OrderDeletionService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.service.NextOrderFailureNotifier;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.next_order.service.NextOrderRequestService;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.p_products.service.OrderTransactionService;
import com.hunt.otziv.p_products.status.policy.OrderManualArchivePolicy;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.PaymentRouteSelection;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankInitCommand;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.model.TbankRuntimeMode;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.StandaloneBankPaymentPolicy;
import com.hunt.otziv.payments.service.PaymentUrlPolicy;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.payments.service.TbankTokenSigner;
import com.hunt.otziv.payments.service.ManualPaymentAutoConfirmationService;
import com.hunt.otziv.payments.service.ManualCardPaymentReviewNotificationService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.UserService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonBillingService {

    public static final String STATUS_WAITING_COMMON_INVOICE = "Ожидает общего счета";
    private static final int BULK_QUERY_CHUNK_SIZE = 500;
    private static final String STATUS_NEEDS_ATTENTION = "Требует внимания";
    private static final String STATUS_NOT_PAID = "Не оплачено";
    private static final String STATUS_PUBLIC = "Опубликовано";
    private static final String STATUS_TO_CHECK = "В проверку";
    private static final String STATUS_IN_CHECK = "На проверке";
    private static final String STATUS_TO_PUBLISH = "Публикация";
    private static final String STATUS_TO_PAY = "Выставлен счет";
    private static final String STATUS_REMINDER = "Напоминание";
    private static final String STATUS_ARCHIVE = "Архив";
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
            CommonInvoiceStatus.UNPAID
    );
    private static final Set<CommonInvoiceStatus> REMINDER_STATUSES = Set.of(
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );
    private static final Set<CommonInvoiceStatus> UNSENT_ACTION_STATUSES = Set.of(
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.PARTIALLY_PAID
    );
    private static final Set<CommonInvoiceStatus> PUBLIC_PAYABLE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
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
    private static final String PAYMENT_REF_PREPAID = "PREPAID";
    private static final String PAYMENT_REF_APPLYING = "APPLYING";
    private static final String PAYMENT_REF_APPLIED = "APPLIED";
    private static final String PAYMENT_REF_ARCHIVED = "ARCHIVED";
    private static final String PAYMENT_REF_CANCEL_PENDING = "CANCEL_PENDING";
    private static final String PAYMENT_REF_CANCELING = "CANCELING";
    private static final String PAYMENT_REF_CANCELED = "CANCELED";
    private static final String PAYMENT_REF_CANCEL_FAILED = "CANCEL_FAILED";
    private static final String PAYMENT_REF_CANCEL_FAILED_FINAL = "CANCEL_FAILED_FINAL";
    private static final String PAYMENT_METHOD_TBANK = "TBANK";
    private static final String PAYMENT_METHOD_MANUAL = "MANUAL";
    private static final String PAYMENT_METHOD_MIXED = "MIXED";
    private static final String PAYMENT_REF_INIT_PREPARED = "INIT_PREPARED";
    private static final String PAYMENT_REF_INIT_CONFLICT = "INIT_CONFLICT";
    private static final String PAYMENT_REF_CURRENT = "CURRENT";
    private static final Set<String> PREPARED_PAYMENT_REF_LIFECYCLE_STATUSES = Set.of(
            PAYMENT_REF_INIT_PREPARED,
            PAYMENT_REF_INIT_CONFLICT,
            PAYMENT_REF_CURRENT,
            PAYMENT_REF_PREPAID,
            PAYMENT_REF_CONFIRMED,
            PAYMENT_REF_APPLYING,
            PAYMENT_REF_APPLIED,
            PAYMENT_REF_ARCHIVED,
            PAYMENT_REF_CANCEL_PENDING,
            PAYMENT_REF_CANCELING,
            PAYMENT_REF_CANCELED,
            PAYMENT_REF_CANCEL_FAILED,
            PAYMENT_REF_CANCEL_FAILED_FINAL,
            "REJECTED",
            "REFUNDED",
            "PARTIAL_REFUNDED",
            "REVERSED",
            "PARTIAL_REVERSED"
    );
    private static final Set<String> PAYMENT_INIT_TLS_RECOVERY_ALLOWED_REF_STATUSES = Set.of(
            PAYMENT_REF_INIT_PREPARED,
            PAYMENT_REF_INIT_CONFLICT,
            PAYMENT_REF_CANCELED,
            "REJECTED",
            "REFUNDED",
            "REVERSED"
    );
    private static final Set<String> DELETION_DETACH_BLOCKING_PAYMENT_REF_STATUSES = Set.of(
            PAYMENT_REF_CONFIRMED,
            PAYMENT_REF_PREPAID,
            PAYMENT_REF_APPLYING,
            PAYMENT_REF_APPLIED,
            PAYMENT_REF_CANCEL_PENDING,
            PAYMENT_REF_CANCELING,
            PAYMENT_REF_CANCEL_FAILED,
            PAYMENT_REF_CANCEL_FAILED_FINAL,
            PAYMENT_REF_INIT_PREPARED,
            PAYMENT_REF_INIT_CONFLICT,
            PAYMENT_REF_CURRENT
    );
    private static final Set<String> PAYMENT_INIT_MANUAL_BLOCKING_REF_STATUSES = Set.of(
            PAYMENT_REF_CURRENT,
            PAYMENT_REF_CANCEL_PENDING,
            PAYMENT_REF_CANCELING,
            PAYMENT_REF_CANCEL_FAILED,
            PAYMENT_REF_CANCEL_FAILED_FINAL,
            PAYMENT_REF_CONFIRMED,
            PAYMENT_REF_PREPAID,
            PAYMENT_REF_APPLYING,
            PAYMENT_REF_APPLIED
    );
    private static final Set<String> PAYMENT_INIT_NEW_ATTEMPT_BLOCKING_REF_STATUSES = Set.of(
            PAYMENT_REF_INIT_PREPARED,
            PAYMENT_REF_INIT_CONFLICT,
            PAYMENT_REF_CURRENT,
            PAYMENT_REF_CANCEL_PENDING,
            PAYMENT_REF_CANCELING,
            PAYMENT_REF_CANCEL_FAILED,
            PAYMENT_REF_CANCEL_FAILED_FINAL,
            PAYMENT_REF_CONFIRMED,
            PAYMENT_REF_PREPAID,
            PAYMENT_REF_APPLYING,
            PAYMENT_REF_APPLIED
    );
    private static final String PREPAID_WAITING_COMMON_INVOICE_READY = "prepaid_waiting_common_invoice_ready";
    private static final Set<String> PAYMENT_REF_REFUNDED_STATUSES = Set.of(
            "REFUNDED",
            "PARTIAL_REFUNDED",
            "REVERSED",
            "PARTIAL_REVERSED",
            "CANCELED"
    );
    private static final Set<String> PAYMENT_REF_NONTERMINAL_DOWNGRADE_PROTECTED_STATUSES = Set.of(
            PAYMENT_REF_PREPAID,
            PAYMENT_REF_CONFIRMED,
            PAYMENT_REF_APPLYING,
            PAYMENT_REF_APPLIED,
            PAYMENT_REF_CANCELED,
            "REJECTED",
            "REFUNDED",
            "PARTIAL_REFUNDED",
            "REVERSED",
            "PARTIAL_REVERSED"
    );
    private static final int PAYMENT_REF_CANCEL_MAX_ATTEMPTS = 144;
    private static final int TRANSACTION_LOCK_RETRY_ATTEMPTS = 3;
    private static final long TRANSACTION_LOCK_RETRY_DELAY_MS = 300L;
    private static final int COMPANY_RECONCILE_MAX_ATTEMPTS = 20;
    private static final java.time.Duration COMPANY_RECONCILE_LEASE = java.time.Duration.ofMinutes(5);
    private static final java.time.Duration COMPANY_RECONCILE_MAX_BACKOFF = java.time.Duration.ofMinutes(30);
    private static final java.time.Duration PAYMENT_REF_CANCEL_RETRY_DELAY = java.time.Duration.ofMinutes(10);
    private static final java.time.Duration PAYMENT_REF_CANCELING_TIMEOUT = java.time.Duration.ofMinutes(30);
    private static final String MESSAGE_SEND_IN_PROGRESS = "message_send_in_progress";
    private static final String PAYMENT_INIT_IN_PROGRESS = "payment_init_in_progress";
    private static final String PAYMENT_INIT_STALE = "payment_init_stale";
    private static final String PAYMENT_CANCEL_FAILED_FINAL = "payment_cancel_failed_final";
    private static final String MESSAGE_SEND_STALE = "message_send_stale";
    private static final String MIGRATION_PAYMENT_REGISTRY_ATTENTION = "migration_common_payment_registry:";
    private static final String MIGRATION_PAYMENT_REGISTRY_MANUAL_CONFIRM_REASON =
            "nonterminal_or_unknown_payment_ref_on_invoice";
    private static final String PAYMENT_INIT_TLS_SAFE_ARCHIVED_REASON_PREFIX =
            "payment_init_tls_failed_before_http_request";
    private static final String PAYMENT_INIT_MANUALLY_CHECKED_REASON = "payment_init_manually_checked";
    private static final String PAYMENT_INIT_MANUALLY_CHECKED_BY_PREFIX =
            PAYMENT_INIT_MANUALLY_CHECKED_REASON + "_by=";
    private static final String INVOICE_MEMBERSHIP_CHANGED = "common_invoice_membership_changed";
    private static final String STANDALONE_PAYMENT_ROUTE_CONFLICT = "standalone_payment_route_conflict";
    private static final String BAD_REVIEW_SUPPLEMENT_REQUIRED = "bad_review_supplement_required:";
    private static final String PAYABLE_CHANGE_REQUIRES_SUPPLEMENT = "payable_change_requires_supplement:";
    private static final String COMMON_INVOICE_ROUTE_ATTACHED_PREFIX = "common_invoice_route_attached; ";
    private static final String MANUAL_PAYMENT_ABSENT_VERIFIED_PREFIX = "manual_payment_absent_verified";
    private static final String CONTRACTOR_COMMON_SOURCE_CONFIRMATION_AUDIT_PREFIX =
            "contractor_common_source_confirmation;";
    private static final Set<PaymentLinkStatus> SAFELY_CLOSED_STANDALONE_PAYMENT_STATUSES = Set.of(
            PaymentLinkStatus.REJECTED,
            PaymentLinkStatus.CANCELED,
            PaymentLinkStatus.REVERSED,
            PaymentLinkStatus.REFUNDED
    );
    private static final Set<PaymentLinkStatus> MANUAL_COMMON_PAYMENT_CLOSABLE_ROUTE_STATUSES = Set.of(
            PaymentLinkStatus.CREATED,
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED,
            PaymentLinkStatus.EXPIRED,
            PaymentLinkStatus.FAILED
    );
    private static final Set<String> MANUAL_COMMON_PAYMENT_SAFE_REF_STATUSES = Set.of(
            PAYMENT_REF_APPLIED,
            PAYMENT_REF_ARCHIVED,
            PAYMENT_REF_CANCELED,
            "REJECTED",
            "REFUNDED",
            "REVERSED"
    );
    private static final Set<PaymentLinkStatus> STANDALONE_PAYMENT_REVERSAL_STATUSES = Set.of(
            PaymentLinkStatus.REVERSED,
            PaymentLinkStatus.PARTIAL_REVERSED,
            PaymentLinkStatus.REFUNDED,
            PaymentLinkStatus.PARTIAL_REFUNDED
    );
    private static final Set<String> RESOLVABLE_TECHNICAL_TAIL_ERROR_PREFIXES = Set.of(
            "disabled:",
            "empty:",
            "merged_into:",
            "manual_fix:"
    );
    private static final java.time.Duration OPERATION_IN_PROGRESS_TIMEOUT = java.time.Duration.ofMinutes(30);
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    private final PlatformTransactionManager transactionManager;
    private final EntityManager entityManager;
    private final CommonBillingAccountRepository accountRepository;
    private final CommonBillingAccountCompanyRepository accountCompanyRepository;
    private final CommonInvoiceBoardQueryRepository invoiceBoardQueryRepository;
    private final CommonInvoiceRepository invoiceRepository;
    private final CommonInvoiceOrderRepository invoiceOrderRepository;
    private final CommonInvoicePaymentRefRepository paymentRefRepository;
    private final CompanyRepository companyRepository;
    private final ManagerRepository managerRepository;
    private final OrderRepository orderRepository;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;
    private final NextOrderRequestRepository nextOrderRequestRepository;
    private final ObjectProvider<OrderDeletionService> orderDeletionServiceProvider;
    private final PaymentLinkRepository paymentLinkRepository;
    private final ObjectProvider<PaymentLinkService> paymentLinkServiceProvider;
    private final ManualPaymentTaskService manualPaymentTaskService;
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
    private final ManualCardPaymentReviewNotificationService manualCardPaymentReviewNotificationService;
    private final AppSettingService appSettingService;
    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;
    private final ContractorPaymentShadowService contractorPaymentShadowService;
    private final ContractorCompletionRewardService contractorCompletionRewardService;
    private final TbankRuntimeSettingsService runtimeSettingsService;
    private final PaymentProfileService paymentProfileService;
    private final TbankPaymentProperties properties;
    private final TbankClient tbankClient;
    private final TbankTokenSigner tokenSigner;
    private final ReviewRecoveryGateService recoveryGateService;
    private final CommonInvoicePublicationBlockerService publicationBlockerService;
    private final ObjectProvider<OrderPublicationApprovalService> publicationApprovalServiceProvider;
    private final R0ObservabilityMetrics observabilityMetrics;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public boolean hasClientReportedPaymentForOrder(Long orderId) {
        return orderId != null && invoiceRepository.countClientReportedPaymentsByOrderId(orderId) > 0;
    }

    @Transactional
    public List<CommonBillingAccountResponse> accounts() {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        List<CommonBillingAccount> accounts = accountRepository.findAllForAdmin();
        List<Long> ids = accounts.stream().map(CommonBillingAccount::getId).toList();
        Map<Long, List<CommonBillingAccountCompany>> companies = accountCompanyRepository.findByAccountIds(ids)
                .stream()
                .collect(Collectors.groupingBy(link -> link.getAccount().getId()));
        List<CommonBillingAccount> visibleAccounts = accounts.stream()
                .filter(account -> accountVisibleToManager(account, companies.getOrDefault(account.getId(), List.of()), visibleManagerIds))
                .toList();
        Map<Long, CommonInvoiceSummaryResponse> currentInvoices = currentInvoiceSummaries(visibleAccounts);
        return visibleAccounts.stream()
                .map(account -> toAccountResponse(
                        account,
                        companies.getOrDefault(account.getId(), List.of()),
                        currentInvoices.get(account.getId())
                ))
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
        List<CommonBillingAccount> visibleAccounts = accounts.stream()
                .filter(account -> accountVisibleToManager(account, companies.getOrDefault(account.getId(), List.of()), visibleManagerIds))
                .toList();
        Map<Long, CommonInvoiceSummaryResponse> currentInvoices = currentInvoiceSummaries(visibleAccounts);
        return visibleAccounts.stream()
                .map(account -> toAccountResponse(
                        account,
                        companies.getOrDefault(account.getId(), List.of()),
                        currentInvoices.get(account.getId())
                ))
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

    /** Loads only the requested board page after SQL filtering and counting. */
    @Transactional
    public ManagerBoardPage managerBoardPage(
            String boardStatus,
            String keyword,
            Long companyId,
            Set<Long> visibleManagerIds,
            String sortDirection,
            int pageNumber,
            int pageSize
    ) {
        int safePageNumber = Math.max(0, pageNumber);
        int safePageSize = Math.max(1, pageSize);
        String normalizedStatus = normalize(boardStatus);
        String normalizedKeyword = normalize(keyword).toLowerCase(Locale.ROOT);

        normalizeBoardInvoiceDuplicates();
        CommonInvoiceBoardQueryRepository.PageSelection selection = invoiceBoardQueryRepository.findPage(
                normalizedStatus,
                normalizedKeyword,
                companyId,
                visibleManagerIds,
                "asc".equalsIgnoreCase(sortDirection),
                safePageNumber,
                safePageSize,
                LocalDateTime.now().minusHours(CommonInvoicePublicationBlockerService.ATTENTION_AFTER_HOURS)
        );
        if (selection.invoiceIds().isEmpty()) {
            return new ManagerBoardPage(List.of(), selection.totalCards(), selection.linkedOrderCount());
        }

        Map<Long, CommonInvoice> invoicesById = invoiceRepository.findBoardInvoicesByIds(selection.invoiceIds())
                .stream()
                .filter(invoice -> invoice != null && invoice.getId() != null)
                .collect(Collectors.toMap(CommonInvoice::getId, Function.identity()));
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoiceId = invoiceOrderRepository
                .findByInvoiceIdsWithOrders(selection.invoiceIds())
                .stream()
                .filter(item -> item != null && item.getInvoice() != null && item.getInvoice().getId() != null)
                .collect(Collectors.groupingBy(item -> item.getInvoice().getId()));
        List<BoardInvoiceView> selectedCards = selection.invoiceIds().stream()
                .map(invoicesById::get)
                .filter(Objects::nonNull)
                .map(invoice -> new BoardInvoiceView(
                        invoice,
                        itemsByInvoiceId.getOrDefault(invoice.getId(), List.of())
                ))
                .toList();
        List<OrderDTOList> cards = selectedCards.stream()
                .map(view -> {
                    refreshInvoiceAmounts(view.invoice(), view.items());
                    return toManagerBoardCard(view.invoice(), view.items());
                })
                .toList();
        return new ManagerBoardPage(cards, selection.totalCards(), selection.linkedOrderCount());
    }

    /** Aggregates both common cards and linked orders in SQL. */
    @Transactional
    public ManagerBoardMetrics managerBoardMetrics(Set<Long> visibleManagerIds) {
        normalizeBoardInvoiceDuplicates();
        CommonInvoiceBoardQueryRepository.BoardMetrics metrics = invoiceBoardQueryRepository.metrics(
                visibleManagerIds,
                LocalDateTime.now().minusHours(CommonInvoicePublicationBlockerService.ATTENTION_AFTER_HOURS)
        );
        return new ManagerBoardMetrics(metrics.cardCounts(), metrics.linkedOrderCounts());
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
        CommonBillingAccount accountSnapshot = accountRepository.findByIdWithRelations(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(accountSnapshot);
        boolean snapshotEnabled = accountSnapshot.isEnabled();
        boolean snapshotTargetDisabled = request != null
                && (Boolean.FALSE.equals(request.enabled())
                || (request.enabled() == null && !snapshotEnabled));
        if (snapshotTargetDisabled) {
            // No Account/link field has been changed yet. The detach path can
            // therefore establish Order -> Account -> Invoice first.
            detachCurrentAccountOrders(accountId);
        }
        CommonBillingAccount account = lockFreshAccountAfterOrderPrelude(accountId);
        ensureAccountVisibleForCurrentUser(account);
        if (request != null
                && request.enabled() == null
                && snapshotEnabled != account.isEnabled()) {
            throw invoiceMembershipChanged("состояние общего плательщика изменилось; повторите сохранение");
        }
        applyAccountRequest(account, request);
        ensureAccountRequestVisibleForCurrentUser(account, request == null ? null : request.companyIds(), false);
        accountRepository.save(account);
        if (!account.isEnabled()) {
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
        CommonBillingAccount accountSnapshot = accountRepository.findByIdWithRelations(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(accountSnapshot);
        CommonBillingAccount account = lockFreshAccountAfterOrderPrelude(accountId);
        ensureAccountVisibleForCurrentUser(account);
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
        addCompanyToAccount(account, company);
        return account(accountId);
    }

    @Transactional
    public CommonBillingAccountResponse removeCompany(Long accountId, Long companyId, boolean detachCurrent) {
        CommonBillingAccount accountSnapshot = accountRepository.findByIdWithRelations(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(accountSnapshot);
        if (detachCurrent) {
            detachCurrentCompanyOrders(accountId, companyId);
        }
        CommonBillingAccount account = lockFreshAccountAfterOrderPrelude(accountId);
        ensureAccountVisibleForCurrentUser(account);
        Long currentLinkId = accountCompanyRepository.findByAccount_IdAndCompany_Id(accountId, companyId)
                .map(CommonBillingAccountCompany::getId)
                .orElse(null);
        if (currentLinkId != null) {
            CommonBillingAccountCompany link = accountCompanyRepository.findByIdForUpdate(currentLinkId)
                    .orElseThrow(() -> invoiceMembershipChanged("связь компании с общим плательщиком исчезла"));
            if (link.getAccount() == null
                    || link.getCompany() == null
                    || !Objects.equals(accountId, link.getAccount().getId())
                    || !Objects.equals(companyId, link.getCompany().getId())) {
                throw invoiceMembershipChanged("связь компании сменила плательщика");
            }
            link.setEnabled(false);
            clearCompanyReconcileState(link);
            saveAccountCompany(link);
        }
        return account(accountId);
    }

    @Transactional
    public boolean attachOrderIfNeeded(Order order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        Long companyId = orderRepository.findCompanyIdByOrderId(order.getId()).orElse(null);
        if (companyId == null) {
            return false;
        }
        if (invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(order.getId()).isPresent()) {
            return true;
        }
        Optional<CommonBillingAccount> account = enabledAccountForCompany(companyId);
        if (account.isEmpty()) {
            return false;
        }
        CommonInvoiceOrder item = attachOrderToCurrentInvoice(account.get(), order.getId(), companyId);
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
                        && invoice.getStatus() != CommonInvoiceStatus.ARCHIVED
                        && invoice.getStatus() != CommonInvoiceStatus.DISABLED)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Set<Long> findOrderIdsInActiveCommonInvoices(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Set.of();
        }
        List<Long> normalized = orderIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<Long> linked = new HashSet<>();
        for (int offset = 0; offset < normalized.size(); offset += BULK_QUERY_CHUNK_SIZE) {
            linked.addAll(invoiceOrderRepository.findLinkedOrderIds(
                    normalized.subList(offset, Math.min(normalized.size(), offset + BULK_QUERY_CHUNK_SIZE)),
                    BOARD_INVOICE_STATUSES
            ));
        }
        return linked;
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
        if (invoice.getStatus() != CommonInvoiceStatus.COLLECTING
                || invoice.getSentAt() != null
                || invoice.getClientReportedAt() != null
                || hasFrozenCommonPaymentRoute(invoice)) {
            CommonInvoiceStatus previous = invoice.getStatus();
            invoice.setPreviousStatus(previous == null ? null : previous.name());
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    PAYABLE_CHANGE_REQUIRES_SUPPLEMENT
                            + " source_invoice=" + invoice.getId()
                            + ";order=" + orderId
                            + ";previous=" + (previous == null ? "unknown" : previous.name()),
                    512
            ));
            invoiceRepository.save(invoice);
            return true;
        }
        Long payable = payableKopecksOrMarkAttention(invoice, item.getOrder());
        if (payable == null) {
            return true;
        }
        item.setAmountKopecks(payable);
        invoiceOrderRepository.save(item);
        List<CommonInvoiceOrder> currentItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        recalculateInvoice(invoice, currentItems);
        promoteBadReviewSuccessorIfReady(invoice, currentItems);
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
        List<CommonInvoiceOrder> currentItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        if (allOrdersReady(currentItems)) {
            // This entry point is called while the publication transaction
            // already owns one Order row. Never lock any sibling Order here:
            // two last publications for the same invoice could otherwise hold
            // A/B and wait for B/A. Finalize after commit in a fresh canonical
            // Orders(id ASC) -> account -> invoice transaction.
            if (deferReadyCommonInvoiceFinalizationUntilAfterCommit(invoice)) {
                return true;
            }
            if (applyCommonInvoicePrepaymentIfReady(invoice, currentItems)) {
                return true;
            }
        }
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

    public int sendUnsentActionInvoices(int limit) {
        if (!immediateClientMessagesEnabled()) {
            return 0;
        }
        List<CommonInvoice> invoices = invoiceRepository.findUnsentActionCandidates(
                UNSENT_ACTION_STATUSES,
                LocalDateTime.now().minusMinutes(5),
                PageRequest.of(0, Math.max(1, limit))
        );
        int sent = 0;
        for (CommonInvoice candidate : invoices) {
            PreparedCommonInvoiceMessage prepared = writeTransaction(() ->
                    preparePaymentMessage(candidate.getId(), false, false, false, null, false)
            );
            if (prepared == null) {
                continue;
            }
            ClientMessageSendResult result = sendPreparedPaymentMessage(prepared);
            boolean delivered = writeTransaction(() -> finishPaymentMessageSend(prepared, result));
            if (delivered) {
                sent++;
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
            Long candidateInvoiceId = paymentRefInvoiceId(candidate);
            PreparedArchivedPaymentCancel prepared = writeTransaction(() ->
                    prepareArchivedPaymentCancel(candidate.getId(), candidateInvoiceId)
            );
            if (prepared == null) {
                continue;
            }
            String status = cancelArchivedPayment(prepared);
            writeTransaction(() -> {
                finishArchivedPaymentCancel(prepared, status);
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

    private CommonInvoiceDetailsResponse invoiceAfterOrderPrelude(Long invoiceId) {
        CommonInvoice invoice = lockedInvoiceAfterOrderPrelude(invoiceId)
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
        boolean payable = remaining > 0 && canAcceptPublicPayment(invoice);
        if (payable) {
            ensureCommonPaymentRouteSelected(invoice, remaining);
        }
        boolean contractorRoute = invoice.getContractorAllocationId() != null
                && invoice.getPaymentRouteManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE;
        ContractorPaymentRequisitesSnapshot contractorRequisites = contractorRoute && payable
                ? contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(invoice, remaining)
                .orElse(null)
                : null;
        boolean contractorRequisitesVisible = !contractorRoute || contractorRequisites != null;
        String manualPhone = contractorRoute
                ? contractorRequisites == null ? "" : normalize(contractorRequisites.paymentPhone())
                : normalize(invoice.getPaymentRouteManualPhone());
        String manualRecipient = contractorRoute
                ? contractorRequisites == null ? "" : normalize(contractorRequisites.recipientName())
                : normalize(invoice.getPaymentRouteManualRecipient());
        String manualBank = contractorRoute
                ? contractorRequisites == null ? "" : normalize(contractorRequisites.bankName())
                : normalize(invoice.getPaymentRouteManualBankName());
        String manualComment = contractorRoute
                ? contractorRequisites == null ? "" : normalize(contractorRequisites.paymentComment())
                : normalize(invoice.getPaymentRouteManualComment());
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
                payable,
                normalize(invoice.getPaymentRouteType()),
                invoice.getPaymentRouteManualType() == null ? "" : invoice.getPaymentRouteManualType().name(),
                contractorRequisitesVisible ? manualPhone : "",
                contractorRequisitesVisible ? manualRecipient : "",
                contractorRequisitesVisible ? manualBank : "",
                contractorRequisitesVisible && !contractorRoute ? safeCommonManualPaymentUrl(invoice) : "",
                normalize(invoice.getPaymentRouteManualButton()),
                contractorRequisitesVisible ? manualComment : "",
                contractorRequisitesVisible && !contractorRoute
                        ? normalize(invoice.getPaymentRouteInstructionText())
                        : "",
                payable && contractorRoute && contractorRequisitesVisible
                        && contractorPaymentLiveRoutingService.isCommonClientReportable(invoice),
                invoice.getClientReportedAt(),
                items.stream().map(this::toOrderResponse).toList()
        );
    }

    /** Token-only, idempotent public evidence; no amount or recipient is accepted from the client. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicCommonInvoiceResponse reportPublicCommonPayment(String token) {
        String clean = cleanToken(token);
        contractorPaymentLiveRoutingService.recordCommonClientReported(clean);
        return writeTransaction(() -> publicInvoice(clean));
    }

    @Transactional
    public void deleteInvoiceWithOrders(Long invoiceId, Principal principal) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureCommonInvoiceCanBeDeleted(invoice, items);
        if (hasFrozenCommonPaymentRoute(invoice)
                || invoice.getContractorAllocationId() != null
                || invoice.getClientReportedAt() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя удалить общий счет с уже выданными платежными реквизитами. "
                            + "Переведите его в \"Не оплачено\" или выполните ручную сверку."
            );
        }
        if (isBadReviewSuccessor(invoice)) {
            deleteUnsentBadReviewSuccessor(invoice, items);
            return;
        }
        if (invoiceRepository.existsBySupersedesInvoice_Id(invoiceId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Счет имеет дополнительный цикл. Сначала удалите последний неотправленный цикл"
            );
        }

        List<Long> orderIds = items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // OrderDeletionService deliberately fails closed while an order is a
        // member of a common invoice. Detach every membership only after all
        // delete guards have passed and before delegating to the standalone
        // order deletion path. This method is one transaction, so any child
        // deletion failure restores these rows together with all prior work.
        int detachedLinks = invoiceOrderRepository.deleteByInvoiceId(invoiceId);
        if (detachedLinks != items.size()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состав общего счета изменился во время удаления. Повторите действие."
            );
        }

        for (Long orderId : orderIds) {
            boolean deleted = orderDeletionServiceProvider.getObject().deleteOrder(orderId, principal);
            if (!deleted) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Не удалось удалить связанный заказ #" + orderId
                );
            }
        }

        int paymentRefs = paymentRefRepository.deleteByInvoiceId(invoiceId);
        invoiceRepository.deleteById(invoiceId);
        log.info(
                "Удален общий счет {} вместе со связанными заказами: orders={}, detachedLinks={}, paymentRefs={}",
                invoiceId,
                orderIds.size(),
                detachedLinks,
                paymentRefs
        );
    }

    @Transactional
    public CommonInvoiceDetailsResponse markOrderPaid(
            Long invoiceId,
            Long orderId,
            ManualPaymentConfirmationRequest request,
            Principal principal
    ) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureGenericConfirmationDoesNotUseContractorSource(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanChangePositions(invoice);
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден в общем счете"));
        if (!invoice.getId().equals(item.getInvoice().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ относится к другому общему счету");
        }
        applyManualPaymentEvidence(invoice, item, request, principal);
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
        return invoiceAfterOrderPrelude(invoiceId);
    }

    CommonInvoiceDetailsResponse markOrderPaid(Long invoiceId, Long orderId) {
        return markOrderPaid(
                invoiceId,
                orderId,
                new ManualPaymentConfirmationRequest("Внутреннее подтверждение", ""),
                () -> "system"
        );
    }

    @Transactional
    public boolean applyConfirmedOrderPayment(Long orderId, LocalDateTime paidAt, String reason) {
        if (orderId == null) {
            return false;
        }
        // PaymentLinkService already follows Order -> PaymentLink. Re-locking the
        // same canonical row is safe and makes this entry point correct when it
        // is invoked independently as well. Do not lock every sibling here:
        // two concurrent standalone confirmations may already own different
        // order rows from the same invoice.
        orderAggregateMutationLockService.lock(orderId);
        List<PaymentLink> lockedPaymentLinks = paymentLinkRepository.findByOrderIdForUpdate(orderId);
        if (lockedPaymentLinks == null) {
            lockedPaymentLinks = List.of();
        }
        Optional<CommonInvoiceOrder> optionalItem = invoiceOrderRepository.findByOrderIdWithInvoice(orderId);
        if (optionalItem.isEmpty()) {
            return false;
        }

        CommonInvoiceOrder item = optionalItem.get();
        CommonInvoice itemInvoice = item.getInvoice();
        Long invoiceId = itemInvoice == null ? null : itemInvoice.getId();
        if (invoiceId == null) {
            return false;
        }

        CommonInvoice invoice = lockedInvoiceAfterOrderPrelude(invoiceId).orElse(itemInvoice);
        if (invoice.getStatus() == CommonInvoiceStatus.PAID
                || invoice.getStatus() == CommonInvoiceStatus.UNPAID
                || invoice.getStatus() == CommonInvoiceStatus.BAN
                || invoice.getStatus() == CommonInvoiceStatus.DISABLED
                || invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION) {
            return false;
        }

        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        CommonInvoiceOrder target = items.stream()
                .filter(candidate -> candidate.getOrder() != null && orderId.equals(candidate.getOrder().getId()))
                .findFirst()
                .orElse(item);
        boolean alreadyApplied = target.isPaid();
        try {
            Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(
                    invoice,
                    items,
                    Map.of(orderId, List.copyOf(lockedPaymentLinks))
            );
            if (appliedStandalonePayments.isEmpty()) {
                throw standalonePaymentConflict(
                        orderId,
                        "обратный вызов оплаты не нашел единственный подтвержденный отдельный платеж"
                );
            }
            ensureNoCompetingStandaloneRoutesOrThrow(
                    paymentLinksRequiringCommonInvoiceRouteCheck(
                            Map.of(orderId, List.copyOf(lockedPaymentLinks)),
                            List.of(target),
                            appliedStandalonePayments
                    )
            );
        } catch (ResponseStatusException conflict) {
            markStandalonePaymentRouteConflict(invoice, conflict);
            return false;
        }

        // The caller already supplied the confirmed standalone payment. Avoid
        // discovering and closing sibling standalone links while only the
        // target order is locked.
        refreshInvoiceAmounts(invoice, items);
        if (!items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isPaid)) {
            if (!closeOrderAsPaidForConfirmedItem(invoice, target)) {
                return true;
            }
            closePaidInvoice(invoice, items);
        } else {
            if (!closeOrderAsPaidForConfirmedItem(invoice, target)) {
                return true;
            }
            invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
            if (invoice.getNextReminderAt() == null) {
                invoice.setNextReminderAt(LocalDateTime.now().plusDays(REMINDER_INTERVAL_DAYS));
            }
            invoiceRepository.save(invoice);
        }
        log.info(
                "Оплата отдельной ссылки заказа {} зачтена в общий счет {}: {}",
                orderId,
                invoiceId,
                normalize(reason)
        );
        return !alreadyApplied;
    }

    /**
     * Quarantines an invoice when the provider later reverses/refunds the exact
     * standalone payment that funded one of its positions. Paid flags are kept
     * intact until a human reconciles the returned money.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applyStandalonePaymentReversal(
            Long orderId,
            Long paymentLinkId,
            PaymentLinkStatus terminalStatus
    ) {
        if (orderId == null || paymentLinkId == null
                || !STANDALONE_PAYMENT_REVERSAL_STATUSES.contains(terminalStatus)) {
            return false;
        }
        orderAggregateMutationLockService.lock(orderId);
        PaymentLink sourceLink = paymentLinkRepository.findByOrderIdForUpdate(orderId).stream()
                .filter(link -> paymentLinkId.equals(link.getId()))
                .findFirst()
                .orElse(null);
        String durableProviderStatus = sourceLink == null
                ? ""
                : normalize(sourceLink.getProviderTerminalStatus()).toUpperCase(Locale.ROOT);
        if (sourceLink == null
                || sourceLink.getStatus() != terminalStatus
                || !terminalStatus.name().equals(durableProviderStatus)) {
            return false;
        }
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId).orElse(null);
        if (item == null || item.getInvoice() == null || item.getInvoice().getId() == null) {
            return false;
        }
        CommonInvoice snapshot = item.getInvoice();
        if (!paymentLinkId.equals(item.getSourcePaymentLinkId())) {
            return false;
        }
        CommonInvoice invoice = lockedInvoiceAfterOrderPrelude(snapshot.getId()).orElse(snapshot);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(
                "standalone_payment_reversed: order=" + orderId
                        + ";link=" + paymentLinkId
                        + ";status=" + terminalStatus.name()
                        + ";provider=" + durableProviderStatus,
                512
        ));
        invoiceRepository.save(invoice);
        return true;
    }

    /**
     * Locks and validates a linked common invoice before a bad-review task can
     * change the payable amount. A frozen, delivered or historical invoice is
     * never silently rewritten; it needs a supplemental invoice or manual
     * reconciliation. The invoice lock is retained by the caller transaction
     * through the later task and amount updates.
     */
    @Transactional
    public CommonPayableChangeDisposition prepareLinkedOrderPayableChange(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return CommonPayableChangeDisposition.NOT_LINKED;
        }
        CommonInvoiceOrder snapshot = invoiceOrderRepository.findByOrderIdWithInvoice(orderId).orElse(null);
        if (snapshot == null || snapshot.getInvoice() == null || snapshot.getInvoice().getId() == null) {
            return CommonPayableChangeDisposition.NOT_LINKED;
        }
        CommonInvoice invoice = lockedInvoice(snapshot.getInvoice().getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Связанный общий счет изменился. Обновите данные и повторите действие"
                ));
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Заказ больше не входит в ожидаемый общий счет"
                ));
        if (!Objects.equals(invoice.getId(), item.getInvoice().getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состав общего счета изменился. Обновите данные и повторите действие"
            );
        }
        boolean unpaidCycle = !item.isPaid()
                && item.isUnpaid()
                && (invoice.getStatus() == CommonInvoiceStatus.UNPAID
                || (invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && attentionError(invoice).startsWith(BAD_REVIEW_SUPPLEMENT_REQUIRED)));
        boolean frozenSuccessorCycle = !item.isPaid()
                && "BAD_REVIEW_SUCCESSOR".equals(invoice.getInvoicePurpose())
                && (invoice.getStatus() != CommonInvoiceStatus.COLLECTING
                || invoice.getSentAt() != null
                || invoice.getClientReportedAt() != null);
        if (unpaidCycle || frozenSuccessorCycle) {
            // The delivered common cycle is immutable. Completion is allowed,
            // but a standalone order link is forbidden.
            return CommonPayableChangeDisposition.SUPPLEMENT_REQUIRED;
        }
        if (item.isPaid()
                || invoice.getStatus() != CommonInvoiceStatus.COLLECTING
                || invoice.getSentAt() != null
                || invoice.getClientReportedAt() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сумма общего счета уже зафиксирована или содержит платежные признаки. "
                            + "Нужен дополнительный счет либо ручная сверка"
            );
        }
        ensureCommonPaymentRouteAllowsCompositionChange(invoice);
        return CommonPayableChangeDisposition.REFRESH_CURRENT_INVOICE;
    }

    /** Creates the immutable predecessor's idempotent live successor. */
    @Transactional
    public boolean createBadReviewSupplementSuccessor(Long orderId, Long taskId) {
        if (orderId == null || orderId <= 0 || taskId == null || taskId <= 0) {
            return false;
        }
        CommonInvoiceOrder snapshot = invoiceOrderRepository.findByOrderIdWithInvoice(orderId).orElse(null);
        if (snapshot == null || snapshot.getInvoice() == null || snapshot.getInvoice().getId() == null) {
            return false;
        }
        CommonInvoice invoice = lockedInvoice(snapshot.getInvoice().getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Связанный общий счет изменился. Обновите данные и повторите действие"
                ));
        // One successor belongs to one frozen predecessor generation. Every
        // task that arrives while that successor is still COLLECTING refreshes
        // the same cycle instead of creating another invoice.
        String idempotencyKey = "BAD_REVIEW_SUCCESSOR:" + invoice.getId();
        if (invoiceRepository.findByCycleIdempotencyKeyForUpdate(idempotencyKey).isPresent()) {
            return true;
        }
        CommonInvoiceOrder triggeringItem = invoiceOrderRepository.findByOrderIdWithInvoice(orderId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Заказ больше не входит в ожидаемый общий счет"
                ));
        if (!Objects.equals(invoice.getId(), triggeringItem.getInvoice().getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состав общего счета изменился. Обновите данные и повторите действие"
            );
        }
        boolean acceptedState = invoice.getStatus() == CommonInvoiceStatus.UNPAID
                || (invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && attentionError(invoice).startsWith(BAD_REVIEW_SUPPLEMENT_REQUIRED));
        if (!acceptedState || triggeringItem.isPaid() || !triggeringItem.isUnpaid()) {
            markBadReviewSupplementAttention(invoice, orderId, taskId, "unsafe_source_state");
            return true;
        }
        List<CommonInvoiceOrder> sourceItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        List<CommonInvoiceOrder> unpaidItems = sourceItems.stream()
                .filter(CommonInvoiceOrder::isActiveMembership)
                .filter(item -> !item.isPaid())
                .toList();
        if (unpaidItems.isEmpty() || unpaidItems.stream().anyMatch(item -> !item.isUnpaid())) {
            markBadReviewSupplementAttention(invoice, orderId, taskId, "unsafe_membership");
            return true;
        }
        Map<Long, Long> payableByOrder = new LinkedHashMap<>();
        for (CommonInvoiceOrder sourceItem : unpaidItems) {
            Order sourceOrder = sourceItem.getOrder();
            Long sourceOrderId = sourceOrder == null ? null : sourceOrder.getId();
            Long payable = sourceOrderId == null ? null : payableKopecksOrMarkAttention(invoice, sourceOrder);
            if (payable == null || payable <= 0 || hasBadReviewSuccessorPaymentEvidence(sourceItem)) {
                markBadReviewSupplementAttention(invoice, orderId, taskId, "unsafe_amount_or_payment_evidence");
                return true;
            }
            payableByOrder.put(sourceOrderId, payable);
        }

        CommonInvoice successor = createInvoice(invoice.getAccount());
        successor.setSupersedesInvoice(invoice);
        successor.setInvoicePurpose("BAD_REVIEW_SUCCESSOR");
        successor.setCycleIdempotencyKey(idempotencyKey);
        successor.setTitle(limit(invoice.getTitle() + " - дополнительный счет", 180));
        invoiceRepository.save(successor);

        LocalDateTime linkedAt = LocalDateTime.now();
        List<CommonInvoiceOrder> successorItems = new ArrayList<>();
        sourceItems.stream()
                .filter(CommonInvoiceOrder::isActiveMembership)
                .forEach(sourceItem -> sourceItem.setActiveMembership(false));
        for (CommonInvoiceOrder sourceItem : unpaidItems) {
            CommonInvoiceOrder successorItem = new CommonInvoiceOrder();
            successorItem.setInvoice(successor);
            successorItem.setActiveMembership(true);
            successorItem.setOrder(sourceItem.getOrder());
            successorItem.setAmountKopecks(payableByOrder.get(sourceItem.getOrder().getId()));
            successorItem.setOriginalOrderStatusTitle(sourceItem.getOriginalOrderStatusTitle());
            successorItem.setArchiveSourceOrderStatusTitle(sourceItem.getArchiveSourceOrderStatusTitle());
            successorItem.setReady(false);
            successorItem.setPaid(false);
            successorItem.setUnpaid(false);
            successorItem.setInvoiceLinkedAt(linkedAt);
            successorItems.add(successorItem);
        }
        invoiceOrderRepository.saveAll(sourceItems);
        // CommonInvoiceOrder uses IDENTITY. Flush the inactive predecessor
        // before persisting active successor rows, otherwise MySQL can still
        // reject the successor on uk_common_invoice_active_order.
        entityManager.flush();
        invoiceOrderRepository.saveAll(successorItems);
        recalculateInvoice(successor, successorItems);
        promoteBadReviewSuccessorIfReady(successor, successorItems);
        return true;
    }

    private void promoteBadReviewSuccessorIfReady(
            CommonInvoice invoice,
            List<CommonInvoiceOrder> items
    ) {
        if (invoice == null
                || !"BAD_REVIEW_SUCCESSOR".equals(invoice.getInvoicePurpose())
                || invoice.getStatus() != CommonInvoiceStatus.COLLECTING
                || items == null
                || items.isEmpty()) {
            return;
        }
        boolean stable = items.stream().allMatch(item -> {
            Order order = item.getOrder();
            if (order == null || order.getId() == null || hasActiveRecovery(item)) {
                return false;
            }
            BadReviewTaskSummary summary = badReviewTaskService.getSummaryForOrder(order.getId());
            return summary != null && summary.pending() == 0;
        });
        if (!stable) {
            return;
        }
        items.forEach(item -> item.setReady(true));
        invoiceOrderRepository.saveAll(items);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        // A successor is a new financial presentation. Keep it READY for the
        // canonical common-invoice send action; task completion must not send
        // an independently assembled message, bypass normal approval or move
        // the source order out of "Не оплачено" before actual payment.
        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true)
                && immediateClientMessagesEnabled()) {
            sendInvoiceAfterCommit(invoice.getId(), false);
        }
    }

    private void markBadReviewSupplementAttention(
            CommonInvoice invoice,
            Long orderId,
            Long taskId,
            String reason
    ) {
        if (invoice.getStatus() == CommonInvoiceStatus.UNPAID) {
            invoice.setPreviousStatus(CommonInvoiceStatus.UNPAID.name());
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(
                BAD_REVIEW_SUPPLEMENT_REQUIRED
                        + " source_invoice=" + invoice.getId()
                        + ";order=" + orderId
                        + ";task=" + taskId
                        + ";reason=" + reason,
                512
        ));
        invoiceRepository.save(invoice);
    }

    @Transactional
    public CommonInvoiceDetailsResponse approveReviewOrders(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanChangePositions(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        return approveReviewOrders(invoiceId, items);
    }

    private CommonInvoiceDetailsResponse approveReviewOrders(
            Long invoiceId,
            List<CommonInvoiceOrder> items
    ) {
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

        for (CommonInvoiceOrder item : candidates) {
            publicationApprovalService().validateExistingOrder(item.getOrder().getId());
        }
        for (CommonInvoiceOrder item : candidates) {
            Order order = item.getOrder();
            publicationApprovalService().approveExistingOrder(
                    order.getId(),
                    "invoiceId=" + invoiceId + ";source=approve_all"
            );
        }
        return invoiceAfterOrderPrelude(invoiceId);
    }

    private OrderPublicationApprovalService publicationApprovalService() {
        return publicationApprovalServiceProvider.getObject();
    }

    @Transactional
    public CommonInvoiceDetailsResponse detachOrder(Long invoiceId, Long orderId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanChangePositions(invoice);
        ensureCommonPaymentRouteAllowsCompositionChange(invoice);
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден в общем счете"));
        if (!invoice.getId().equals(item.getInvoice().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ относится к другому общему счету");
        }

        Order order = item.getOrder();
        if (item.isPaid()) {
            try {
                closeOrderAsPaidWithoutNextOrder(order, true);
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
        publicationBlockerService.reconcileInvoice(invoiceId);
        if (items.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.DISABLED);
            invoice.setNextReminderAt(null);
            invoice.setLastError("empty: в общем счете нет заказов");
            invoiceRepository.save(invoice);
            List<CommonInvoicePaymentRef> paymentRefs = paymentRefEvidenceRows(invoice);
            return new CommonInvoiceDetailsResponse(
                    toInvoiceSummary(invoice, List.of()),
                    List.of(),
                    List.of(),
                    List.of(),
                    toPaymentRefEvidence(paymentRefs, Map.of()),
                    paymentEvidenceToken(invoice, paymentRefs)
            );
        }
        if (isInvoiceReady(invoiceId) && invoice.getStatus() == CommonInvoiceStatus.COLLECTING) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            invoiceRepository.save(invoice);
            markInvoiceOrdersPublished(items);
        }
        return invoiceAfterOrderPrelude(invoiceId);
    }

    /**
     * Narrow compatibility path for deleting an untouched order created by the
     * next-order automation after cancellation of its source payment. This is
     * not a hard-delete bypass: it locks and revalidates the order, invoice and
     * membership, and refuses to detach anything carrying payment state.
     */
    @Transactional
    public boolean detachOrderForDeletion(Long orderId) {
        Order order = orderAggregateMutationLockService.lock(orderId);
        boolean autoCreated = nextOrderRequestRepository.findByCreatedOrderIdForUpdate(orderId).stream()
                .anyMatch(request -> request.getStatus() == NextOrderRequestStatus.CREATED
                        && request.getCreatedOrder() != null
                        && Objects.equals(orderId, request.getCreatedOrder().getId()));
        if (!autoCreated
                || !"Новый".equals(statusTitle(order))
                || order.getCounter() > 0
                || order.isComplete()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автосозданный следующий заказ уже изменен и не может быть безопасно удален"
            );
        }

        Optional<CommonInvoiceOrder> snapshot = invoiceOrderRepository.findByOrderIdWithInvoice(orderId);
        if (snapshot.isEmpty()) {
            return false;
        }
        Long invoiceId = snapshot.map(CommonInvoiceOrder::getInvoice)
                .map(CommonInvoice::getId)
                .orElseThrow(() -> invoiceMembershipChanged("у позиции отсутствует общий счет"));
        CommonInvoice invoice = lockedInvoiceAfterOrderPrelude(invoiceId)
                .orElseThrow(() -> invoiceMembershipChanged("общий счет исчез во время удаления заказа"));
        CommonInvoiceOrder item = invoiceOrderRepository.findMembershipByOrderIdForRead(orderId)
                .orElseThrow(() -> invoiceMembershipChanged("позиция исчезла во время удаления заказа"));
        Long currentInvoiceId = item.getInvoice() == null ? null : item.getInvoice().getId();
        if (!Objects.equals(invoiceId, currentInvoiceId)) {
            throw invoiceMembershipChanged("позиция перешла в другой общий счет");
        }
        ensureNoOperationInProgress(invoice);
        ensureDeletionDetachHasNoPaymentState(invoice, item);

        int deleted = invoiceOrderRepository.deleteByOrderId(orderId);
        if (deleted != 1) {
            throw invoiceMembershipChanged("позиция не была отвязана");
        }
        List<CommonInvoiceOrder> remainingItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (remainingItems.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.DISABLED);
            invoice.setAmountKopecks(0);
            invoice.setPaidKopecks(0);
            invoice.setNextReminderAt(null);
            invoice.setLastError("empty: автосозданный заказ удален после отмены оплаты");
            invoiceRepository.save(invoice);
        } else {
            recalculateInvoice(invoice, remainingItems);
            promoteCollectingInvoiceToReadyIfPossible(invoice, remainingItems);
        }
        publicationBlockerService.reconcileInvoice(invoiceId);
        return true;
    }

    private void ensureDeletionDetachHasNoPaymentState(CommonInvoice invoice, CommonInvoiceOrder item) {
        boolean itemHasPayment = item.isPaid()
                || item.isUnpaid()
                || item.getPaidAt() != null
                || !normalize(item.getPaymentMethod()).isBlank()
                || !normalize(item.getManualPaidBy()).isBlank()
                || !normalize(item.getManualPaymentComment()).isBlank()
                || !normalize(item.getManualPaymentReceiptUrl()).isBlank();
        boolean invoiceHasInitOrBinding = PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError()))
                || !normalize(invoice.getTbankOrderId()).isBlank()
                || !normalize(invoice.getTbankPaymentId()).isBlank()
                || !normalize(invoice.getTbankTerminalKey()).isBlank()
                || invoice.getTbankPaymentAmountKopecks() != null
                || invoice.getTbankPaymentCreatedAt() != null
                || !normalize(invoice.getPaymentUrl()).isBlank();
        boolean invoiceCanChange = invoice.getStatus() == CommonInvoiceStatus.COLLECTING
                || invoice.getStatus() == CommonInvoiceStatus.READY;
        boolean activeRefs = paymentRefRepository.existsByInvoice_IdAndStatusIn(
                invoice.getId(),
                DELETION_DETACH_BLOCKING_PAYMENT_REF_STATUSES
        );
        if (itemHasPayment || invoiceHasInitOrBinding || !invoiceCanChange || activeRefs) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Заказ связан с общим счетом, который уже содержит платежные данные; нужна ручная проверка"
            );
        }
    }

    @Transactional
    public CommonInvoiceDetailsResponse markPaid(
            Long invoiceId,
            ManualPaymentConfirmationRequest request,
            Principal principal
    ) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureGenericConfirmationDoesNotUseContractorSource(invoice);
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
        applyManualPaymentEvidence(invoice, items, request, principal);
        archiveAndClearCurrentPaymentRef(invoice, "manual_paid");
        closePaidInvoice(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    CommonInvoiceDetailsResponse markPaid(Long invoiceId) {
        return markPaid(
                invoiceId,
                new ManualPaymentConfirmationRequest("Внутреннее подтверждение", ""),
                () -> "system"
        );
    }

    @Transactional
    public CommonInvoiceDetailsResponse retryAttention(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (attentionError(invoice).startsWith(CommonBillingPublicationApprovalFailureMarker.ERROR_PREFIX)) {
            List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
            resolveAttentionByCurrentItems(invoice, items);
            return approveReviewOrders(invoiceId, items);
        }
        ensureAttentionCanBeRetried(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        closePaidInvoice(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
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
        return invoiceAfterOrderPrelude(invoiceId);
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
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse confirmPaymentInitCheck(Long invoiceId) {
        return confirmPaymentInitCheck(invoiceId, null);
    }

    @Transactional
    public CommonInvoiceDetailsResponse confirmPaymentInitCheck(
            Long invoiceId,
            CommonInvoicePaymentInitCheckRequest request
    ) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!isPaymentInitManualCheckAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета нет ручной проверки создания T-Bank ссылки");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureNoRecordedFullPaymentWithOpenItems(invoice, items);
        List<CommonInvoicePaymentRef> refs = paymentRefRepository.findByInvoiceIdForUpdate(invoice.getId());
        ensurePaymentEvidenceSnapshotMatches(invoice, refs, request);
        if (isMigrationPaymentRegistryAttention(invoice)) {
            ensureMigrationPaymentRegistryCanBeManuallyResolved(
                    invoice,
                    refs,
                    paymentPrelude.paymentLinksByOrder()
            );
        }
        resolvePreparedPaymentInitAfterManualCheck(invoice);
        resolveAttentionByCurrentItems(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    /**
     * Confirms bank-statement evidence against the immutable common-invoice
     * source selected for one contractor recipient. This endpoint deliberately
     * accepts a cumulative total, so retries and partial transfers are
     * idempotent and never get attributed to a newer successor source.
     */
    @Transactional
    public CommonInvoiceDetailsResponse confirmContractorPaymentSource(
            Long invoiceId,
            long confirmedTotalKopecks,
            LocalDateTime effectiveAt,
            String reason,
            Principal principal
    ) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        validateContractorCommonSourceConfirmation(
                invoice,
                confirmedTotalKopecks,
                effectiveAt,
                reason
        );
        ContractorPaymentAllocation sourceAllocation = contractorPaymentLiveRoutingService
                .validatedCommonConfirmationSource(invoiceId, invoice.getContractorAllocationId());
        if (confirmedTotalKopecks > sourceAllocation.getAmountKopecks()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвержденная сумма превышает сумму источника");
        }
        retireUnstartedSuccessorForLateSourceOrThrow(invoice);

        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В общем счете нет позиций для сверки");
        }
        long sourcePaidBaselineKopecks = Math.max(0L, sourceAllocation.getSourcePaidBaselineKopecks());
        long previousSourceTotal = Math.max(
                0L,
                invoice.getPaidKopecks() - sourcePaidBaselineKopecks
        );
        if (confirmedTotalKopecks < previousSourceTotal) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Подтвержденная сумма не может уменьшаться; возврат отражается отдельной операцией"
            );
        }
        if (confirmedTotalKopecks == previousSourceTotal && hasExactContractorSourceEvidence(invoice)) {
            return invoiceAfterOrderPrelude(invoiceId);
        }

        long invoiceConfirmedTotal;
        try {
            invoiceConfirmedTotal = Math.addExact(sourcePaidBaselineKopecks, confirmedTotalKopecks);
        } catch (ArithmeticException overflow) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвержденная сумма некорректна");
        }
        if (invoiceConfirmedTotal > invoice.getAmountKopecks()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвержденная сумма превышает сумму счета");
        }

        LocalDateTime observedAt = effectiveAt == null ? LocalDateTime.now() : effectiveAt;
        String actor = principal == null ? "" : normalize(principal.getName());
        String auditReason = CONTRACTOR_COMMON_SOURCE_CONFIRMATION_AUDIT_PREFIX
                + " source_total=" + confirmedTotalKopecks
                + "; оператор=" + limit(actor, 120)
                + "; основание=" + normalize(reason);
        mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_MANUAL);
        invoice.setManualPaidBy(actor);
        invoice.setManualPaymentComment(limit(auditReason, 1000));
        invoice.setManualConfirmedAt(observedAt);
        invoice.setPaidAt(observedAt);
        invoice.setPaidKopecks(invoiceConfirmedTotal);

        if (invoiceConfirmedTotal < invoice.getAmountKopecks()) {
            invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
            invoice.setNextReminderAt(LocalDateTime.now().plusDays(REMINDER_INTERVAL_DAYS));
            invoice.setLastError(null);
            invoiceRepository.save(invoice);
            scheduleContractorShadowReconcile(invoiceId);
            return invoiceAfterOrderPrelude(invoiceId);
        }

        ManualPaymentConfirmationRequest evidence = new ManualPaymentConfirmationRequest(auditReason, "");
        applyManualPaymentEvidence(invoice, items, evidence, principal);
        invoice.setManualPaymentComment(limit(auditReason, 1000));
        invoiceRepository.save(invoice);
        closePaidInvoice(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommonInvoiceDetailsResponse repairStandalonePaymentRouteConflict(Long invoiceId) {
        CommonInvoice snapshot = invoiceRepository.findByIdWithAccount(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(snapshot);
        if (!attentionError(snapshot).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ошибка отдельного платежного маршрута уже изменилась; обновите общий счет"
            );
        }

        List<Long> orderIds = invoiceOrderRepository.findOrderIdsByInvoiceId(invoiceId).stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        for (Long orderId : orderIds) {
            paymentLinkService().reconcileActiveLinkForOrder(orderId);
        }

        CommonInvoiceDetailsResponse repaired = writeTransaction(() ->
                repairStandalonePaymentRouteConflictLocked(invoiceId)
        );
        if (repaired != null
                && repaired.summary() != null
                && (CommonInvoiceStatus.READY.name().equals(repaired.summary().status())
                || CommonInvoiceStatus.PARTIALLY_PAID.name().equals(repaired.summary().status()))) {
            return sendInvoice(invoiceId, true);
        }
        return repaired;
    }

    /**
     * Retires an empty collecting shell only when it has no positions and no
     * payment history whatsoever. This recovers an invoice left behind after
     * all auto-created next orders were safely deleted.
     */
    @Transactional
    public CommonInvoiceDetailsResponse disableEmptyInvoice(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (!items.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Общий счет уже содержит заказы; обновите карточку контроля"
            );
        }
        if (invoice.getStatus() != CommonInvoiceStatus.COLLECTING
                && invoice.getStatus() != CommonInvoiceStatus.READY) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автозакрытие доступно только для пустого счета в сборе"
            );
        }
        boolean hasPaymentEvidence = invoice.getAmountKopecks() != 0
                || invoice.getPaidKopecks() != 0
                || invoice.getSentAt() != null
                || invoice.getPaidAt() != null
                || invoice.getClosedAt() != null
                || invoice.getClientReportedAt() != null
                || invoice.getManualConfirmedAt() != null
                || !normalize(invoice.getManualPaidBy()).isBlank()
                || !normalize(invoice.getManualPaymentComment()).isBlank()
                || !normalize(invoice.getManualPaymentReceiptUrl()).isBlank()
                || !attentionError(invoice).isBlank()
                || !normalize(invoice.getPaymentSuccessNotificationError()).isBlank()
                || hasCurrentCommonPaymentRoute(invoice)
                || hasFrozenCommonPaymentRoute(invoice);
        if (hasPaymentEvidence || !paymentRefRepository.findByInvoiceIdForUpdate(invoiceId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Пустой счет содержит платежные признаки и требует ручной сверки"
            );
        }

        invoice.setStatus(CommonInvoiceStatus.DISABLED);
        invoice.setAmountKopecks(0);
        invoice.setPaidKopecks(0);
        invoice.setNextReminderAt(null);
        invoice.setLastError("empty: в общем счете нет заказов");
        invoiceRepository.save(invoice);
        return invoiceDetails(invoice, List.of());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommonInvoiceDetailsResponse reportPaidByManualCardTransfer(
            Long invoiceId,
            CommonInvoiceManualCardPaymentRequest request,
            Principal principal
    ) {
        String reason = validateCommonInvoiceManualCardPaymentReason(request);
        CommonInvoice snapshot = invoiceRepository.findByIdWithAccount(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(snapshot);
        if (!attentionError(snapshot).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ручное зачисление из этой карточки доступно только для конфликта одиночных платежей"
            );
        }

        List<Long> orderIds = invoiceOrderRepository.findOrderIdsByInvoiceId(invoiceId).stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        reconcileStandaloneBankRoutesBeforeCommonManualPayment(orderIds);
        return writeTransaction(() -> reportPaidByManualCardTransferLocked(
                invoiceId,
                reason,
                principal
        ));
    }

    private CommonInvoiceDetailsResponse reportPaidByManualCardTransferLocked(
            Long invoiceId,
            String reason,
            Principal principal
    ) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!attentionError(invoice).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платежное состояние общего счета изменилось; обновите карточку"
            );
        }

        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (items.isEmpty() || !allOrdersReady(items)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ручную оплату можно зачислить только после готовности всех заказов общего счета"
            );
        }
        ensureNoCurrentCommonTbankPaymentForManualCard(invoice);
        ensureCommonPaymentRefsSafeForManualCard(
                paymentRefRepository.findByInvoiceIdForUpdate(invoiceId)
        );

        Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(
                invoice,
                items,
                paymentPrelude.paymentLinksByOrder()
        );
        List<Long> closedRouteIds = closeStandaloneRoutesForCommonManualPaymentOrThrow(
                paymentLinksRequiringCommonInvoiceRouteCheck(
                        paymentPrelude.paymentLinksByOrder(),
                        items,
                        appliedStandalonePayments
                ),
                invoiceId,
                reason,
                principal
        );

        refreshInvoiceAmounts(invoice, items);
        long manualAmountKopecks = remainingKopecks(invoice);
        if (manualAmountKopecks > 0) {
            ManualPaymentConfirmationRequest evidence = new ManualPaymentConfirmationRequest(reason, "");
            applyManualPaymentEvidence(invoice, items, evidence, principal);
        }
        closePaidInvoice(invoice, items);
        if (manualAmountKopecks > 0) {
            String actor = principal == null ? "" : normalize(principal.getName());
            manualCardPaymentReviewNotificationService.notifyCommonInvoiceAfterCommit(
                    new ManualCardPaymentReviewNotificationService.CommonInvoiceReviewRequest(
                            invoice.getId(),
                            normalize(invoice.getTitle()).isBlank()
                                    ? normalize(invoice.getAccount() == null ? null : invoice.getAccount().getName())
                                    : normalize(invoice.getTitle()),
                            manualAmountKopecks,
                            actor,
                            reason,
                            items.stream()
                                    .map(CommonInvoiceOrder::getOrder)
                                    .filter(Objects::nonNull)
                                    .map(Order::getId)
                                    .filter(Objects::nonNull)
                                    .toList(),
                            closedRouteIds
                    )
            );
        }
        return invoiceAfterOrderPrelude(invoiceId);
    }

    private CommonInvoiceDetailsResponse repairStandalonePaymentRouteConflictLocked(Long invoiceId) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!attentionError(invoice).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ошибка отдельного платежного маршрута уже изменилась; обновите общий счет"
            );
        }

        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(
                invoice,
                items,
                paymentPrelude.paymentLinksByOrder()
        );
        closeProvablyUnstartedStandaloneRoutesOrThrow(
                paymentLinksRequiringCommonInvoiceRouteCheck(
                        paymentPrelude.paymentLinksByOrder(),
                        items,
                        appliedStandalonePayments
                ),
                invoiceId
        );
        resolveAttentionByCurrentItems(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommonInvoiceDetailsResponse recoverUnsentPaymentInitTlsFailure(Long invoiceId) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!isDefinitelyUnsentPaymentInitTlsFailure(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автопочинка доступна только для TLS-сбоя, который произошел до отправки запроса в T-Bank"
            );
        }
        ensureInvoiceHasNoCurrentProviderEvidence(invoice);

        List<CommonInvoicePaymentRef> refs = paymentRefRepository.findByInvoiceIdForUpdate(invoiceId);
        ensureUnsentTlsPaymentRefsCanBeRecovered(invoice, refs);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureNoCompetingStandalonePaymentLinks(paymentPrelude.paymentLinksByOrder());
        List<CommonInvoicePaymentRef> recoverableRefs = refs.stream()
                .filter(this::isPreparedPaymentRef)
                .toList();
        recoverableRefs.forEach(ref -> {
                    String previousReason = normalize(ref.getReason());
                    ref.setStatus(PAYMENT_REF_ARCHIVED);
                    ref.setReason(limit(
                            PAYMENT_INIT_TLS_SAFE_ARCHIVED_REASON_PREFIX
                                    + (previousReason.isBlank() ? "" : "; previous=" + previousReason),
                            160
                    ));
                });
        paymentRefRepository.saveAll(recoverableRefs);

        resolveAttentionByCurrentItems(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
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
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public void resolveWhatsappGroupTail(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        String error = attentionError(invoice);
        if (!(error.startsWith("whatsapp_group_missing") || error.contains("whatsapp-групп"))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ошибка WhatsApp общего счета уже изменилась; обновите карточку контроля"
            );
        }
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
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
        return invoiceAfterOrderPrelude(invoiceId);
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
            invoice.setPaidKopecks(invoice.getAmountKopecks());
            if (invoice.getPaidAt() == null) {
                invoice.setPaidAt(LocalDateTime.now());
            }
            markInvoicePaidClosed(invoice);
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
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
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
            return invoiceAfterOrderPrelude(invoiceId);
        }

        List<CommonInvoiceOrder> sortedItems = items.stream()
                .filter(item -> !item.isPaid())
                .sorted(Comparator.comparing(item -> {
                    Order order = item.getOrder();
                    return order == null || order.getId() == null ? Long.MAX_VALUE : order.getId();
                }))
                .toList();
        List<String> closeFailures = new ArrayList<>();
        Set<Long> closedOrderIds = new HashSet<>();
        for (CommonInvoiceOrder item : sortedItems) {
            long itemAmount = Math.max(0, item.getAmountKopecks());
            if (itemAmount > remainingPaymentKopecks) {
                break;
            }
            try {
                closeOrderAsPaidWithoutNextOrder(item.getOrder());
                if (item.getOrder() != null && item.getOrder().getId() != null) {
                    closedOrderIds.add(item.getOrder().getId());
                }
                item.setPaid(true);
                item.setUnpaid(false);
                item.setPaidAt(LocalDateTime.now());
                item.setPaymentMethod(PAYMENT_METHOD_TBANK);
                remainingPaymentKopecks -= itemAmount;
            } catch (Exception e) {
                closeFailures.add(orderFailureLabel(item));
                log.warn("Не удалось закрыть заказ {} поздним платежом старой ссылки общего счета {}",
                        item.getOrder() == null ? null : item.getOrder().getId(), invoiceId, e);
                break;
            }
        }
        invoiceOrderRepository.saveAll(items);
        mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_TBANK);
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
            return invoiceAfterOrderPrelude(invoiceId);
        }

        finishLatePaymentApply(invoice, refs, items, remainingPaymentKopecks, closedOrderIds);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markUnpaid(Long invoiceId) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
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
        scheduleContractorShadowRelease(invoiceId);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional(readOnly = true)
    public CommonInvoiceArchivePreviewResponse archivePreview(Long invoiceId) {
        CommonInvoice invoice = invoiceRepository.findByIdWithAccount(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        return buildArchivePreview(invoice, invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId));
    }

    @Transactional
    public CommonInvoiceDetailsResponse archiveInvoice(
            Long invoiceId,
            CommonInvoiceCloseRequest request,
            Principal principal
    ) {
        if (request == null || !request.confirm()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Архивирование требует confirm=true");
        }
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        CommonInvoiceArchivePreviewResponse preview = buildArchivePreview(invoice, items);
        if (!preview.allowed()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Общий счет нельзя архивировать: " + String.join("; ", preview.blockers())
            );
        }

        for (CommonInvoiceOrder item : items) {
            Order order = item.getOrder();
            item.setArchiveSourceOrderStatusTitle(OrderManualArchivePolicy.statusTitle(order));
            try {
                orderStatusTransitionService.changeStatusForCommonBillingOrder(order.getId(), STATUS_ARCHIVE);
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Не удалось архивировать заказ #" + order.getId() + ": " + exception.getMessage(),
                        exception
                );
            }
        }

        invoiceOrderRepository.saveAll(items);
        archiveAndClearCurrentPaymentRef(invoice, "manual_archive");
        closeInvoice(invoice, CommonInvoiceStatus.ARCHIVED, "MANUAL_ARCHIVE", principal);
        invoiceRepository.save(invoice);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse restoreLiveArchivedInvoice(Long invoiceId, Principal principal) {
        CommonInvoice invoice = lockedInvoice(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if (invoice.getStatus() != CommonInvoiceStatus.ARCHIVED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Из live можно восстановить только общий счет, закрытый вручную"
            );
        }

        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        for (CommonInvoiceOrder item : items) {
            Order order = item.getOrder();
            String targetStatus = normalize(item.getArchiveSourceOrderStatusTitle());
            if (!OrderManualArchivePolicy.ALLOWED_SOURCE_STATUSES.contains(targetStatus)) {
                targetStatus = STATUS_TO_CHECK;
            }
            try {
                orderStatusTransitionService.changeStatusForPrivilegedCommonBillingOrder(order.getId(), targetStatus);
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Не удалось восстановить заказ #" + order.getId() + ": " + exception.getMessage(),
                        exception
                );
            }
        }

        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        invoice.setToken(randomToken());
        invoice.setPreviousStatus(null);
        invoice.setClosedAt(null);
        invoice.setClosedBy(null);
        invoice.setCloseReason(null);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markBan(Long invoiceId, Principal principal) {
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
        closeInvoice(invoice, CommonInvoiceStatus.BAN, "BAN", principal);
        invoiceRepository.save(invoice);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    CommonInvoiceDetailsResponse markBan(Long invoiceId) {
        return markBan(invoiceId, () -> "system");
    }

    private CommonInvoiceArchivePreviewResponse buildArchivePreview(
            CommonInvoice invoice,
            List<CommonInvoiceOrder> items
    ) {
        List<String> invoiceBlockers = new ArrayList<>();
        if (invoice.getStatus() != CommonInvoiceStatus.COLLECTING) {
            invoiceBlockers.add("счет должен находиться в статусе сбора");
        }
        if (invoice.getPaidKopecks() > 0 || items.stream().anyMatch(CommonInvoiceOrder::isPaid)) {
            invoiceBlockers.add("по счету уже есть оплата");
        }
        if (invoice.getSentAt() != null
                || !normalize(invoice.getPaymentUrl()).isBlank()
                || !normalize(invoice.getTbankOrderId()).isBlank()
                || !normalize(invoice.getTbankPaymentId()).isBlank()) {
            invoiceBlockers.add("по счету уже начат платежный процесс");
        }
        if (items.isEmpty()) {
            invoiceBlockers.add("в общем счете нет заказов");
        }

        List<CommonInvoiceArchiveOrderPreview> orderPreviews = items.stream()
                .map(item -> {
                    Order order = item.getOrder();
                    List<String> blockers = archiveOrderBlockers(order);
                    return new CommonInvoiceArchiveOrderPreview(
                            order == null ? null : order.getId(),
                            order == null || order.getCompany() == null ? "" : order.getCompany().getTitle(),
                            OrderManualArchivePolicy.statusTitle(order),
                            blockers.isEmpty(),
                            blockers
                    );
                })
                .toList();
        orderPreviews.stream()
                .filter(order -> !order.allowed())
                .forEach(order -> invoiceBlockers.add(
                        "заказ #" + order.orderId() + ": " + String.join(", ", order.blockers())
                ));

        return new CommonInvoiceArchivePreviewResponse(
                invoice.getId(),
                invoiceBlockers.isEmpty(),
                items.size(),
                orderPreviews,
                List.copyOf(invoiceBlockers)
        );
    }

    private List<String> archiveOrderBlockers(Order order) {
        List<String> blockers = new ArrayList<>();
        if (order == null || order.getId() == null) {
            blockers.add("заказ не найден");
            return blockers;
        }
        if (!OrderManualArchivePolicy.isAllowed(order)) {
            blockers.add("статус \"" + OrderManualArchivePolicy.statusTitle(order) + "\" не разрешен");
        }
        if (recoveryGateService.hasActiveRecoveryTasks(order.getId())) {
            blockers.add("есть активная задача восстановления");
        }
        BadReviewTaskSummary badReviewSummary = badReviewTaskService.getSummaryForOrder(order.getId());
        if (badReviewSummary != null && badReviewSummary.pending() > 0) {
            blockers.add("есть активная плохая задача");
        }
        nextOrderRequestRepository.findBySourceOrderId(order.getId())
                .filter(next -> next.getStatus() == NextOrderRequestStatus.PENDING
                        || next.getStatus() == NextOrderRequestStatus.FAILED)
                .ifPresent(next -> blockers.add("есть незавершенный запрос следующего заказа"));
        return blockers;
    }

    private void closeInvoice(
            CommonInvoice invoice,
            CommonInvoiceStatus targetStatus,
            String closeReason,
            Principal principal
    ) {
        invoice.setPreviousStatus(invoice.getStatus() == null ? null : invoice.getStatus().name());
        invoice.setStatus(targetStatus);
        invoice.setClosedAt(LocalDateTime.now());
        String actor = principal == null ? "system" : normalize(principal.getName());
        invoice.setClosedBy(limit(actor.isBlank() ? "system" : actor, 160));
        invoice.setCloseReason(closeReason);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
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
        String cleanEmail = normalize(email);
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите e-mail для электронного чека");
        }

        PreparedCommonPaymentInit prepared = writeTransaction(() -> preparePaymentInit(cleanToken(token), cleanEmail));
        if (prepared.deferredFailure() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, prepared.deferredFailure());
        }
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
            boolean tlsCertificateFailureBeforeHttp =
                    CommonPaymentInitFailureClassifier.isCertificateTlsFailureBeforeHttpResponse(e);
            String persistedError = (tlsCertificateFailureBeforeHttp
                    ? CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_ERROR_CODE
                    : "payment_init_exception")
                    + ": " + readableException(e);
            String paymentRefReason = tlsCertificateFailureBeforeHttp
                    ? CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_REF_REASON
                    : CommonPaymentInitFailureClassifier.LEGACY_TLS_BEFORE_HTTP_REF_REASON;
            writeTransaction(() -> {
                failPaymentInit(prepared, persistedError, paymentRefReason);
                return null;
            });
            throw e;
        }
        String responseMismatch = paymentInitResponseMismatch(prepared, response);
        if (responseMismatch != null) {
            executePaymentInitFailureWrite(
                    prepared,
                    response,
                    () -> failMismatchedPaymentInit(prepared, response, responseMismatch)
            );
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Т-Банк вернул несогласованный ответ. Платеж отправлен на ручную сверку."
            );
        }
        String paymentUrl;
        try {
            paymentUrl = PaymentUrlPolicy.require(
                    response.paymentUrl(),
                    PaymentUrlPolicy.Purpose.TBANK_PAYMENT,
                    HttpStatus.BAD_GATEWAY,
                    "Т-Банк вернул недопустимую ссылку оплаты"
            );
        } catch (ResponseStatusException e) {
            executePaymentInitFailureWrite(
                    prepared,
                    response,
                    () -> failUnsafePaymentUrl(prepared, response)
            );
            throw e;
        }
        PaymentInitFinishResult result;
        try {
            result = writeTransaction(() -> finishPaymentInit(prepared, response, paymentUrl));
        } catch (RuntimeException finishFailure) {
            boolean identityCollision = isPaymentIdentityConstraintViolation(finishFailure);
            boolean currentRegistryCollision = isCurrentPaymentRegistryConstraintViolation(finishFailure);
            try {
                writeTransaction(() -> {
                    if (currentRegistryCollision) {
                        quarantineCurrentPaymentRegistryConstraint(prepared, response, finishFailure);
                    } else {
                        failMismatchedPaymentInit(
                                prepared,
                                response,
                                "finish_exception:" + readableException(finishFailure)
                        );
                    }
                    return null;
                });
            } catch (RuntimeException quarantineFailure) {
                finishFailure.addSuppressed(quarantineFailure);
                log.error(
                        "Не удалось пометить общий счет {} для ручной сверки после ошибки фиксации T-Bank Init",
                        prepared.invoiceId(),
                    quarantineFailure
                );
            }
            if (identityCollision) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "PaymentId уже используется другим платежом; ссылка отправлена на ручную сверку",
                        finishFailure
                );
            }
            if (currentRegistryCollision) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "У общего счета уже есть другая активная платежная ссылка; платеж отправлен на ручную сверку",
                        finishFailure
                );
            }
            throw finishFailure;
        }
        if (result.failureStatus() != null) {
            throw new ResponseStatusException(result.failureStatus(), result.failureMessage());
        }
        return result.response();
    }

    private void executePaymentInitFailureWrite(
            PreparedCommonPaymentInit prepared,
            TbankInitResponse response,
            Runnable action
    ) {
        try {
            writeTransaction(() -> {
                action.run();
                return null;
            });
        } catch (RuntimeException failure) {
            if (!isPaymentIdentityConstraintViolation(failure)) {
                throw failure;
            }
            writeTransaction(() -> {
                quarantinePaymentInitIdentityConstraint(prepared, response, failure);
                return null;
            });
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "PaymentId уже связан с другим платежом; ссылка отправлена на ручную сверку",
                    failure
            );
        }
    }

    private PreparedCommonPaymentInit preparePaymentInit(String token, String cleanEmail) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceByTokenAfterStandalonePaymentPrelude(token);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureNoOperationInProgress(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        try {
            Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(
                    invoice,
                    items,
                    paymentPrelude.paymentLinksByOrder()
            );
            closeProvablyUnstartedStandaloneRoutesOrThrow(
                    paymentLinksRequiringCommonInvoiceRouteCheck(
                            paymentPrelude.paymentLinksByOrder(),
                            items,
                            appliedStandalonePayments
                    ),
                    invoice.getId()
            );
        } catch (ResponseStatusException conflict) {
            markStandalonePaymentRouteConflict(invoice, conflict);
            return new PreparedCommonPaymentInit(
                    invoice.getId(),
                    null,
                    cleanEmail,
                    0,
                    null,
                    null,
                    null,
                    "У общего счета обнаружен другой незакрытый способ оплаты; нужна ручная сверка"
            );
        }
        refreshInvoiceAmounts(invoice, items);
        long remaining = remainingKopecks(invoice);
        if (!canAcceptPublicPayment(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет еще не готов к оплате");
        }
        if (remaining <= 0) {
            if (!allOrdersReady(items)) {
                return new PreparedCommonPaymentInit(
                        invoice.getId(),
                        null,
                        cleanEmail,
                        0,
                        null,
                        null,
                        new PublicPaymentInitResponse("", "", invoice.getStatus().name()),
                        null
                );
            }
            closePaidInvoice(invoice, items);
            return new PreparedCommonPaymentInit(
                    invoice.getId(),
                    null,
                    cleanEmail,
                    0,
                    null,
                    null,
                    new PublicPaymentInitResponse("", "", invoice.getStatus().name()),
                    null
            );
        }

        ensureCommonPaymentRouteSelected(invoice, remaining);
        if (!isTbankCommonRoute(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Для общего счета выбран другой способ оплаты. Используйте реквизиты на странице счета."
            );
        }
        if (!runtimeSettingsService.isPaymentLinksEnabled() || !runtimeSettingsService.isTbankEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежные ссылки выключены в настройках");
        }

        String cachedPaymentUrl = PaymentUrlPolicy.safe(
                invoice.getPaymentUrl(),
                PaymentUrlPolicy.Purpose.TBANK_PAYMENT
        );
        boolean hasPersistedProviderRef = !normalize(invoice.getTbankPaymentId()).isBlank()
                || !normalize(invoice.getTbankOrderId()).isBlank();
        if (hasPersistedProviderRef && cachedPaymentUrl.isBlank()) {
            String persistedProviderLabel = paymentRefLabel(
                    invoice.getTbankOrderId(),
                    invoice.getTbankPaymentId()
            );
            archiveAndClearCurrentPaymentRef(invoice, "payment_cached_invalid_url");
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit(
                    "payment_cached_invalid_url: Сохраненная ссылка платежа "
                            + persistedProviderLabel
                            + " отсутствует или имеет недопустимый формат; нужна ручная сверка",
                    512
            ));
            invoiceRepository.save(invoice);
            return new PreparedCommonPaymentInit(
                    invoice.getId(),
                    null,
                    cleanEmail,
                    remaining,
                    null,
                    null,
                    null,
                    "Сохраненная ссылка Т-Банка отсутствует или имеет недопустимый формат"
            );
        }

        if (!cachedPaymentUrl.isBlank()
                && invoice.getTbankPaymentAmountKopecks() != null
                && invoice.getTbankPaymentAmountKopecks() == remaining
                && invoice.getTbankPaymentCreatedAt() != null
                && invoice.getTbankPaymentCreatedAt().plus(properties.getRedirectDue()).isAfter(LocalDateTime.now())) {
            CommonInvoicePaymentRef currentAnchor = lockedPaymentRefByProviderBinding(
                    invoice.getTbankOrderId(),
                    invoice.getTbankPaymentId()
            ).filter(ref -> Objects.equals(invoice.getId(), paymentRefInvoiceId(ref)))
                    .filter(ref -> PAYMENT_REF_CURRENT.equals(
                            normalize(ref.getStatus()).toUpperCase(Locale.ROOT)
                    ))
                    .orElse(null);
            if (currentAnchor == null) {
                quarantineMissingCurrentPaymentAnchor(
                        invoice,
                        invoice.getTbankOrderId(),
                        invoice.getTbankPaymentId()
                );
                return new PreparedCommonPaymentInit(
                        invoice.getId(),
                        null,
                        cleanEmail,
                        remaining,
                        null,
                        null,
                        null,
                        "Платежная ссылка не прошла проверку durable-реестра"
                );
            }
            return new PreparedCommonPaymentInit(
                    invoice.getId(),
                    null,
                    cleanEmail,
                    remaining,
                    null,
                    null,
                    new PublicPaymentInitResponse(
                            cachedPaymentUrl,
                            invoice.getTbankPaymentId(),
                            invoice.getStatus().name()
                    ),
                    null
            );
        }

        if (hasPersistedProviderRef) {
            archiveAndClearCurrentPaymentRef(invoice, "payment_link_expired_before_replacement");
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    "payment_init_conflict: предыдущая T-Bank ссылка истекла и отправлена на отмену; "
                            + "новую ссылку можно создать после завершения отмены и ручной проверки",
                    512
            ));
            invoiceRepository.save(invoice);
            return new PreparedCommonPaymentInit(
                    invoice.getId(),
                    null,
                    cleanEmail,
                    remaining,
                    null,
                    null,
                    null,
                    "Предыдущая ссылка Т-Банка отменяется. Повторите после ручной проверки."
            );
        }

        ensureNoBlockingPaymentRefsForNewInit(invoice);

        PaymentProfile profile = lockedCommonPaymentProfile(invoice);
        TbankPaymentProfile runtimeProfile = normalize(invoice.getPaymentRouteTerminalKey()).isBlank()
                ? paymentProfileService.toRuntime(profile)
                : paymentProfileService.toRuntimeForTerminal(profile, invoice.getPaymentRouteTerminalKey());
        if (runtimeProfile == null) {
            runtimeProfile = paymentProfileService.toRuntime(profile);
        }
        if (!runtimeProfile.hasCredentials()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не заданы TerminalKey или Password Т-Банка");
        }

        String tbankOrderId = groupTbankOrderId(invoice);
        CommonInvoicePaymentRef preparedRef = createPreparedPaymentInitRef(
                invoice,
                tbankOrderId,
                runtimeProfile,
                remaining
        );
        invoice.setPayerEmail(cleanEmail);
        invoice.setTbankOrderId(tbankOrderId);
        invoice.setTbankPaymentId(null);
        invoice.setTbankTerminalKey(runtimeProfile.terminalKey());
        invoice.setTbankPaymentAmountKopecks(remaining);
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now());
        invoice.setPaymentUrl(null);
        invoice.setLastError(PAYMENT_INIT_IN_PROGRESS);
        invoiceRepository.save(invoice);
        return new PreparedCommonPaymentInit(
                invoice.getId(),
                preparedRef.getId(),
                cleanEmail,
                remaining,
                runtimeProfile,
                tbankOrderId,
                null,
                null
        );
    }

    private PaymentInitFinishResult finishPaymentInit(
            PreparedCommonPaymentInit prepared,
            TbankInitResponse response,
            String paymentUrl
    ) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        CommonInvoicePaymentRef preparedRef = lockedPreparedPaymentRef(prepared).orElse(null);
        if (hasForeignPaymentIdBinding(
                response == null ? null : response.paymentId(),
                invoice.getId(),
                preparedRef == null ? prepared.paymentRefId() : preparedRef.getId()
        )) {
            String collisionPaymentId = normalize(response == null ? null : response.paymentId());
            markPreparedPaymentInitConflict(
                    invoice,
                    prepared,
                    "response_payment_id_collision:" + collisionPaymentId
            );
            if (matchesPreparedCurrentIntent(invoice, prepared)) {
                clearCurrentPaymentRef(invoice);
            } else {
                invoice.setPaymentUrl(null);
            }
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    "payment_init_response_collision: PaymentId " + collisionPaymentId
                            + " уже связан с другим платежом; "
                            + "ссылка не выдана, нужна ручная сверка",
                    512
            ));
            invoiceRepository.save(invoice);
            return new PaymentInitFinishResult(
                    null,
                    HttpStatus.CONFLICT,
                    "T-Bank вернул уже используемый PaymentId. Нужна ручная сверка."
            );
        }
        PaymentInitFinishResult webhookResult = paymentInitAlreadyHandledByWebhook(
                invoice,
                prepared,
                preparedRef,
                response,
                paymentUrl
        );
        if (webhookResult != null) {
            return webhookResult;
        }
        boolean currentIntentMatches = matchesPreparedCurrentIntent(invoice, prepared);
        if (!PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError()))) {
            boolean completedByEarlyWebhook = currentIntentMatches
                    && !normalize(invoice.getTbankPaymentId()).isBlank()
                    && normalize(invoice.getTbankPaymentId()).equals(normalize(response.paymentId()));
            if (completedByEarlyWebhook) {
                return new PaymentInitFinishResult(
                        new PublicPaymentInitResponse(paymentUrl, response.paymentId(), invoice.getStatus().name()),
                        null,
                        null
                );
            }
            recordInitializedPaymentRef(invoice, prepared, response, "init_finalized_after_invoice_changed");
            clearCurrentPaymentRef(invoice);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    "payment_init_conflict: состояние общего счета изменилось после создания платежа; нужна ручная сверка",
                    512
            ));
            invoiceRepository.save(invoice);
            return new PaymentInitFinishResult(
                    null,
                    HttpStatus.CONFLICT,
                    "Общий счет изменился во время создания платежной ссылки"
            );
        }
        if (!response.success()) {
            recordInitializedPaymentRef(invoice, prepared, response, "tbank_init_failed");
            clearCurrentPaymentRef(invoice);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("tbank_init_failed: " + response.errorText(), 512));
            invoiceRepository.save(invoice);
            return new PaymentInitFinishResult(null, HttpStatus.BAD_GATEWAY, response.errorText());
        }

        if (!currentIntentMatches
                || (!normalize(invoice.getTbankPaymentId()).isBlank()
                && !normalize(invoice.getTbankPaymentId()).equals(normalize(response.paymentId())))) {
            recordInitializedPaymentRef(invoice, prepared, response, "init_current_intent_mismatch");
            clearCurrentPaymentRef(invoice);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    "payment_init_conflict: сохраненные реквизиты создания платежа изменились; нужна ручная сверка",
                    512
            ));
            invoiceRepository.save(invoice);
            return new PaymentInitFinishResult(
                    null,
                    HttpStatus.CONFLICT,
                    "Реквизиты платежа изменились. Нужна ручная сверка."
            );
        }

        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        refreshInvoiceAmounts(invoice, items);
        if (!canAcceptPublicPayment(invoice) || remainingKopecks(invoice) != prepared.remainingKopecks()) {
            recordInitializedPaymentRef(invoice, prepared, response, "init_conflict_after_amount_changed");
            clearCurrentPaymentRef(invoice);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit(
                    "payment_init_conflict: T-Bank создал ссылку "
                            + paymentRefLabel(prepared.tbankOrderId(), response.paymentId())
                            + " на " + amountRubles(prepared.remainingKopecks())
                            + " руб., но состав или сумма общего счета изменились; нужна ручная сверка",
                    512
            ));
            invoiceRepository.save(invoice);
            return new PaymentInitFinishResult(
                    null,
                    HttpStatus.CONFLICT,
                    "Состав или сумма общего счета изменились. Повторите оплату."
            );
        }

        activatePreparedPaymentRef(invoice, prepared, preparedRef, response);
        invoice.setPayerEmail(prepared.email());
        invoice.setTbankOrderId(prepared.tbankOrderId());
        invoice.setTbankPaymentId(response.paymentId());
        invoice.setTbankTerminalKey(prepared.runtimeProfile().terminalKey());
        invoice.setTbankPaymentAmountKopecks(prepared.remainingKopecks());
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now());
        invoice.setPaymentUrl(paymentUrl);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        return new PaymentInitFinishResult(
                new PublicPaymentInitResponse(paymentUrl, response.paymentId(), invoice.getStatus().name()),
                null,
                null
        );
    }

    private void activatePreparedPaymentRef(
            CommonInvoice invoice,
            PreparedCommonPaymentInit prepared,
            CommonInvoicePaymentRef preparedRef,
            TbankInitResponse response
    ) {
        if (invoice == null || prepared == null || preparedRef == null || response == null) {
            throw invoiceMembershipChanged("не найдена durable-запись создаваемой T-Bank ссылки");
        }
        String status = normalize(preparedRef.getStatus()).toUpperCase(Locale.ROOT);
        if (!PAYMENT_REF_INIT_PREPARED.equals(status) && !PAYMENT_REF_CURRENT.equals(status)) {
            throw invoiceMembershipChanged("T-Bank ссылка уже перешла в состояние " + status);
        }
        if (!matchesPreparedPaymentRef(preparedRef, prepared)) {
            throw invoiceMembershipChanged("durable-запись T-Bank ссылки сменила реквизиты");
        }
        preparedRef.setTbankPaymentId(limit(response.paymentId(), 64));
        preparedRef.setTbankTerminalKey(limit(response.terminalKey(), 64));
        preparedRef.setAmountKopecks(response.amount());
        preparedRef.setStatus(PAYMENT_REF_CURRENT);
        preparedRef.setReason("provider_init_active");
        paymentRefRepository.save(preparedRef);
        // Materialize the unique PaymentId registry before the non-unique invoice
        // projection is updated. A concurrent duplicate fails here and is
        // quarantined by the outer recovery transaction.
        entityManager.flush();
    }

    private String paymentInitResponseMismatch(
            PreparedCommonPaymentInit prepared,
            TbankInitResponse response
    ) {
        if (response == null) {
            return "response_missing";
        }
        if (!response.success()) {
            return "success_false:error_code=" + normalize(response.errorCode());
        }
        if (!"0".equals(normalize(response.errorCode()))) {
            return "error_code_mismatch:" + normalize(response.errorCode());
        }
        if (normalize(response.paymentId()).isBlank()) {
            return "payment_id_missing";
        }
        if (!normalize(prepared.tbankOrderId()).equals(normalize(response.orderId()))) {
            return "order_id_mismatch";
        }
        String expectedTerminalKey = prepared.runtimeProfile() == null
                ? ""
                : normalize(prepared.runtimeProfile().terminalKey());
        if (expectedTerminalKey.isBlank()
                || !expectedTerminalKey.equals(normalize(response.terminalKey()))) {
            return "terminal_key_mismatch";
        }
        if (response.amount() == null || response.amount() != prepared.remainingKopecks()) {
            return "amount_mismatch";
        }
        return null;
    }

    private void failMismatchedPaymentInit(
            PreparedCommonPaymentInit prepared,
            TbankInitResponse response,
            String mismatch
    ) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null) {
            return;
        }
        recordInitializedPaymentRef(
                invoice,
                prepared,
                response,
                "init_response_mismatch:" + normalize(mismatch)
        );
        if (matchesPreparedCurrentIntent(invoice, prepared)) {
            clearCurrentPaymentRef(invoice);
        } else {
            invoice.setPaymentUrl(null);
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setPaymentUrl(null);
        invoice.setLastError(limit(
                "payment_init_response_mismatch: T-Bank вернул несогласованные реквизиты ("
                        + normalize(mismatch)
                        + ") для " + paymentRefLabel(response == null ? null : response.orderId(),
                        response == null ? null : response.paymentId())
                        + "; ссылка не выдана, нужна ручная сверка",
                512
        ));
        invoiceRepository.save(invoice);
    }

    private void failUnsafePaymentUrl(PreparedCommonPaymentInit prepared, TbankInitResponse response) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null) {
            return;
        }
        recordInitializedPaymentRef(invoice, prepared, response, "unsafe_tbank_payment_url");
        if (PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError()))) {
            if (matchesPreparedCurrentIntent(invoice, prepared)) {
                clearCurrentPaymentRef(invoice);
            } else {
                invoice.setPaymentUrl(null);
            }
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    "payment_init_invalid_url: T-Bank создал платеж "
                            + paymentRefLabel(prepared.tbankOrderId(), response.paymentId())
                            + ", но вернул недопустимую ссылку; нужна ручная сверка",
                    512
            ));
            invoiceRepository.save(invoice);
        }
    }

    private void failPaymentInit(
            PreparedCommonPaymentInit prepared,
            String error,
            String paymentRefReason
    ) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null || !PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError()))) {
            return;
        }
        markPreparedPaymentInitConflict(invoice, prepared, paymentRefReason);
        if (matchesPreparedCurrentIntent(invoice, prepared)) {
            clearCurrentPaymentRef(invoice);
        } else {
            invoice.setPaymentUrl(null);
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(error + "; проверьте банк вручную перед повторной оплатой", 512));
        invoiceRepository.save(invoice);
    }

    private void quarantinePaymentInitIdentityConstraint(
            PreparedCommonPaymentInit prepared,
            TbankInitResponse response,
            RuntimeException failure
    ) {
        if (prepared == null) {
            return;
        }
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null) {
            return;
        }
        CommonInvoicePaymentRef anchor = lockedPreparedPaymentRef(prepared).orElse(null);
        String paymentId = normalize(response == null ? null : response.paymentId());
        if (anchor != null) {
            anchor.setStatus(PAYMENT_REF_INIT_CONFLICT);
            anchor.setReason(limit("payment_identity_constraint:" + paymentId, 160));
            paymentRefRepository.save(anchor);
        }
        if (matchesPreparedCurrentIntent(invoice, prepared)) {
            clearCurrentPaymentRef(invoice);
        } else {
            invoice.setPaymentUrl(null);
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(
                "payment_init_response_collision: PaymentId " + paymentId
                        + " нарушил уникальность durable-реестра; нужна ручная сверка ("
                        + readableException(failure) + ")",
                512
        ));
        invoiceRepository.save(invoice);
    }

    private void quarantineCurrentPaymentRegistryConstraint(
            PreparedCommonPaymentInit prepared,
            TbankInitResponse response,
            RuntimeException failure
    ) {
        if (prepared == null) {
            return;
        }
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null) {
            return;
        }
        CommonInvoicePaymentRef anchor = lockedPreparedPaymentRef(prepared).orElse(null);
        if (anchor != null) {
            String paymentId = normalize(response == null ? null : response.paymentId());
            if (!paymentId.isBlank() && !hasForeignPaymentIdBinding(paymentId, invoice.getId(), anchor.getId())) {
                anchor.setTbankPaymentId(limit(paymentId, 64));
            }
            String terminalKey = normalize(response == null ? null : response.terminalKey());
            if (!terminalKey.isBlank()) {
                anchor.setTbankTerminalKey(limit(terminalKey, 64));
            }
            if (response != null && response.amount() != null && response.amount() > 0) {
                anchor.setAmountKopecks(response.amount());
            }
            anchor.setStatus(PAYMENT_REF_INIT_CONFLICT);
            anchor.setReason(limit("current_payment_registry_collision:" + paymentId, 160));
            paymentRefRepository.save(anchor);
            if (!normalize(anchor.getTbankPaymentId()).isBlank()) {
                entityManager.flush();
            }
        }
        if (matchesPreparedCurrentIntent(invoice, prepared)) {
            clearCurrentPaymentRef(invoice);
        } else {
            invoice.setPaymentUrl(null);
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(
                "payment_registry_collision: у общего счета обнаружено несколько активных T-Bank ссылок; "
                        + "ссылка не выдана, нужна ручная сверка (" + readableException(failure) + ")",
                512
        ));
        invoiceRepository.save(invoice);
    }

    public boolean handleTbankWebhook(Map<String, String> payload) {
        try {
            return writeTransaction(() -> handleTbankWebhookInTransaction(payload));
        } catch (RuntimeException failure) {
            if (!isDurablePaymentRegistryConstraintViolation(failure)) {
                throw failure;
            }
            writeTransaction(() -> {
                quarantineWebhookIdentityConstraint(payload, failure);
                return null;
            });
            // Acknowledge a verified bank webhook after durable quarantine;
            // returning an error would cause endless provider retries.
            return true;
        }
    }

    private boolean handleTbankWebhookInTransaction(Map<String, String> payload) {
        VerifiedWebhookProfile verified = verifyWebhook(payload);
        String orderId = normalize(payload.get("OrderId"));
        String paymentId = normalize(payload.get("PaymentId"));
        String status = normalize(payload.get("Status")).toUpperCase(Locale.ROOT);
        boolean success = "true".equalsIgnoreCase(normalize(payload.get("Success")));
        String errorCode = normalize(payload.get("ErrorCode"));
        if (isTerminalPaymentWebhook(status, success, errorCode) && paymentId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "PaymentId обязателен для конечного статуса webhook"
            );
        }
        List<Long> invoiceIdsByOrderId = orderId.isBlank()
                ? List.of()
                : invoiceRepository.findIdsByTbankOrderId(orderId);
        if (new HashSet<>(invoiceIdsByOrderId).size() > 1) {
            quarantineDuplicateProviderIdentityInvoices("OrderId", orderId, invoiceIdsByOrderId);
            return true;
        }
        List<Long> invoiceIdsByPaymentId = paymentId.isBlank()
                ? List.of()
                : invoiceRepository.findIdsByTbankPaymentId(paymentId);
        if (new HashSet<>(invoiceIdsByPaymentId).size() > 1) {
            quarantineDuplicateProviderIdentityInvoices("PaymentId", paymentId, invoiceIdsByPaymentId);
            return true;
        }
        Optional<CommonInvoicePaymentRef> providerRefCandidate = findProviderPaymentRefCandidate(orderId, paymentId);
        Long candidateInvoiceId = providerRefCandidate.map(this::paymentRefInvoiceId).orElse(null);
        if (candidateInvoiceId == null && !orderId.isBlank()) {
            candidateInvoiceId = invoiceIdsByOrderId.stream().findFirst().orElse(null);
        }
        if (candidateInvoiceId == null && !paymentId.isBlank()) {
            candidateInvoiceId = invoiceIdsByPaymentId.stream().findFirst().orElse(null);
        }
        if (candidateInvoiceId == null) {
            return handleArchivedPaymentWebhook(payload, orderId, paymentId, verified.runtimeProfile());
        }

        CommonInvoice invoice = lockedInvoice(candidateInvoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (!matchesCurrentPaymentRef(invoice, orderId, paymentId)) {
            return handleArchivedPaymentWebhook(payload, orderId, paymentId, verified.runtimeProfile());
        }
        validateWebhookTerminal(invoice, verified.runtimeProfile());
        validateWebhookAmount(invoice, payload);
        CommonInvoicePaymentRef currentAnchor = providerRefCandidate
                .flatMap(this::lockedPaymentRef)
                .or(() -> lockedPaymentRefByProviderBinding(orderId, paymentId))
                .filter(ref -> Objects.equals(invoice.getId(), paymentRefInvoiceId(ref)))
                .orElse(null);
        if (currentAnchor == null) {
            quarantineMissingCurrentPaymentAnchor(invoice, orderId, paymentId);
            return true;
        }
        if (currentAnchor != null
                && !normalize(currentAnchor.getTbankPaymentId()).isBlank()
                && !paymentId.isBlank()
                && !paymentId.equals(normalize(currentAnchor.getTbankPaymentId()))) {
            quarantineWebhookPaymentIdCollision(invoice, currentAnchor, paymentId);
            return true;
        }
        if (hasForeignPaymentIdBinding(
                paymentId,
                invoice.getId(),
                currentAnchor == null ? null : currentAnchor.getId()
        )) {
            quarantineWebhookPaymentIdCollision(invoice, currentAnchor, paymentId);
            return true;
        }
        invoice.setTbankPaymentId(paymentId.isBlank() ? invoice.getTbankPaymentId() : paymentId);
        invoice.setTbankTerminalKey(verified.runtimeProfile().terminalKey());

        if ("CONFIRMED".equals(status)) {
            updateCurrentPaymentAnchorFromWebhook(
                    currentAnchor,
                    paymentId,
                    verified.runtimeProfile().terminalKey(),
                    payload,
                    PAYMENT_REF_CONFIRMED,
                    "current_payment_confirmed"
            );
            if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
                recordCurrentPaymentRef(invoice, PAYMENT_REF_APPLIED, "confirmed_after_paid");
                invoiceRepository.save(invoice);
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
            mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_TBANK);
            refreshInvoiceAmounts(invoice, items);
            long confirmedAmount = currentAnchor != null && currentAnchor.getAmountKopecks() != null
                    ? currentAnchor.getAmountKopecks()
                    : parseWebhookAmount(payload);
            if (confirmedAmount > 0 && remainingKopecks(invoice) != confirmedAmount) {
                clearCurrentPaymentRef(invoice);
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
                invoice.setLastError(limit(
                        "payment_amount_changed_after_confirmation: T-Bank подтвердил "
                                + amountRubles(confirmedAmount)
                                + " руб., но текущий остаток счета равен "
                                + amountRubles(remainingKopecks(invoice))
                                + " руб.; нужна ручная сверка",
                        512
                ));
                invoiceRepository.save(invoice);
                return true;
            }
            if (!allOrdersReady(items)) {
                recordCommonInvoicePrepayment(invoice, items);
                return true;
            }
            recordCurrentPaymentRef(invoice, PAYMENT_REF_CONFIRMED, "current_payment_confirmed");
            closePaidInvoice(invoice, items);
            if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
                markConfirmedPaymentRefsApplied(invoice.getId());
            }
        } else if (isTerminalPaymentWebhook(status, success, errorCode)) {
            String terminalStatus = durableTerminalWebhookStatus(status, success, errorCode);
            updateCurrentPaymentAnchorFromWebhook(
                    currentAnchor,
                    paymentId,
                    verified.runtimeProfile().terminalKey(),
                    payload,
                    terminalStatus,
                    "current_payment_terminal"
            );
            recordCurrentPaymentRef(invoice, terminalStatus, "current_payment_terminal");
            if (PAYMENT_REF_REFUNDED_STATUSES.contains(terminalStatus)
                    && invoice.getStatus() == CommonInvoiceStatus.PAID) {
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
                invoice.setLastError(limit(
                        "tbank_payment_refunded: платеж получил статус " + terminalStatus
                                + "; проверьте банк и оплату вручную",
                        512
                ));
            } else {
                invoice.setLastError(limit(
                        "tbank_payment_terminal: "
                                + (errorCode.isBlank() ? terminalStatus : errorCode),
                        512
                ));
            }
            invoiceRepository.save(invoice);
        } else {
            updateCurrentPaymentAnchorFromWebhook(
                    currentAnchor,
                    paymentId,
                    verified.runtimeProfile().terminalKey(),
                    payload,
                    PAYMENT_REF_CURRENT,
                    "current_payment_pending:" + (status.isBlank() ? "UNKNOWN" : status)
            );
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

        boolean allowedProviderOrderMismatch = allowsArchivedProviderOrderMismatch(
                paymentRef,
                orderId,
                paymentId
        );
        if ((!orderId.isBlank()
                && !normalize(paymentRef.getTbankOrderId()).isBlank()
                && !orderId.equals(normalize(paymentRef.getTbankOrderId()))
                && !allowedProviderOrderMismatch)
                || (!paymentId.isBlank()
                && !normalize(paymentRef.getTbankPaymentId()).isBlank()
                && !paymentId.equals(normalize(paymentRef.getTbankPaymentId())))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Реквизиты webhook не совпадают с архивной ссылкой");
        }
        if (hasForeignPaymentIdBinding(paymentId, invoice.getId(), paymentRef.getId())) {
            quarantineWebhookPaymentIdCollision(invoice, paymentRef, paymentId);
            return true;
        }
        if (!orderId.isBlank()
                && orderId.equals(normalize(invoice.getTbankOrderId()))
                && !normalize(invoice.getTbankPaymentId()).isBlank()
                && !paymentId.isBlank()
                && !paymentId.equals(normalize(invoice.getTbankPaymentId()))) {
            quarantineWebhookPaymentIdCollision(invoice, paymentRef, paymentId);
            return true;
        }
        String previousPaymentId = normalize(paymentRef.getTbankPaymentId());
        String previousTerminalKey = normalize(paymentRef.getTbankTerminalKey());
        Long previousAmount = paymentRef.getAmountKopecks();
        enrichPaymentRefAmountFromWebhook(paymentRef, payload);
        if (!paymentId.isBlank()) {
            paymentRef.setTbankPaymentId(limit(paymentId, 64));
        }
        if (normalize(paymentRef.getTbankTerminalKey()).isBlank()) {
            paymentRef.setTbankTerminalKey(limit(runtimeProfile.terminalKey(), 64));
        }
        boolean providerEvidenceChanged = !previousPaymentId.equals(normalize(paymentRef.getTbankPaymentId()))
                || !previousTerminalKey.equals(normalize(paymentRef.getTbankTerminalKey()))
                || !Objects.equals(previousAmount, paymentRef.getAmountKopecks());
        if (providerEvidenceChanged) {
            paymentRefRepository.save(paymentRef);
            entityManager.flush();
        }

        String status = normalize(payload.get("Status")).toUpperCase(Locale.ROOT);
        boolean success = "true".equalsIgnoreCase(normalize(payload.get("Success")));
        String errorCode = normalize(payload.get("ErrorCode"));
        String originalStatus = normalize(paymentRef.getStatus()).toUpperCase(Locale.ROOT);
        if (isIdempotentArchivedWebhook(paymentRef, status, success, errorCode)) {
            log.info("Повторный webhook старой ссылки общего счета {} уже обработан: {} ({})",
                    invoice.getId(), paymentRefLabel(paymentRef), status);
            return true;
        }
        boolean terminalWebhook = isTerminalPaymentWebhook(status, success, errorCode);
        if (!terminalWebhook
                && (PAYMENT_REF_CANCEL_PENDING.equals(originalStatus)
                || PAYMENT_REF_CANCELING.equals(originalStatus)
                || PAYMENT_REF_CANCEL_FAILED.equals(originalStatus)
                || PAYMENT_REF_CANCEL_FAILED_FINAL.equals(originalStatus))) {
            paymentRef.setReason(paymentRefReasonPreservingProviderOrderMismatch(
                    paymentRef,
                    "cancel_lifecycle_webhook:" + (status.isBlank() ? "UNKNOWN" : status)
            ));
            paymentRefRepository.save(paymentRef);
            return true;
        }
        if (!terminalWebhook && PAYMENT_REF_NONTERMINAL_DOWNGRADE_PROTECTED_STATUSES.contains(originalStatus)) {
            log.info(
                    "Устаревший не-конечный webhook {} не понижает durable-статус ссылки {} общего счета {}",
                    status.isBlank() ? "UNKNOWN" : status,
                    originalStatus,
                    invoice.getId()
            );
            return true;
        }
        if (!terminalWebhook) {
            boolean cancellable = canCancelInitializedPaymentRef(paymentRef);
            paymentRef.setStatus(cancellable ? PAYMENT_REF_CANCEL_PENDING : PAYMENT_REF_INIT_CONFLICT);
            paymentRef.setReason(paymentRefReasonPreservingProviderOrderMismatch(
                    paymentRef,
                    (cancellable ? "init_webhook_cancel_pending:" : "init_webhook_incomplete:")
                            + (status.isBlank() ? "UNKNOWN" : status)
            ));
            paymentRefRepository.save(paymentRef);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit(
                    "payment_init_conflict: T-Bank сообщил статус "
                            + (status.isBlank() ? "UNKNOWN" : status)
                            + " до завершения Init; нужна ручная сверка",
                    512
            ));
            invoiceRepository.save(invoice);
            return true;
        }
        String terminalStatus = durableTerminalWebhookStatus(status, success, errorCode);
        paymentRef.setStatus(terminalStatus);
        paymentRefRepository.save(paymentRef);

        if (PAYMENT_REF_CONFIRMED.equals(terminalStatus)) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    "late_tbank_payment: оплачена старая ссылка " + paymentRefLabel(paymentRef)
                            + ", сумма " + amountRubles(paymentRef.getAmountKopecks() == null ? 0 : paymentRef.getAmountKopecks())
                            + " руб.; требуется ручная сверка",
                    512
            ));
            invoiceRepository.save(invoice);
        } else if (PAYMENT_REF_REFUNDED_STATUSES.contains(terminalStatus)
                && invoice.getStatus() == CommonInvoiceStatus.PAID) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    "tbank_payment_refunded: оплаченный общий счет получил статус " + terminalStatus
                            + " по T-Bank ссылке " + paymentRefLabel(paymentRef)
                            + "; проверьте банк и оплату вручную",
                    512
            ));
            invoiceRepository.save(invoice);
        } else if ("REJECTED".equals(terminalStatus)) {
            invoice.setLastError(limit(
                    "archived_payment_" + (errorCode.isBlank() ? terminalStatus : errorCode),
                    512
            ));
            invoiceRepository.save(invoice);
        }
        return true;
    }

    private boolean allowsArchivedProviderOrderMismatch(
            CommonInvoicePaymentRef paymentRef,
            String webhookOrderId,
            String webhookPaymentId
    ) {
        if (paymentRef == null
                || normalize(webhookOrderId).isBlank()
                || normalize(webhookPaymentId).isBlank()
                || !normalize(webhookPaymentId).equals(normalize(paymentRef.getTbankPaymentId()))) {
            return false;
        }
        String reason = normalize(paymentRef.getReason()).toLowerCase(Locale.ROOT);
        return reason.contains("order_id_mismatch") || reason.contains("provider_order_mismatch");
    }

    private String paymentRefReasonPreservingProviderOrderMismatch(
            CommonInvoicePaymentRef paymentRef,
            String nextReason
    ) {
        String existing = normalize(paymentRef == null ? null : paymentRef.getReason());
        String normalizedExisting = existing.toLowerCase(Locale.ROOT);
        if (normalizedExisting.contains("order_id_mismatch")
                || normalizedExisting.contains("provider_order_mismatch")) {
            return limit(existing + ";" + normalize(nextReason), 160);
        }
        return limit(nextReason, 160);
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
            boolean reconciliationStarted = !wasEnabled && shouldEnable;
            if (reconciliationStarted) {
                markCompanyReconcilePending(entry.getValue());
            } else if (!shouldEnable) {
                clearCompanyReconcileState(entry.getValue());
            }
            saveAccountCompany(entry.getValue());
            if (reconciliationStarted) {
                scheduleCompanyReconcileAfterCommit(entry.getValue().getId());
            }
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
        boolean reconciliationRequired = link.getId() == null || !link.isEnabled() || link.isReconcilePending();
        link.setAccount(account);
        link.setCompany(company);
        link.setEnabled(true);
        if (reconciliationRequired) {
            markCompanyReconcilePending(link);
        }
        saveAccountCompany(link);
        if (account.getInvoiceCompany() == null) {
            account.setInvoiceCompany(company);
            accountRepository.save(account);
        }
        if (reconciliationRequired) {
            scheduleCompanyReconcileAfterCommit(link.getId());
        }
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
                clearCompanyReconcileState(link);
                saveAccountCompany(link);
            }
        }
    }

    private void detachCurrentCompanyOrders(Long accountId, Long companyId) {
        Optional<CommonInvoice> optionalInvoice = activeInvoiceSnapshot(accountId)
                .flatMap(snapshot -> lockedInvoice(snapshot.getId()));
        if (optionalInvoice.isEmpty()) {
            return;
        }
        CommonInvoice invoice = optionalInvoice.get();
        ensureCommonPaymentRouteAllowsCompositionChange(invoice);
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

    private void detachCurrentAccountOrders(Long accountId) {
        Optional<CommonInvoice> optionalInvoice = activeInvoiceSnapshot(accountId)
                .flatMap(snapshot -> lockedInvoice(snapshot.getId()));
        if (optionalInvoice.isEmpty()) {
            return;
        }
        CommonInvoice invoice = optionalInvoice.get();
        ensureCommonPaymentRouteAllowsCompositionChange(invoice);
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
        Set<Long> lockedOrderIds = lockInvoiceOrderAggregates(invoiceId);
        Optional<CommonInvoice> invoice = lockedInvoiceAfterOrderPrelude(invoiceId);
        invoice.ifPresent(ignored -> ensureInvoiceMembershipUnchanged(invoiceId, lockedOrderIds));
        return invoice;
    }

    /**
     * Establishes the same lock order used by standalone payment mutations:
     * Order aggregates, their PaymentLink rows, and only then the common invoice.
     * Membership is checked again while all locks are held so a pre-lock snapshot
     * can never authorize or reconcile a different invoice composition.
     */
    private LockedInvoicePaymentPrelude lockedInvoiceAfterStandalonePaymentPrelude(Long invoiceId) {
        Set<Long> lockedOrderIds = lockInvoiceOrderAggregates(invoiceId);
        Map<Long, List<PaymentLink>> paymentLinksByOrder = lockPaymentLinksForOrders(lockedOrderIds);
        CommonInvoice invoice = lockedInvoiceAfterOrderPrelude(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureInvoiceMembershipUnchanged(invoiceId, lockedOrderIds);
        return new LockedInvoicePaymentPrelude(invoice, paymentLinksByOrder);
    }

    private Optional<CommonInvoice> lockedInvoiceAfterOrderPrelude(Long invoiceId) {
        Optional<CommonInvoice> snapshot = invoiceRepository.findByIdWithAccount(invoiceId);
        Long expectedAccountId = snapshot.map(CommonInvoice::getAccount)
                .map(CommonBillingAccount::getId)
                .orElse(null);
        lockFreshAccountAfterOrderPrelude(expectedAccountId);
        Optional<CommonInvoice> locked = invoiceRepository.findByIdWithAccountForUpdate(invoiceId)
                .or(() -> snapshot);
        locked.ifPresent(invoice -> {
            entityManager.refresh(invoice);
            ensureInvoiceAccountUnchanged(invoice, expectedAccountId);
        });
        return locked;
    }

    private Optional<CommonInvoice> lockedInvoiceByToken(String token) {
        Set<Long> lockedOrderIds = lockInvoiceOrderAggregatesByToken(token);
        Optional<CommonInvoice> snapshot = invoiceRepository.findByTokenWithAccount(token);
        Long expectedAccountId = snapshot.map(CommonInvoice::getAccount)
                .map(CommonBillingAccount::getId)
                .orElse(null);
        lockFreshAccountAfterOrderPrelude(expectedAccountId);
        Optional<CommonInvoice> invoice = invoiceRepository.findByTokenWithAccountForUpdate(token)
                .or(() -> snapshot);
        invoice.ifPresent(locked -> {
            entityManager.refresh(locked);
            ensureInvoiceAccountUnchanged(locked, expectedAccountId);
        });
        invoice.ifPresent(locked -> ensureInvoiceMembershipUnchanged(locked.getId(), lockedOrderIds));
        return invoice;
    }

    private LockedInvoicePaymentPrelude lockedInvoiceByTokenAfterStandalonePaymentPrelude(String token) {
        Set<Long> lockedOrderIds = lockInvoiceOrderAggregatesByToken(token);
        Map<Long, List<PaymentLink>> paymentLinksByOrder = lockPaymentLinksForOrders(lockedOrderIds);
        Optional<CommonInvoice> snapshot = invoiceRepository.findByTokenWithAccount(token);
        Long expectedAccountId = snapshot.map(CommonInvoice::getAccount)
                .map(CommonBillingAccount::getId)
                .orElse(null);
        lockFreshAccountAfterOrderPrelude(expectedAccountId);
        CommonInvoice invoice = invoiceRepository.findByTokenWithAccountForUpdate(token)
                .or(() -> snapshot)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        entityManager.refresh(invoice);
        ensureInvoiceAccountUnchanged(invoice, expectedAccountId);
        ensureInvoiceMembershipUnchanged(invoice.getId(), lockedOrderIds);
        return new LockedInvoicePaymentPrelude(invoice, paymentLinksByOrder);
    }

    private CommonBillingAccount lockFreshAccountAfterOrderPrelude(Long accountId) {
        if (accountId == null) {
            return null;
        }
        CommonBillingAccount account = accountRepository.findByIdWithRelationsForUpdate(accountId)
                .orElseThrow(() -> invoiceMembershipChanged(
                        "плательщик общего счета исчез во время операции"
                ));
        // SELECT ... FOR UPDATE may return an already-managed pre-lock snapshot.
        // Refresh while the row lock is held before any caller mutates it.
        entityManager.refresh(account);
        return account;
    }

    private void ensureInvoiceAccountUnchanged(CommonInvoice invoice, Long expectedAccountId) {
        Long currentAccountId = invoice == null || invoice.getAccount() == null
                ? null
                : invoice.getAccount().getId();
        if (expectedAccountId != null && !Objects.equals(expectedAccountId, currentAccountId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    INVOICE_MEMBERSHIP_CHANGED + ": плательщик общего счета изменился; повторите действие"
            );
        }
    }

    private Set<Long> lockInvoiceOrderAggregates(Long invoiceId) {
        if (invoiceId == null) {
            return Set.of();
        }
        return lockOrderAggregates(invoiceOrderRepository.findOrderIdsByInvoiceId(invoiceId));
    }

    private Set<Long> lockInvoiceOrderAggregatesByToken(String token) {
        if (normalize(token).isBlank()) {
            return Set.of();
        }
        return lockOrderAggregates(invoiceOrderRepository.findOrderIdsByInvoiceToken(token));
    }

    private Set<Long> lockOrderAggregates(Collection<Long> orderIds) {
        return Set.copyOf(lockOrderAggregatesWithEntities(orderIds).keySet());
    }

    private Map<Long, Order> lockOrderAggregatesWithEntities(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> sortedOrderIds = new TreeSet<>(orderIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<Long, Order> locked = new HashMap<>();
        for (Long orderId : sortedOrderIds) {
            locked.put(orderId, orderAggregateMutationLockService.lock(orderId));
        }
        return locked;
    }

    private Map<Long, List<PaymentLink>> lockPaymentLinksForOrders(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PaymentLink>> locked = new HashMap<>();
        for (Long orderId : new TreeSet<>(orderIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()))) {
            List<PaymentLink> links = paymentLinkRepository.findByOrderIdForUpdate(orderId);
            locked.put(orderId, links == null ? List.of() : List.copyOf(links));
        }
        return Map.copyOf(locked);
    }

    private Map<Long, List<PaymentLink>> paymentLinksRequiringCommonInvoiceRouteCheck(
            Map<Long, List<PaymentLink>> paymentLinksByOrder,
            Collection<CommonInvoiceOrder> items,
            Set<PaymentLink> appliedStandalonePayments
    ) {
        if (paymentLinksByOrder == null || paymentLinksByOrder.isEmpty()
                || items == null || items.isEmpty()) {
            return Map.of();
        }
        Set<Long> invoiceOrderIds = items.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getOrder() != null && item.getOrder().getId() != null)
                .map(item -> item.getOrder().getId())
                .collect(Collectors.toSet());
        Set<PaymentLink> applied = appliedStandalonePayments == null
                ? Set.of()
                : appliedStandalonePayments;
        Map<Long, List<PaymentLink>> selected = new HashMap<>();
        for (Map.Entry<Long, List<PaymentLink>> entry : paymentLinksByOrder.entrySet()) {
            if (!invoiceOrderIds.contains(entry.getKey())) {
                continue;
            }
            List<PaymentLink> relevant = entry.getValue().stream()
                    .filter(link -> !applied.contains(link))
                    .toList();
            if (!relevant.isEmpty()) {
                selected.put(entry.getKey(), relevant);
            }
        }
        return Map.copyOf(selected);
    }

    private void reconcileStandaloneBankRoutesBeforeCommonManualPayment(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        List<PaymentLink> links = paymentLinkRepository.findByOrderIdInForRead(orderIds);
        LocalDateTime now = LocalDateTime.now();
        links.stream()
                .filter(this::isBankPayment)
                .filter(link -> link.getId() != null)
                .filter(link -> !normalize(link.getTbankPaymentId()).isBlank())
                .forEach(link -> paymentLinkService().reconcileBankLink(link.getId(), now));

        paymentLinkRepository.findByOrderIdInForRead(orderIds).stream()
                .filter(this::isBankPayment)
                .filter(link -> link.getId() != null)
                .filter(link -> link.getStatus() == PaymentLinkStatus.INITIATED)
                .filter(link -> {
                    String providerStatus = normalize(link.getProviderTerminalStatus()).toUpperCase(Locale.ROOT);
                    return "NEW".equals(providerStatus) || "FORM_SHOWED".equals(providerStatus);
                })
                .forEach(link -> paymentLinkService().cancel(link.getId()));
    }

    private boolean isBankPayment(PaymentLink link) {
        return link != null
                && (link.getPaymentMethod() == PaymentMethod.BANK_FORM
                || link.getPaymentMethod() == PaymentMethod.SBP_QR);
    }

    private void ensureNoCompetingStandaloneRoutesOrThrow(
            Map<Long, List<PaymentLink>> paymentLinksByOrder
    ) {
        for (Map.Entry<Long, List<PaymentLink>> entry : (paymentLinksByOrder == null
                ? Map.<Long, List<PaymentLink>>of()
                : paymentLinksByOrder).entrySet()) {
            for (PaymentLink link : entry.getValue()) {
                if (isSafelyClosedStandaloneRoute(link)) {
                    continue;
                }
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Заказ #" + entry.getKey()
                                + " нельзя включить в общий счет: отдельный способ оплаты"
                                + " (ссылка #" + (link.getId() == null ? "?" : link.getId())
                                + ", статус " + (link.getStatus() == null ? "UNKNOWN" : link.getStatus().name())
                                + ") не закрыт безопасно. Проверьте T-Bank либо поступление по ручным реквизитам,"
                                + " затем явно отмените/закройте отдельный платеж."
                );
            }
        }
    }

    private int closeProvablyUnstartedStandaloneRoutesOrThrow(
            Map<Long, List<PaymentLink>> paymentLinksByOrder,
            Long invoiceId
    ) {
        Map<Long, List<PaymentLink>> routes = paymentLinksByOrder == null
                ? Map.of()
                : paymentLinksByOrder;
        List<PaymentLink> closable = new ArrayList<>();
        for (Map.Entry<Long, List<PaymentLink>> entry : routes.entrySet()) {
            for (PaymentLink link : entry.getValue()) {
                if (isSafelyClosedStandaloneRoute(link)) {
                    continue;
                }
                if (!StandaloneBankPaymentPolicy.canAutoCloseForCommonInvoice(link)) {
                    ensureNoCompetingStandaloneRoutesOrThrow(Map.of(entry.getKey(), List.of(link)));
                }
                closable.add(link);
            }
        }

        if (closable.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        for (PaymentLink link : closable) {
            Long orderId = link.getOrder() == null ? null : link.getOrder().getId();
            link.setStatus(PaymentLinkStatus.CANCELED);
            link.setExpiresAt(link.getExpiresAt() == null || link.getExpiresAt().isAfter(now)
                    ? now
                    : link.getExpiresAt());
            link.setLastError(limit(
                    "common_invoice_unstarted_route_auto_closed: invoice="
                            + (invoiceId == null ? "pending" : invoiceId)
                            + "; order=" + (orderId == null ? "?" : orderId),
                    512
            ));
        }
        paymentLinkRepository.saveAll(closable);
        log.info(
                "Автоматически закрыты неинициализированные отдельные платежные маршруты: invoice={}, links={}",
                invoiceId,
                closable.stream().map(PaymentLink::getId).toList()
        );
        return closable.size();
    }

    private List<Long> closeStandaloneRoutesForCommonManualPaymentOrThrow(
            Map<Long, List<PaymentLink>> paymentLinksByOrder,
            Long invoiceId,
            String reason,
            Principal principal
    ) {
        List<PaymentLink> closable = new ArrayList<>();
        for (Map.Entry<Long, List<PaymentLink>> entry : (paymentLinksByOrder == null
                ? Map.<Long, List<PaymentLink>>of()
                : paymentLinksByOrder).entrySet()) {
            for (PaymentLink link : entry.getValue()) {
                if (isSafelyClosedStandaloneRoute(link)) {
                    continue;
                }
                if (StandaloneBankPaymentPolicy.canAutoCloseForCommonInvoice(link)
                        || canManagerCloseManualRouteForCommonPayment(link)) {
                    closable.add(link);
                    continue;
                }
                ensureNoCompetingStandaloneRoutesOrThrow(Map.of(entry.getKey(), List.of(link)));
            }
        }

        if (closable.isEmpty()) {
            return List.of();
        }
        String actor = principal == null ? "" : normalize(principal.getName());
        LocalDateTime now = LocalDateTime.now();
        for (PaymentLink link : closable) {
            PaymentLinkStatus previousStatus = link.getStatus();
            link.setStatus(PaymentLinkStatus.CANCELED);
            link.setExpiresAt(link.getExpiresAt() == null || link.getExpiresAt().isAfter(now)
                    ? now
                    : link.getExpiresAt());
            link.setLastError(limit(
                    "common_invoice_manual_card_paid: invoice=" + invoiceId
                            + "; actor=" + limit(actor.isBlank() ? "manager" : actor, 80)
                            + "; previous_status=" + (previousStatus == null ? "UNKNOWN" : previousStatus.name())
                            + "; reason=" + reason,
                    512
            ));
        }
        paymentLinkRepository.saveAll(closable);
        return closable.stream()
                .map(PaymentLink::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean canManagerCloseManualRouteForCommonPayment(PaymentLink link) {
        if (!isManualPayment(link)
                || link.getManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE
                || !MANUAL_COMMON_PAYMENT_CLOSABLE_ROUTE_STATUSES.contains(link.getStatus())) {
            return false;
        }
        return normalize(link.getTbankPaymentId()).isBlank()
                && normalize(link.getTbankOrderId()).isBlank()
                && normalize(link.getTbankTerminalKey()).isBlank()
                && normalize(link.getBankInitNonce()).isBlank()
                && normalize(link.getBankCancelNonce()).isBlank()
                && link.getBankCancelOriginStatus() == null;
    }

    private Set<PaymentLink> synchronizeConfirmedStandalonePaymentsOrThrow(
            CommonInvoice invoice,
            List<CommonInvoiceOrder> items,
            Map<Long, List<PaymentLink>> paymentLinksByOrder
    ) {
        if (invoice == null || items == null || items.isEmpty()
                || paymentLinksByOrder == null || paymentLinksByOrder.isEmpty()) {
            return Set.of();
        }
        ensureAppliedStandalonePaymentSourcesHealthyOrThrow(items, paymentLinksByOrder);
        long allocatedPaidKopecks = items.stream()
                .filter(CommonInvoiceOrder::isPaid)
                .mapToLong(CommonInvoiceOrder::getAmountKopecks)
                .sum();
        boolean hasUnallocatedCommonPayment = invoice.getPaidKopecks() > allocatedPaidKopecks;
        Set<PaymentLink> applied = new HashSet<>();
        List<ConfirmedStandaloneApplication> applications = new ArrayList<>();

        for (CommonInvoiceOrder item : items) {
            Order order = item == null ? null : item.getOrder();
            Long orderId = order == null ? null : order.getId();
            if (orderId == null) {
                continue;
            }
            List<PaymentLink> confirmed = paymentLinksByOrder.getOrDefault(orderId, List.of()).stream()
                    .filter(Objects::nonNull)
                    .filter(link -> link.getStatus() == PaymentLinkStatus.CONFIRMED)
                    .toList();
            if (confirmed.isEmpty()) {
                continue;
            }
            if (confirmed.size() != 1) {
                throw standalonePaymentConflict(
                        orderId,
                        "найдено несколько подтвержденных отдельных платежей (" + confirmed.size() + ")"
                );
            }

            PaymentLink link = confirmed.getFirst();
            long currentAmount = item.getAmountKopecks();
            if (!item.isPaid()) {
                try {
                    currentAmount = amountKopecks(payableSum(order));
                } catch (AmountCalculationException exception) {
                    throw standalonePaymentConflict(
                            orderId,
                            "не удалось безопасно определить актуальную сумму позиции"
                    );
                }
            }
            ensureConfirmedStandaloneEvidence(item, link, currentAmount);
            if (item.getSourcePaymentLinkId() != null) {
                if (!Objects.equals(item.getSourcePaymentLinkId(), link.getId())) {
                    throw standalonePaymentConflict(orderId, "позиция уже связана с другим отдельным платежом");
                }
                ensureItemMatchesConfirmedStandalonePayment(item, link);
                applied.add(link);
                continue;
            }
            if (hasItemPaymentEvidence(item)) {
                throw standalonePaymentConflict(
                        orderId,
                        "позиция уже содержит другой либо недоказанный источник оплаты"
                );
            }
            if (hasUnallocatedCommonPayment || hasCurrentCommonPaymentRoute(invoice)) {
                throw standalonePaymentConflict(
                        orderId,
                        "в общем счете уже есть платежный источник; автоматическое распределение небезопасно"
                );
            }
            if (!isOrderPaid(order)) {
                throw standalonePaymentConflict(
                        orderId,
                        "платеж подтвержден, но перевод заказа в статус оплаты не завершен"
                );
            }
            applications.add(new ConfirmedStandaloneApplication(item, link, currentAmount));
        }

        for (ConfirmedStandaloneApplication application : applications) {
            CommonInvoiceOrder item = application.item();
            PaymentLink link = application.link();
            item.setAmountKopecks(application.amountKopecks());
            item.setPaid(true);
            item.setUnpaid(false);
            item.setPaidAt(link.getPaidAt());
            item.setPaymentMethod(standalonePaymentMethod(link));
            item.setSourcePaymentLinkId(link.getId());
            if (isManualPayment(link)) {
                item.setManualPaidBy(normalize(link.getManualConfirmedBy()));
                item.setManualPaymentComment(normalize(link.getManualComment()));
            }
            mergeInvoicePaymentMethod(invoice, standalonePaymentMethod(link));
            applied.add(link);
        }
        if (!applications.isEmpty()) {
            invoiceOrderRepository.saveAll(applications.stream()
                    .map(ConfirmedStandaloneApplication::item)
                    .toList());
        }
        return Set.copyOf(applied);
    }

    private void ensureConfirmedStandaloneEvidence(
            CommonInvoiceOrder item,
            PaymentLink link,
            long currentItemAmount
    ) {
        Long orderId = item == null || item.getOrder() == null ? null : item.getOrder().getId();
        Long linkOrderId = link == null || link.getOrder() == null ? null : link.getOrder().getId();
        Long confirmedAmount = link == null ? null : link.getConfirmedAmountKopecks();
        boolean exactAmount = confirmedAmount != null
                && confirmedAmount > 0
                && confirmedAmount == currentItemAmount
                && link.getAmountKopecks() == currentItemAmount;
        boolean commonEvidence = link != null
                && link.getId() != null
                && Objects.equals(orderId, linkOrderId)
                && link.getPaidAt() != null
                && exactAmount;
        boolean bankEvidence = commonEvidence
                && (link.getPaymentMethod() == PaymentMethod.BANK_FORM
                || link.getPaymentMethod() == PaymentMethod.SBP_QR)
                && !normalize(link.getTbankPaymentId()).isBlank()
                && !normalize(link.getTbankOrderId()).isBlank()
                && !normalize(link.getTbankTerminalKey()).isBlank();
        boolean manualEvidence = commonEvidence
                && isManualPayment(link)
                && link.getManualConfirmedAt() != null
                && !normalize(link.getManualConfirmedBy()).isBlank();
        if (!bankEvidence && !manualEvidence) {
            throw standalonePaymentConflict(
                    orderId,
                    "подтвержденный платеж не имеет однозначного происхождения или его сумма не совпадает"
            );
        }
    }

    private void ensureAppliedStandalonePaymentSourcesHealthyOrThrow(
            List<CommonInvoiceOrder> items,
            Map<Long, List<PaymentLink>> paymentLinksByOrder
    ) {
        for (CommonInvoiceOrder item : items) {
            Long sourcePaymentLinkId = item == null ? null : item.getSourcePaymentLinkId();
            Order order = item == null ? null : item.getOrder();
            Long orderId = order == null ? null : order.getId();
            if (sourcePaymentLinkId == null || orderId == null) {
                continue;
            }
            PaymentLink source = paymentLinksByOrder.getOrDefault(orderId, List.of()).stream()
                    .filter(link -> sourcePaymentLinkId.equals(link.getId()))
                    .findFirst()
                    .orElse(null);
            if (source != null && source.getStatus() != PaymentLinkStatus.CONFIRMED) {
                throw standalonePaymentConflict(
                        orderId,
                        "ранее зачтенный отдельный платеж #" + sourcePaymentLinkId
                                + " теперь имеет статус " + source.getStatus().name()
                );
            }
        }
    }

    private void ensureItemMatchesConfirmedStandalonePayment(CommonInvoiceOrder item, PaymentLink link) {
        boolean matches = item != null
                && item.isPaid()
                && !item.isUnpaid()
                && Objects.equals(item.getPaidAt(), link.getPaidAt())
                && standalonePaymentMethod(link).equals(normalize(item.getPaymentMethod()))
                && Objects.equals(item.getSourcePaymentLinkId(), link.getId());
        if (!matches) {
            Long orderId = item == null || item.getOrder() == null ? null : item.getOrder().getId();
            throw standalonePaymentConflict(orderId, "источник отдельной оплаты не совпадает с позицией счета");
        }
    }

    private boolean hasItemPaymentEvidence(CommonInvoiceOrder item) {
        return item != null
                && (item.isPaid()
                || item.isUnpaid()
                || item.getPaidAt() != null
                || !normalize(item.getPaymentMethod()).isBlank()
                || !normalize(item.getManualPaidBy()).isBlank()
                || !normalize(item.getManualPaymentComment()).isBlank()
                || !normalize(item.getManualPaymentReceiptUrl()).isBlank());
    }

    /**
     * An UNPAID marker is the expected source state for a bad-review successor,
     * not evidence that money moved. Only durable settlement/evidence fields
     * make cloning that position unsafe.
     */
    private boolean hasBadReviewSuccessorPaymentEvidence(CommonInvoiceOrder item) {
        return item != null
                && (item.isPaid()
                || item.getPaidAt() != null
                || item.getSourcePaymentLinkId() != null
                || !normalize(item.getPaymentMethod()).isBlank()
                || !normalize(item.getManualPaidBy()).isBlank()
                || !normalize(item.getManualPaymentComment()).isBlank()
                || !normalize(item.getManualPaymentReceiptUrl()).isBlank());
    }

    private boolean hasCurrentCommonPaymentRoute(CommonInvoice invoice) {
        return invoice != null
                && (!normalize(invoice.getPaymentUrl()).isBlank()
                || !normalize(invoice.getTbankPaymentId()).isBlank()
                || !normalize(invoice.getTbankOrderId()).isBlank()
                || !normalize(invoice.getTbankTerminalKey()).isBlank()
                || invoice.getTbankPaymentAmountKopecks() != null
                || invoice.getTbankPaymentCreatedAt() != null);
    }

    private String standalonePaymentMethod(PaymentLink link) {
        return isManualPayment(link) ? PAYMENT_METHOD_MANUAL : PAYMENT_METHOD_TBANK;
    }

    private boolean isManualPayment(PaymentLink link) {
        return link != null
                && (link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK
                || link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK);
    }

    private ResponseStatusException standalonePaymentConflict(Long orderId, String reason) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Заказ #" + (orderId == null ? "?" : orderId) + ": " + reason
                        + ". Нужна ручная сверка платежных источников."
        );
    }

    private void deleteUnsentBadReviewSuccessor(
            CommonInvoice successor,
            List<CommonInvoiceOrder> successorItems
    ) {
        Long successorId = successor.getId();
        Long predecessorId = successor.getSupersedesInvoice() == null
                ? null
                : successor.getSupersedesInvoice().getId();
        if (predecessorId == null || invoiceRepository.existsBySupersedesInvoice_Id(successorId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Можно удалить только последний неотправленный дополнительный цикл"
            );
        }
        lockedInvoice(predecessorId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Предыдущий цикл общего счета не найден"
        ));
        Set<Long> successorOrderIds = successorItems.stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<CommonInvoiceOrder> predecessorItems = invoiceOrderRepository
                .findByInvoiceIdWithOrders(predecessorId)
                .stream()
                .filter(item -> item.getOrder() != null
                        && successorOrderIds.contains(item.getOrder().getId()))
                .toList();
        if (predecessorItems.size() != successorOrderIds.size()
                || predecessorItems.stream().anyMatch(CommonInvoiceOrder::isActiveMembership)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состав цикла изменился. Обновите данные и повторите действие"
            );
        }
        int detachedLinks = invoiceOrderRepository.deleteByInvoiceId(successorId);
        if (detachedLinks != successorItems.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав цикла изменился");
        }
        predecessorItems.forEach(item -> item.setActiveMembership(true));
        invoiceOrderRepository.saveAll(predecessorItems);
        entityManager.flush();
        paymentRefRepository.deleteByInvoiceId(successorId);
        invoiceRepository.deleteById(successorId);
        log.info("Удален неотправленный дополнительный цикл {}. Активное членство вернуто в цикл {}", successorId, predecessorId);
    }

    private void ensureGenericConfirmationDoesNotUseContractorSource(CommonInvoice invoice) {
        if (isFrozenLiveContractorSource(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Поступление по реквизитам специалиста или менеджера подтверждается только сверкой конкретного счета"
            );
        }
    }

    private boolean isFrozenLiveContractorSource(CommonInvoice invoice) {
        return invoice != null
                && invoice.getContractorAllocationId() != null
                && invoice.getPaymentRouteManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE
                && "MANUAL_MOBILE_BANK".equalsIgnoreCase(normalize(invoice.getPaymentRouteType()));
    }

    private boolean hasExactContractorSourceEvidence(CommonInvoice invoice) {
        return isFrozenLiveContractorSource(invoice)
                && invoice.getManualConfirmedAt() != null
                && normalize(invoice.getManualPaymentComment())
                .startsWith(CONTRACTOR_COMMON_SOURCE_CONFIRMATION_AUDIT_PREFIX);
    }

    private void validateContractorCommonSourceConfirmation(
            CommonInvoice invoice,
            long confirmedTotalKopecks,
            LocalDateTime effectiveAt,
            String reason
    ) {
        if (!isFrozenLiveContractorSource(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Операция доступна только для зафиксированного платежного профиля специалиста или менеджера"
            );
        }
        if (confirmedTotalKopecks <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвержденная сумма должна быть положительной");
        }
        if (effectiveAt != null && effectiveAt.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Время поступления не может быть в будущем");
        }
        if (normalize(reason).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите основание сверки");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID
                || invoice.getStatus() == CommonInvoiceStatus.BAN
                || invoice.getStatus() == CommonInvoiceStatus.DISABLED
                || invoice.getStatus() == CommonInvoiceStatus.ARCHIVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже закрыт и требует отдельной сверки");
        }
    }

    /**
     * A late transfer for predecessor A may retire only a provably unstarted
     * successor B. Once B was sent, reported, routed or otherwise gained
     * payment evidence, choosing a recipient automatically is unsafe.
     */
    private void retireUnstartedSuccessorForLateSourceOrThrow(CommonInvoice source) {
        if (source == null || source.getId() == null) {
            return;
        }
        List<CommonInvoice> successors = invoiceRepository.findSuccessorsForUpdate(source.getId());
        if (successors == null || successors.isEmpty()) {
            return;
        }
        if (successors.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Цепочка дополнительных счетов неоднозначна; нужна ручная сверка"
            );
        }
        CommonInvoice successor = successors.get(0);
        List<CommonInvoiceOrder> successorItems = invoiceOrderRepository
                .findByInvoiceIdWithOrders(successor.getId());
        if (successorWasStarted(successor, successorItems)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Следующий счет уже был выдан клиенту или содержит платежные признаки; сначала выполните его ручную сверку"
            );
        }
        deleteUnsentBadReviewSuccessor(successor, successorItems);
    }

    private boolean successorWasStarted(CommonInvoice successor, List<CommonInvoiceOrder> items) {
        if (successor == null) {
            return true;
        }
        String operation = normalize(successor.getLastError());
        boolean invoiceEvidence = successor.getSentAt() != null
                || successor.getClientReportedAt() != null
                || successor.getManualConfirmedAt() != null
                || successor.getPaidAt() != null
                || successor.getPaidKopecks() > 0
                || successor.getContractorAllocationId() != null
                || MESSAGE_SEND_IN_PROGRESS.equals(operation)
                || PAYMENT_INIT_IN_PROGRESS.equals(operation)
                || hasFrozenCommonPaymentRoute(successor)
                || hasCurrentCommonPaymentRoute(successor)
                || paymentRefRepository.existsByInvoice_Id(successor.getId());
        return invoiceEvidence || (items != null && items.stream().anyMatch(this::hasBadReviewSuccessorPaymentEvidence));
    }

    private boolean isSafelyClosedStandaloneRoute(PaymentLink link) {
        if (link == null || link.getStatus() == null) {
            return false;
        }
        boolean operationStillReserved = !normalize(link.getBankInitNonce()).isBlank()
                || !normalize(link.getBankCancelNonce()).isBlank()
                || link.getBankCancelOriginStatus() != null;
        if (operationStillReserved) {
            return false;
        }
        if (SAFELY_CLOSED_STANDALONE_PAYMENT_STATUSES.contains(link.getStatus())) {
            return true;
        }
        if (link.getStatus() != PaymentLinkStatus.EXPIRED) {
            return false;
        }
        boolean providerConfirmedExpiry = "DEADLINE_EXPIRED".equals(
                normalize(link.getProviderTerminalStatus()).toUpperCase(Locale.ROOT)
        ) && (link.getPaymentMethod() == PaymentMethod.BANK_FORM
                || link.getPaymentMethod() == PaymentMethod.SBP_QR);
        return providerConfirmedExpiry
                || (link.getPaymentMethod() == PaymentMethod.BANK_FORM
                && normalize(link.getTbankPaymentId()).isBlank()
                && normalize(link.getTbankOrderId()).isBlank()
                && link.getInitiatedAt() == null);
    }

    private void markStandalonePaymentRouteConflict(
            CommonInvoice invoice,
            ResponseStatusException conflict
    ) {
        if (invoice == null) {
            throw conflict;
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(
                STANDALONE_PAYMENT_ROUTE_CONFLICT + ": "
                        + normalize(conflict == null ? null : conflict.getReason()),
                512
        ));
        invoiceRepository.save(invoice);
    }

    private void ensureInvoiceMembershipUnchanged(Long invoiceId, Set<Long> lockedOrderIds) {
        Set<Long> currentOrderIds = invoiceOrderRepository.findMembershipByInvoiceIdForRead(invoiceId)
                .stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!currentOrderIds.equals(lockedOrderIds == null ? Set.of() : lockedOrderIds)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    INVOICE_MEMBERSHIP_CHANGED + ": состав общего счета изменился; повторите действие"
            );
        }
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
                    || (message != null && message.toLowerCase(Locale.ROOT).contains("deadlock found"))
                    || (message != null && message.contains(INVOICE_MEMBERSHIP_CHANGED))) {
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

    private boolean deferReadyCommonInvoiceFinalizationUntilAfterCommit(CommonInvoice invoice) {
        if (invoice == null
                || invoice.getId() == null
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        Long invoiceId = invoice.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    writeTransaction(() -> {
                        CommonInvoice locked = lockedInvoice(invoiceId).orElse(null);
                        if (locked == null || locked.getStatus() == CommonInvoiceStatus.PAID) {
                            return null;
                        }
                        List<CommonInvoiceOrder> lockedItems = invoiceOrderRepository
                                .findByInvoiceIdWithOrders(invoiceId);
                        if (allOrdersReady(lockedItems)) {
                            recalculateInvoice(locked, lockedItems);
                            if (!applyCommonInvoicePrepaymentIfReady(locked, lockedItems)
                                    && isInvoiceReady(invoiceId)) {
                                locked.setStatus(CommonInvoiceStatus.READY);
                                invoiceRepository.save(locked);
                                // Every member Order is already locked by
                                // lockedInvoice(invoiceId), so this cannot
                                // introduce an Order->Order reverse edge.
                                markInvoiceOrdersPublished(lockedItems);
                                if (immediateClientMessagesEnabled()) {
                                    sendInvoiceAfterCommit(invoiceId, false);
                                } else {
                                    locked.setLastError(
                                            "auto_send_disabled: моментальные клиентские сообщения выключены"
                                    );
                                    invoiceRepository.save(locked);
                                }
                            }
                        }
                        return null;
                    });
                } catch (RuntimeException e) {
                    // Ready flags and any PREPAID ref are durable. A later
                    // refresh retries under the same canonical lock order.
                    log.warn("Не удалось завершить готовый общий счет {} после коммита", invoiceId, e);
                }
            }
        });
        return true;
    }

    private void ensureCompanyNotEnabledInAnotherAccount(Long accountId, Long companyId) {
        accountCompanyRepository.findConfiguredEnabledLinksForCompany(companyId)
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

    private void scheduleCompanyReconcileAfterCommit(Long linkId) {
        Runnable reconcile = () -> {
            try {
                processCompanyReconcileJob(linkId);
            } catch (RuntimeException e) {
                // The committed link remains reconcile_pending and will be
                // reclaimed by the bounded scheduler after a crash/failure.
                log.error("Не удалось запустить сверку связи общего счета {} после коммита", linkId, e);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
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
        List<Long> candidateIds = accountCompanyRepository.findPendingReconciliationIds(
                LocalDateTime.now(),
                PageRequest.of(0, limit)
        );
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

    private boolean processCompanyReconcileJob(Long linkId) {
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
            log.error(
                    "Не удалось свести заказы компании {} с общим плательщиком {}, попытка {}",
                    job.companyId(),
                    job.accountId(),
                    job.attempt(),
                    failure
            );
            return false;
        }
    }

    private PreparedCompanyReconcile claimCompanyReconcile(Long linkId) {
        CommonBillingAccountCompany link = linkId == null
                ? null
                : accountCompanyRepository.findByIdForUpdate(linkId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (link == null
                || !link.isEnabled()
                || link.getAccount() == null
                || !link.getAccount().isEnabled()
                || !link.isReconcilePending()
                || (link.getReconcileNextAttemptAt() != null && link.getReconcileNextAttemptAt().isAfter(now))
                || (link.getReconcileLeaseUntil() != null && link.getReconcileLeaseUntil().isAfter(now))) {
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
        return new PreparedCompanyReconcile(
                link.getId(),
                link.getAccount().getId(),
                link.getCompany().getId(),
                leaseToken,
                attempt
        );
    }

    private void failCompanyReconcile(PreparedCompanyReconcile job, RuntimeException failure) {
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

    private java.time.Duration companyReconcileBackoff(int attempt) {
        long multiplier = 1L << Math.min(10, Math.max(0, attempt - 1));
        java.time.Duration delay = java.time.Duration.ofSeconds(30L * multiplier);
        return delay.compareTo(COMPANY_RECONCILE_MAX_BACKOFF) > 0
                ? COMPANY_RECONCILE_MAX_BACKOFF
                : delay;
    }

    /**
     * Fresh-transaction reconciliation. Discovery is scalar and non-locking;
     * every involved Order is then locked once in id order, followed by every
     * Account and Invoice. Exact topology is re-read before the first write.
     */
    private void reconcileEnabledCompany(PreparedCompanyReconcile job) {
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
        Map<Long, InvoiceOrderBinding> expectedMovableBindings = projectionBindings(
                invoiceOrderRepository.findMovableOpenBindingsForCompany(
                        companyId,
                        accountId,
                        ATTACHABLE_INVOICE_STATUSES
                )
        );
        Set<Long> expectedBackfillOrderIds = new TreeSet<>(
                orderRepository.findCommonBillingBackfillOrderIds(companyId, BACKFILL_STATUSES)
        );

        Set<Long> invoiceIdsToLock = new TreeSet<>(expectedTargetInvoiceIds);
        expectedMovableBindings.values().stream()
                .map(InvoiceOrderBinding::invoiceId)
                .filter(Objects::nonNull)
                .forEach(invoiceIdsToLock::add);
        Map<Long, CommonInvoice> invoiceSnapshots = loadInvoiceSnapshots(invoiceIdsToLock);

        Set<Long> accountIdsToLock = new TreeSet<>();
        accountIdsToLock.add(accountId);
        expectedMovableBindings.values().stream()
                .map(InvoiceOrderBinding::accountId)
                .filter(Objects::nonNull)
                .forEach(accountIdsToLock::add);
        Map<Long, CommonBillingAccount> accountSnapshots = loadAccountSnapshots(accountIdsToLock);
        accountSnapshots.put(accountId, targetAccountSnapshot);

        Set<Long> orderIdsToLock = new TreeSet<>(expectedTargetBindings.keySet());
        orderIdsToLock.addAll(expectedMovableBindings.keySet());
        orderIdsToLock.addAll(expectedBackfillOrderIds);
        Map<Long, Order> lockedOrders = lockOrderAggregatesWithEntities(orderIdsToLock);
        Map<Long, List<PaymentLink>> lockedBackfillPaymentLinks =
                lockPaymentLinksForOrders(expectedBackfillOrderIds);
        closeProvablyUnstartedStandaloneRoutesOrThrow(lockedBackfillPaymentLinks, null);
        Map<Long, CommonBillingAccount> lockedAccounts = lockAccountsInCanonicalOrder(accountSnapshots);
        Map<Long, CommonInvoice> lockedInvoices = lockInvoicesInCanonicalOrder(invoiceSnapshots);

        CommonBillingAccount lockedTargetAccount = lockedAccounts.get(accountId);
        if (!isEnabledCompanyLink(accountId, companyId, lockedTargetAccount)) {
            throw invoiceMembershipChanged("связь компании с целевым плательщиком изменилась");
        }
        ensureCompanyNotEnabledInAnotherAccount(accountId, companyId);

        List<CommonInvoice> currentTargetSnapshots = currentInvoiceSnapshots(accountId);
        Set<Long> currentTargetInvoiceIds = invoiceIds(currentTargetSnapshots);
        if (!currentTargetInvoiceIds.equals(expectedTargetInvoiceIds)) {
            throw invoiceMembershipChanged("набор открытых счетов целевого плательщика изменился");
        }
        if (!invoiceBindings(currentTargetInvoiceIds).equals(expectedTargetBindings)) {
            throw invoiceMembershipChanged("состав открытых счетов целевого плательщика изменился");
        }

        List<CommonInvoiceOrder> movableItems = invoiceOrderRepository.findMovableOpenItemsForCompany(
                companyId,
                accountId,
                ATTACHABLE_INVOICE_STATUSES
        );
        if (!itemBindings(movableItems).equals(expectedMovableBindings)) {
            throw invoiceMembershipChanged("состав переносимых заказов изменился");
        }
        Set<Long> currentBackfillOrderIds = new TreeSet<>(
                orderRepository.findCommonBillingBackfillOrderIds(companyId, BACKFILL_STATUSES)
        );
        if (!currentBackfillOrderIds.equals(expectedBackfillOrderIds)) {
            throw invoiceMembershipChanged("состав непривязанных заказов компании изменился");
        }
        CommonBillingAccountCompany reconcileLink = lockedCompanyReconcileLink(job);
        if (expectedTargetInvoiceIds.isEmpty()
                && movableItems.isEmpty()
                && expectedBackfillOrderIds.isEmpty()) {
            clearCompanyReconcileState(reconcileLink);
            accountCompanyRepository.save(reconcileLink);
            return;
        }

        List<CommonInvoice> lockedTargetInvoices = expectedTargetInvoiceIds.stream()
                .sorted()
                .map(lockedInvoices::get)
                .filter(Objects::nonNull)
                .toList();
        CommonInvoice targetInvoice = lockedTargetInvoices.isEmpty()
                ? createInvoice(lockedTargetAccount)
                : normalizeAttachableInvoices(lockedTargetAccount, lockedTargetInvoices);

        Set<CommonInvoice> sourceInvoices = expectedMovableBindings.values().stream()
                .map(InvoiceOrderBinding::invoiceId)
                .distinct()
                .map(lockedInvoices::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
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
            if (lockedOrder == null
                    || lockedOrder.getCompany() == null
                    || !Objects.equals(companyId, lockedOrder.getCompany().getId())
                    || lockedOrder.isComplete()
                    || !BACKFILL_STATUSES.contains(statusTitle(lockedOrder))
                    || invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(orderId).isPresent()) {
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

        log.info(
                "Reconciled company {} with common account {} invoice {}: moved={}, backfilled={}, ready={}, sources={}",
                companyId,
                accountId,
                targetInvoice.getId(),
                movableItems.size(),
                expectedBackfillOrderIds.size(),
                ready,
                sourceInvoices.stream().map(CommonInvoice::getId).toList()
        );
    }

    private boolean isEnabledCompanyLink(
            Long accountId,
            Long companyId,
            CommonBillingAccount account
    ) {
        if (account == null || !account.isEnabled() || !Objects.equals(accountId, account.getId())) {
            return false;
        }
        return accountCompanyRepository.findByAccount_IdAndCompany_Id(accountId, companyId)
                .map(CommonBillingAccountCompany::isEnabled)
                .orElse(false);
    }

    private void markCompanyReconcilePending(CommonBillingAccountCompany link) {
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

    private void clearCompanyReconcileState(CommonBillingAccountCompany link) {
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

    private CommonBillingAccountCompany lockedCompanyReconcileLink(PreparedCompanyReconcile job) {
        CommonBillingAccountCompany link = job == null
                ? null
                : accountCompanyRepository.findByIdForUpdate(job.linkId()).orElse(null);
        if (link == null
                || !link.isEnabled()
                || !link.isReconcilePending()
                || link.getAccount() == null
                || link.getCompany() == null
                || !Objects.equals(job.accountId(), link.getAccount().getId())
                || !Objects.equals(job.companyId(), link.getCompany().getId())
                || !normalize(job.leaseToken()).equals(normalize(link.getReconcileLeaseToken()))) {
            throw invoiceMembershipChanged("задача сверки компании изменилась или потеряла lease");
        }
        return link;
    }

    private List<CommonInvoice> currentInvoiceSnapshots(Long accountId) {
        if (accountId == null) {
            return List.of();
        }
        return invoiceRepository.findCurrentForAccount(
                accountId,
                ATTACHABLE_INVOICE_STATUSES,
                PageRequest.of(0, 50)
        );
    }

    private Set<Long> invoiceIds(Collection<CommonInvoice> invoices) {
        if (invoices == null || invoices.isEmpty()) {
            return Set.of();
        }
        return invoices.stream()
                .filter(Objects::nonNull)
                .map(CommonInvoice::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Map<Long, InvoiceOrderBinding> invoiceBindings(Collection<Long> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return Map.of();
        }
        return projectionBindings(invoiceOrderRepository.findBindingsByInvoiceIds(invoiceIds));
    }

    private Map<Long, InvoiceOrderBinding> projectionBindings(
            Collection<CommonInvoiceOrderRepository.OrderInvoiceBindingView> views
    ) {
        Map<Long, InvoiceOrderBinding> bindings = new HashMap<>();
        if (views == null) {
            return bindings;
        }
        for (CommonInvoiceOrderRepository.OrderInvoiceBindingView view : views) {
            Long orderId = view == null ? null : view.getOrderId();
            Long invoiceId = view == null ? null : view.getInvoiceId();
            Long accountId = view == null ? null : view.getAccountId();
            InvoiceOrderBinding previous = orderId == null
                    ? null
                    : bindings.put(orderId, new InvoiceOrderBinding(invoiceId, accountId));
            if (orderId == null || invoiceId == null || accountId == null || previous != null) {
                throw invoiceMembershipChanged("обнаружена неоднозначная связь заказа и общего счета");
            }
        }
        return bindings;
    }

    private Map<Long, InvoiceOrderBinding> itemBindings(Collection<CommonInvoiceOrder> items) {
        Map<Long, InvoiceOrderBinding> bindings = new HashMap<>();
        if (items == null) {
            return bindings;
        }
        for (CommonInvoiceOrder item : items) {
            Long orderId = item == null || item.getOrder() == null ? null : item.getOrder().getId();
            CommonInvoice invoice = item == null ? null : item.getInvoice();
            Long invoiceId = invoice == null ? null : invoice.getId();
            Long accountId = invoice == null || invoice.getAccount() == null
                    ? null
                    : invoice.getAccount().getId();
            InvoiceOrderBinding previous = orderId == null
                    ? null
                    : bindings.put(orderId, new InvoiceOrderBinding(invoiceId, accountId));
            if (orderId == null || invoiceId == null || accountId == null || previous != null) {
                throw invoiceMembershipChanged("обнаружена неоднозначная связь заказа и общего счета");
            }
        }
        return bindings;
    }

    private Map<Long, CommonInvoice> loadInvoiceSnapshots(Collection<Long> invoiceIds) {
        Map<Long, CommonInvoice> snapshots = new HashMap<>();
        if (invoiceIds == null) {
            return snapshots;
        }
        Set<Long> sortedInvoiceIds = invoiceIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        for (Long invoiceId : sortedInvoiceIds) {
            invoiceRepository.findByIdWithAccount(invoiceId)
                    .ifPresent(invoice -> snapshots.put(invoiceId, invoice));
        }
        if (snapshots.size() != sortedInvoiceIds.size()) {
            throw invoiceMembershipChanged("один из общих счетов исчез во время подготовки");
        }
        return snapshots;
    }

    private Map<Long, CommonBillingAccount> loadAccountSnapshots(Collection<Long> accountIds) {
        Map<Long, CommonBillingAccount> snapshots = new HashMap<>();
        if (accountIds == null) {
            return snapshots;
        }
        Set<Long> sortedAccountIds = accountIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        for (Long accountId : sortedAccountIds) {
            accountRepository.findByIdWithRelations(accountId)
                    .ifPresent(account -> snapshots.put(accountId, account));
        }
        if (snapshots.size() != sortedAccountIds.size()) {
            throw invoiceMembershipChanged("один из общих плательщиков исчез во время подготовки");
        }
        return snapshots;
    }

    private Map<Long, CommonBillingAccount> lockAccountsInCanonicalOrder(
            Map<Long, CommonBillingAccount> snapshots
    ) {
        Map<Long, CommonBillingAccount> locked = new HashMap<>();
        if (snapshots == null || snapshots.isEmpty()) {
            return locked;
        }
        for (Long accountId : new TreeSet<>(snapshots.keySet())) {
            CommonBillingAccount account = lockFreshAccountAfterOrderPrelude(accountId);
            locked.put(accountId, account);
        }
        return locked;
    }

    private Map<Long, CommonInvoice> lockInvoicesInCanonicalOrder(Map<Long, CommonInvoice> snapshots) {
        Map<Long, CommonInvoice> locked = new HashMap<>();
        if (snapshots == null || snapshots.isEmpty()) {
            return locked;
        }
        for (Long invoiceId : new TreeSet<>(snapshots.keySet())) {
            CommonInvoice invoice = invoiceRepository.findByIdWithAccountForUpdate(invoiceId)
                    .orElseThrow(() -> invoiceMembershipChanged(
                            "общий счет исчез во время операции"
                    ));
            entityManager.refresh(invoice);
            Long expectedAccountId = snapshots.get(invoiceId) == null
                    || snapshots.get(invoiceId).getAccount() == null
                    ? null
                    : snapshots.get(invoiceId).getAccount().getId();
            ensureInvoiceAccountUnchanged(invoice, expectedAccountId);
            locked.put(invoiceId, invoice);
        }
        return locked;
    }

    private ResponseStatusException invoiceMembershipChanged(String detail) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                INVOICE_MEMBERSHIP_CHANGED + ": " + detail + "; повторите действие"
        );
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

    private CommonInvoiceOrder attachOrderToCurrentInvoice(
            CommonBillingAccount accountSnapshot,
            Long orderId,
            Long expectedCompanyId
    ) {
        if (accountSnapshot == null || accountSnapshot.getId() == null || orderId == null || expectedCompanyId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не выбраны заказ, компания или общий плательщик");
        }
        Long accountId = accountSnapshot.getId();
        List<CommonInvoice> invoiceSnapshots = currentInvoiceSnapshots(accountId);
        Set<Long> expectedInvoiceIds = invoiceIds(invoiceSnapshots);
        Map<Long, InvoiceOrderBinding> expectedBindings = invoiceBindings(expectedInvoiceIds);

        Set<Long> orderIdsToLock = new TreeSet<>(expectedBindings.keySet());
        orderIdsToLock.add(orderId);
        Map<Long, Order> lockedOrders = lockOrderAggregatesWithEntities(orderIdsToLock);
        Map<Long, List<PaymentLink>> lockedPaymentLinks = lockPaymentLinksForOrders(Set.of(orderId));
        closeProvablyUnstartedStandaloneRoutesOrThrow(lockedPaymentLinks, null);
        Map<Long, CommonBillingAccount> lockedAccounts = lockAccountsInCanonicalOrder(
                Map.of(accountId, accountSnapshot)
        );
        Map<Long, CommonInvoice> lockedInvoices = lockInvoicesInCanonicalOrder(
                loadInvoiceSnapshots(expectedInvoiceIds)
        );

        Order lockedOrder = lockedOrders.get(orderId);
        if (lockedOrder == null
                || lockedOrder.getCompany() == null
                || !Objects.equals(expectedCompanyId, lockedOrder.getCompany().getId())) {
            throw invoiceMembershipChanged("компания заказа изменилась");
        }
        if (invoiceOrderRepository.findMembershipByOrderIdForRead(orderId).isPresent()) {
            throw invoiceMembershipChanged("заказ уже привязан к общему счету");
        }
        CommonBillingAccount lockedAccount = lockedAccounts.get(accountId);
        if (!isEnabledCompanyLink(accountId, expectedCompanyId, lockedAccount)) {
            throw invoiceMembershipChanged("связь компании с общим плательщиком изменилась");
        }
        ensureCompanyNotEnabledInAnotherAccount(accountId, expectedCompanyId);

        Set<Long> currentInvoiceIds = invoiceIds(currentInvoiceSnapshots(accountId));
        if (!currentInvoiceIds.equals(expectedInvoiceIds)
                || !invoiceBindings(currentInvoiceIds).equals(expectedBindings)) {
            throw invoiceMembershipChanged("топология открытого общего счета изменилась");
        }
        List<CommonInvoice> currentInvoices = currentInvoiceIds.stream()
                .sorted()
                .map(lockedInvoices::get)
                .filter(Objects::nonNull)
                .toList();
        CommonInvoice invoice = currentInvoices.isEmpty()
                ? createInvoice(lockedAccount)
                : normalizeAttachableInvoices(lockedAccount, currentInvoices);
        CommonInvoiceOrder item = attachOrderWithoutInvoiceRefresh(invoice, lockedOrder);
        consumeVerifiedManualRouteAfterAttach(invoice, lockedPaymentLinks);
        recalculateInvoice(invoice);
        publicationBlockerService.reconcileInvoice(invoice.getId());
        return item;
    }

    private CommonInvoiceOrder attachOrderWithoutInvoiceRefresh(CommonInvoice invoice, Order order) {
        ensureCommonPaymentRouteAllowsCompositionChange(invoice);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setInvoice(invoice);
        item.setOrder(order);
        Long payable = payableKopecksOrMarkAttention(invoice, order);
        item.setAmountKopecks(payable == null ? 0 : payable);
        item.setOriginalOrderStatusTitle(limit(statusTitle(order), 64));
        return invoiceOrderRepository.save(item);
    }

    private void consumeVerifiedManualRouteAfterAttach(
            CommonInvoice invoice,
            Map<Long, List<PaymentLink>> paymentLinksByOrder
    ) {
        if (invoice == null || invoice.getId() == null
                || paymentLinksByOrder == null || paymentLinksByOrder.isEmpty()) {
            return;
        }
        paymentLinksByOrder.values().stream()
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .filter(link -> link.getStatus() == PaymentLinkStatus.CANCELED)
                .filter(this::isManualPayment)
                .filter(link -> normalize(link.getLastError()).startsWith(MANUAL_PAYMENT_ABSENT_VERIFIED_PREFIX))
                .forEach(link -> {
                    link.setLastError(limit(
                            COMMON_INVOICE_ROUTE_ATTACHED_PREFIX
                                    + "invoice=" + invoice.getId() + "; "
                                    + normalize(link.getLastError()),
                            512
                    ));
                    paymentLinkRepository.save(link);
                });
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

    private List<CommonInvoice> normalizedBoardInvoices() {
        List<CommonInvoice> invoices = invoiceRepository.findBoardInvoices(BOARD_INVOICE_STATUSES);
        if (!hasDuplicateAttachableInvoices(invoices)) {
            return invoices;
        }
        Set<Long> duplicateAccountIds = invoices.stream()
                .filter(invoice -> invoice.getAccount() != null && invoice.getAccount().getId() != null)
                .filter(invoice -> ATTACHABLE_INVOICE_STATUSES.contains(invoice.getStatus()))
                .filter(this::isStandardInvoice)
                .collect(Collectors.groupingBy(
                        invoice -> invoice.getAccount().getId(),
                        Collectors.counting()
                ))
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        boolean normalized = normalizeDuplicateInvoiceAccounts(duplicateAccountIds);
        return normalized ? invoiceRepository.findBoardInvoices(BOARD_INVOICE_STATUSES) : invoices;
    }

    private void normalizeBoardInvoiceDuplicates() {
        normalizeDuplicateInvoiceAccounts(new TreeSet<>(
                invoiceRepository.findAccountIdsWithDuplicateCurrentInvoices(ATTACHABLE_INVOICE_STATUSES)
        ));
    }

    private boolean normalizeDuplicateInvoiceAccounts(Collection<Long> duplicateAccountIds) {
        Set<Long> accountIds = duplicateAccountIds == null
                ? Set.of()
                : duplicateAccountIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
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
            List<CommonInvoice> invoices = expectedInvoiceIdsByAccount.get(accountId).stream()
                    .sorted()
                    .map(lockedInvoices::get)
                    .filter(Objects::nonNull)
                    .toList();
            if (account == null || invoices.size() < 2) {
                throw invoiceMembershipChanged("дубли общих счетов исчезли во время нормализации");
            }
            normalizeAttachableInvoices(account, invoices);
        }
        return true;
    }

    private boolean hasDuplicateAttachableInvoices(List<CommonInvoice> invoices) {
        Map<Long, Long> countsByAccount = invoices.stream()
                .filter(invoice -> invoice.getAccount() != null && invoice.getAccount().getId() != null)
                .filter(invoice -> ATTACHABLE_INVOICE_STATUSES.contains(invoice.getStatus()))
                .filter(this::isStandardInvoice)
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
        LocalDateTime movedAt = LocalDateTime.now();
        for (CommonInvoiceOrder item : movedItems) {
            item.setInvoice(target);
            item.setInvoiceLinkedAt(movedAt);
            item.setPublicationBlockerSince(null);
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
        publicationBlockerService.reconcileInvoice(target.getId());
        log.warn("Объединены дубли открытых общих счетов accountId={}, targetInvoice={}, duplicates={}",
                account == null ? null : account.getId(), target.getId(), duplicateIds);
        return target;
    }

    private Optional<CommonInvoice> activeInvoiceSnapshot(Long accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return invoiceRepository.findCurrentForAccount(
                        accountId,
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
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
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
        try {
            Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(
                    invoice,
                    items,
                    paymentPrelude.paymentLinksByOrder()
            );
            closeProvablyUnstartedStandaloneRoutesOrThrow(
                    paymentLinksRequiringCommonInvoiceRouteCheck(
                            paymentPrelude.paymentLinksByOrder(),
                            items,
                            appliedStandalonePayments
                    ),
                    invoice.getId()
            );
        } catch (ResponseStatusException conflict) {
            markStandalonePaymentRouteConflict(invoice, conflict);
            return null;
        }
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
                markInvoiceOrdersReminder(invoice);
            } else if (!reminder && manual && shouldManualMarkInvoiceToPay(invoice)) {
                invoice.setStatus(CommonInvoiceStatus.INVOICED);
                invoice.setSentAt(LocalDateTime.now());
                markInvoiceOrdersToPay(invoice);
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

        ensureCommonPaymentRouteSelected(invoice, remainingKopecks(invoice));
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
                telegramCopyTransferNumber(invoice),
                reminder,
                manual
        );
    }

    private ClientMessageSendResult sendPreparedPaymentMessage(PreparedCommonInvoiceMessage prepared) {
        try {
            TelegramTransferCopyButton copyButton = TelegramTransferCopyButton
                    .fromFrozenTransferNumber(prepared.telegramCopyTransferNumber())
                    .orElse(null);
            return copyButton == null
                    ? messageSender.send(
                            prepared.chatCompany(),
                            prepared.managerClientId(),
                            prepared.groupId(),
                            prepared.message()
                    )
                    : messageSender.send(
                            prepared.chatCompany(),
                            prepared.managerClientId(),
                            prepared.groupId(),
                            prepared.message(),
                            copyButton
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
                invoice.setStatus(invoice.getPaidKopecks() > 0
                        ? CommonInvoiceStatus.PARTIALLY_PAID
                        : CommonInvoiceStatus.REMINDER);
                invoice.setLastReminderAt(LocalDateTime.now());
                markInvoiceOrdersReminder(invoice);
            } else {
                invoice.setStatus(invoice.getPaidKopecks() > 0
                        ? CommonInvoiceStatus.PARTIALLY_PAID
                        : CommonInvoiceStatus.INVOICED);
                invoice.setSentAt(LocalDateTime.now());
                markInvoiceOrdersToPay(invoice);
            }
            invoice.setNextReminderAt(LocalDateTime.now().plusDays(REMINDER_INTERVAL_DAYS));
            invoice.setLastError(null);
            invoiceRepository.save(invoice);
            return true;
        }

        if (prepared.reminder()) {
            if (prepared.manual()) {
                invoice.setStatus(invoice.getPaidKopecks() > 0
                        ? CommonInvoiceStatus.PARTIALLY_PAID
                        : CommonInvoiceStatus.REMINDER);
                invoice.setLastReminderAt(LocalDateTime.now());
                markInvoiceOrdersReminder(invoice);
            }
            invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        } else if (prepared.manual()) {
            invoice.setStatus(CommonInvoiceStatus.INVOICED);
            invoice.setSentAt(LocalDateTime.now());
            markInvoiceOrdersToPay(invoice);
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
            ensurePartialPaymentNextAction(invoice);
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

    private void ensureNoBlockingPaymentRefsForNewInit(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null) {
            return;
        }
        if (paymentRefRepository.existsByInvoice_IdAndStatusIn(
                invoice.getId(),
                PAYMENT_INIT_NEW_ATTEMPT_BLOCKING_REF_STATUSES
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У общего счета есть незавершенный или подтвержденный платеж Т-Банка; "
                            + "новую ссылку создавать нельзя до безопасного завершения сверки"
            );
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
        LocalDateTime operationStartedAt = PAYMENT_INIT_IN_PROGRESS.equals(error)
                ? paymentInitStartedAt(invoice)
                : invoice.getUpdatedAt();
        if (operationStartedAt == null
                || operationStartedAt.plus(OPERATION_IN_PROGRESS_TIMEOUT).isAfter(LocalDateTime.now())) {
            return;
        }
        if (PAYMENT_INIT_IN_PROGRESS.equals(error)) {
            archiveAndClearCurrentPaymentRef(invoice, "payment_init_stale_timeout");
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

    private LocalDateTime paymentInitStartedAt(CommonInvoice invoice) {
        if (invoice == null) {
            return null;
        }
        if (invoice.getTbankPaymentCreatedAt() != null) {
            return invoice.getTbankPaymentCreatedAt();
        }
        String orderId = normalize(invoice.getTbankOrderId());
        if (orderId.isBlank()) {
            return null;
        }
        return paymentRefRepository.findByTbankOrderId(orderId)
                .filter(ref -> Objects.equals(invoice.getId(), paymentRefInvoiceId(ref)))
                .map(CommonInvoicePaymentRef::getCreatedAt)
                .orElse(null);
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
        if (isMigrationPaymentRegistryAttention(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платеж из миграционного реестра нельзя повторять до ручной сверки T-Bank"
            );
        }
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
        if (isMigrationPaymentRegistryAttention(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Миграционный платежный карантин нельзя закрыть без ручной сверки T-Bank"
            );
        }
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
                || error.startsWith("payment_init_exception")
                || error.startsWith("payment_init_response_mismatch")
                || error.startsWith("payment_init_response_collision")
                || error.startsWith("payment_init_invalid_url")
                || error.startsWith("payment_cached_invalid_url")
                || error.startsWith("tbank_init_failed")
                || isManuallyConfirmableMigrationPaymentRegistryAttention(invoice);
    }

    private boolean isStandardInvoice(CommonInvoice invoice) {
        return invoice != null && "STANDARD".equals(invoice.getInvoicePurpose());
    }

    private boolean isMigrationPaymentRegistryAttention(CommonInvoice invoice) {
        return attentionError(invoice).startsWith(MIGRATION_PAYMENT_REGISTRY_ATTENTION);
    }

    private boolean isManuallyConfirmableMigrationPaymentRegistryAttention(CommonInvoice invoice) {
        String error = attentionError(invoice);
        if (!error.startsWith(MIGRATION_PAYMENT_REGISTRY_ATTENTION)) {
            return false;
        }
        String reason = error.substring(MIGRATION_PAYMENT_REGISTRY_ATTENTION.length());
        int separator = reason.indexOf(';');
        if (separator >= 0) {
            reason = reason.substring(0, separator);
        }
        return MIGRATION_PAYMENT_REGISTRY_MANUAL_CONFIRM_REASON.equals(reason.trim());
    }

    private boolean isDefinitelyUnsentPaymentInitTlsFailure(CommonInvoice invoice) {
        return CommonPaymentInitFailureClassifier.isPersistedTlsBeforeHttpFailure(
                invoice == null ? null : invoice.getLastError()
        );
    }

    private void ensureInvoiceHasNoCurrentProviderEvidence(CommonInvoice invoice) {
        boolean hasProviderEvidence = !normalize(invoice.getTbankOrderId()).isBlank()
                || !normalize(invoice.getTbankPaymentId()).isBlank()
                || !normalize(invoice.getTbankTerminalKey()).isBlank()
                || invoice.getTbankPaymentAmountKopecks() != null
                || invoice.getTbankPaymentCreatedAt() != null
                || !normalize(invoice.getPaymentUrl()).isBlank();
        if (hasProviderEvidence) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автопочинка остановлена: в общем счете уже есть реквизиты платежа T-Bank"
            );
        }
    }

    private void ensureUnsentTlsPaymentRefsCanBeRecovered(
            CommonInvoice invoice,
            List<CommonInvoicePaymentRef> refs
    ) {
        List<CommonInvoicePaymentRef> safeRefs = refs == null ? List.of() : refs;
        if (safeRefs.stream().anyMatch(ref -> !isTlsRecoveryAllowedPaymentRef(ref))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автопочинка остановлена: платежный реестр содержит активное или неизвестное состояние"
            );
        }
        List<CommonInvoicePaymentRef> unresolved = safeRefs.stream()
                .filter(this::isPreparedPaymentRef)
                .toList();
        if (unresolved.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автопочинка остановлена: не найден единственный незавершенный TLS-запрос"
            );
        }
        CommonInvoicePaymentRef ref = unresolved.getFirst();
        String expectedReason = CommonPaymentInitFailureClassifier.isExactKnownLegacyTlsFailure(
                invoice == null ? null : invoice.getLastError()
        )
                ? CommonPaymentInitFailureClassifier.LEGACY_TLS_BEFORE_HTTP_REF_REASON
                : CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_REF_REASON;
        boolean exactPreRequestFailure = expectedReason.equals(normalize(ref.getReason()))
                && !normalize(ref.getTbankOrderId()).isBlank()
                && normalize(ref.getTbankPaymentId()).isBlank()
                && !normalize(ref.getTbankTerminalKey()).isBlank()
                && ref.getAmountKopecks() != null
                && ref.getAmountKopecks() > 0;
        if (!exactPreRequestFailure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автопочинка остановлена: в платежном реестре есть неполные или неоднозначные данные"
            );
        }
    }

    private boolean isTlsRecoveryAllowedPaymentRef(CommonInvoicePaymentRef ref) {
        String status = paymentRefStatus(ref);
        if (PAYMENT_REF_ARCHIVED.equals(status)) {
            return isStrictlySafeArchivedPaymentRef(ref);
        }
        return PAYMENT_INIT_TLS_RECOVERY_ALLOWED_REF_STATUSES.contains(status);
    }

    private boolean isStrictlySafeArchivedPaymentRef(CommonInvoicePaymentRef ref) {
        String reason = normalize(ref == null ? null : ref.getReason()).toLowerCase(Locale.ROOT);
        if (reason.startsWith(PAYMENT_INIT_MANUALLY_CHECKED_BY_PREFIX)
                || PAYMENT_INIT_MANUALLY_CHECKED_REASON.equals(reason)) {
            return true;
        }
        return reason.startsWith(PAYMENT_INIT_TLS_SAFE_ARCHIVED_REASON_PREFIX)
                && normalize(ref == null ? null : ref.getTbankPaymentId()).isBlank();
    }

    private void ensureNoCompetingStandalonePaymentLinks(
            Map<Long, List<PaymentLink>> paymentLinksByOrder
    ) {
        for (Map.Entry<Long, List<PaymentLink>> entry : (paymentLinksByOrder == null
                ? Map.<Long, List<PaymentLink>>of()
                : paymentLinksByOrder).entrySet()) {
            boolean competing = entry.getValue().stream()
                    .anyMatch(StandaloneBankPaymentPolicy::blocksCommonInvoiceTlsRecovery);
            if (competing) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Автопочинка остановлена: у заказа #" + entry.getKey()
                                + " есть отдельный незавершенный платеж. Сначала сверьте и закройте его вручную."
                );
            }
        }
    }

    private void ensureMigrationPaymentRegistryCanBeManuallyResolved(
            CommonInvoice invoice,
            List<CommonInvoicePaymentRef> refs,
            Map<Long, List<PaymentLink>> paymentLinksByOrder
    ) {
        if (!isManuallyConfirmableMigrationPaymentRegistryAttention(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Этот тип миграционного платежного конфликта нельзя закрыть из карточки счета"
            );
        }
        if (refs.stream().anyMatch(ref ->
                !PREPARED_PAYMENT_REF_LIFECYCLE_STATUSES.contains(paymentRefStatus(ref)))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ручная сверка остановлена: платежный реестр содержит неизвестное состояние"
            );
        }
        if (refs.stream().anyMatch(ref ->
                PAYMENT_INIT_MANUAL_BLOCKING_REF_STATUSES.contains(paymentRefStatus(ref)))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ручная сверка остановлена: платеж активен, отменяется или уже применен"
            );
        }

        String invoiceOrderId = normalize(invoice.getTbankOrderId());
        String invoicePaymentId = normalize(invoice.getTbankPaymentId());
        String invoiceTerminalKey = normalize(invoice.getTbankTerminalKey());
        Long invoiceAmount = invoice.getTbankPaymentAmountKopecks();
        if (invoiceOrderId.isBlank() || invoicePaymentId.isBlank()
                || invoiceTerminalKey.isBlank() || invoiceAmount == null || invoiceAmount <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ручная сверка остановлена: сохраненные реквизиты T-Bank неполны"
            );
        }

        List<CommonInvoicePaymentRef> unresolved = refs.stream()
                .filter(this::isPreparedPaymentRef)
                .toList();
        if (unresolved.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ручная сверка остановлена: найдено неоднозначное число незавершенных платежей"
            );
        }
        CommonInvoicePaymentRef ref = unresolved.getFirst();
        boolean completeReconciliationEvidence = !normalize(ref.getTbankOrderId()).isBlank()
                && !normalize(ref.getTbankTerminalKey()).isBlank()
                && invoiceTerminalKey.equals(normalize(ref.getTbankTerminalKey()))
                && ref.getAmountKopecks() != null
                && invoiceAmount.equals(ref.getAmountKopecks());
        if (!completeReconciliationEvidence) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ручная сверка остановлена: в платежном реестре недостаточно данных для проверки T-Bank"
            );
        }
        ensureNoCompetingStandalonePaymentLinks(paymentLinksByOrder);
    }

    private void ensurePaymentEvidenceSnapshotMatches(
            CommonInvoice invoice,
            List<CommonInvoicePaymentRef> refs,
            CommonInvoicePaymentInitCheckRequest request
    ) {
        String supplied = normalize(request == null ? null : request.evidenceToken());
        String current = normalize(paymentEvidenceToken(invoice, filterPaymentRefEvidenceRows(refs)));
        if (supplied.isBlank() || current.isBlank() || !MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                current.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платежные данные изменились или открыты в устаревшей карточке. Обновите счет и повторите сверку."
            );
        }
    }

    private boolean isPreparedPaymentRef(CommonInvoicePaymentRef ref) {
        String status = paymentRefStatus(ref);
        return PAYMENT_REF_INIT_PREPARED.equals(status) || PAYMENT_REF_INIT_CONFLICT.equals(status);
    }

    private String paymentRefStatus(CommonInvoicePaymentRef ref) {
        return normalize(ref == null ? null : ref.getStatus()).toUpperCase(Locale.ROOT);
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
        finishLatePaymentApply(invoice, refs, items, remainingPaymentKopecks, Set.of());
    }

    private void finishLatePaymentApply(
            CommonInvoice invoice,
            List<CommonInvoicePaymentRef> refs,
            List<CommonInvoiceOrder> items,
            long remainingPaymentKopecks,
            Set<Long> closedOrderIds
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
            scheduleContractorShadowReconcile(invoice.getId());
            return;
        }

        setPaymentRefsStatus(refs, PAYMENT_REF_APPLIED);
        if (allPaid) {
            closePaidInvoice(invoice, items, closedOrderIds);
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        invoice.setLastError(null);
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(REMINDER_INTERVAL_DAYS));
        invoiceRepository.save(invoice);
    }

    private void recordCommonInvoicePrepayment(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        recordCurrentPaymentRef(invoice, PAYMENT_REF_PREPAID, PREPAID_WAITING_COMMON_INVOICE_READY);
        mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_TBANK);
        long prepaid = confirmedCommonInvoicePrepaymentKopecks(invoice);
        invoice.setPaidKopecks(Math.min(invoice.getAmountKopecks(), prepaid));
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        log.info(
                "Оплата общего счета {} принята как предоплата: paid={} amount={} ready={}/{}",
                invoice.getId(),
                invoice.getPaidKopecks(),
                invoice.getAmountKopecks(),
                items == null ? 0 : items.stream().filter(CommonInvoiceOrder::isReady).count(),
                items == null ? 0 : items.size()
        );
    }

    private boolean applyCommonInvoicePrepaymentIfReady(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        long prepaid = confirmedCommonInvoicePrepaymentKopecks(invoice);
        if (prepaid <= 0 || !allOrdersReady(items)) {
            return false;
        }
        if (prepaid >= invoice.getAmountKopecks()) {
            closePaidInvoice(invoice, items);
            if (items.stream().allMatch(CommonInvoiceOrder::isPaid)) {
                markCommonInvoicePrepaymentsApplied(invoice);
                log.info("Предоплата общего счета {} применена после готовности всех заказов", invoice.getId());
            }
            return true;
        }

        boolean firstPartialPrepayment = invoice.getStatus() != CommonInvoiceStatus.PARTIALLY_PAID;
        invoice.setPaidKopecks(prepaid);
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        invoice.setLastError(null);
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(REMINDER_INTERVAL_DAYS));
        invoiceRepository.save(invoice);
        markInvoiceOrdersPublished(items);
        if (firstPartialPrepayment && immediateClientMessagesEnabled()) {
            sendInvoiceAfterCommit(invoice.getId(), false);
        }
        log.info(
                "Предоплата общего счета {} меньше итоговой суммы: prepaid={}, amount={}",
                invoice.getId(),
                prepaid,
                invoice.getAmountKopecks()
        );
        return true;
    }

    private long confirmedCommonInvoicePrepaymentKopecks(CommonInvoice invoice) {
        Long invoiceId = invoice == null ? null : invoice.getId();
        if (invoiceId == null) {
            return 0;
        }
        return paymentRefRepository.sumAmountKopecksByInvoiceIdAndStatus(invoiceId, PAYMENT_REF_PREPAID);
    }

    private void markCommonInvoicePrepaymentsApplied(CommonInvoice invoice) {
        Long invoiceId = invoice == null ? null : invoice.getId();
        if (invoiceId == null) {
            return;
        }
        setPaymentRefsStatus(
                paymentRefRepository.findByInvoiceIdAndStatusForUpdate(invoiceId, PAYMENT_REF_PREPAID),
                PAYMENT_REF_APPLIED
        );
    }

    private void markConfirmedPaymentRefsApplied(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        setPaymentRefsStatus(
                paymentRefRepository.findByInvoiceIdAndStatusForUpdate(invoiceId, PAYMENT_REF_CONFIRMED),
                PAYMENT_REF_APPLIED
        );
    }

    private void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        closePaidInvoice(invoice, items, Set.of());
    }

    private void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds) {
        if (isFrozenLiveContractorSource(invoice) && !hasExactContractorSourceEvidence(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Поступление по реквизитам специалиста или менеджера подтверждается только сверкой конкретного счета"
            );
        }
        observabilityMetrics.observeTransactionCompletion(COMMON_INVOICE_CLOSE);
        if (normalize(invoice.getPaymentMethod()).isBlank()) {
            invoice.setPaymentMethod(PAYMENT_METHOD_TBANK);
        }
        List<String> closeFailures = new ArrayList<>();
        for (CommonInvoiceOrder item : items) {
            try {
                if (isAlreadyClosedOrder(item, alreadyClosedOrderIds)) {
                    cleanupPaidOrderAfterCommonBilling(item.getOrder());
                } else if (!item.isPaid() || !isOrderPaid(item.getOrder())) {
                    closeOrderAsPaidWithoutNextOrder(item.getOrder());
                } else {
                    cleanupPaidOrderAfterCommonBilling(item.getOrder());
                }
                if (!item.isPaid()) {
                    item.setPaid(true);
                    item.setPaidAt(LocalDateTime.now());
                }
                if (normalize(item.getPaymentMethod()).isBlank()) {
                    item.setPaymentMethod(invoice.getPaymentMethod());
                }
                item.setUnpaid(false);
            } catch (Exception e) {
                observabilityMetrics.recordCaughtFailure(COMMON_INVOICE_CLOSE, CLOSE_ORDER);
                Long orderId = item.getOrder() == null ? null : item.getOrder().getId();
                closeFailures.add(String.valueOf(orderId));
                log.warn("Не удалось закрыть заказ {} оплатой общего счета {}", orderId, invoice.getId(), e);
            }
        }
        invoiceOrderRepository.saveAll(items);
        invoice.setPaidKopecks(invoice.getAmountKopecks());
        if (invoice.getPaidAt() == null) {
            invoice.setPaidAt(LocalDateTime.now());
        }
        invoice.setNextReminderAt(null);
        if (!closeFailures.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setLastError(limit(
                    "close_failed: платеж получен, но заказы не закрылись: " + String.join(", ", closeFailures),
                    512
            ));
            invoiceRepository.save(invoice);
            scheduleContractorShadowReconcile(invoice.getId());
            return;
        }

        markInvoicePaidClosed(invoice);
        invoice.setLastError(null);
        notifyPaymentSuccessIfNeeded(invoice, items);
        invoiceRepository.save(invoice);
        manualPaymentTaskService.completeCommonInvoiceTaskIfTargetReached(invoice.getPaymentRouteManualTaskId());
        List<String> nextOrderFailures = openNextOrdersIfEnabled(invoice, items);
        if (!nextOrderFailures.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setLastError(limit(
                    "next_order_failed: платеж закрыт, но следующие заказы не создались: " + String.join(", ", nextOrderFailures),
                    512
            ));
            invoiceRepository.save(invoice);
        }
        scheduleContractorShadowReconcile(invoice.getId());
    }

    private void markInvoicePaidClosed(CommonInvoice invoice) {
        if (invoice.getStatus() != CommonInvoiceStatus.PAID && invoice.getPreviousStatus() == null) {
            invoice.setPreviousStatus(invoice.getStatus() == null ? null : invoice.getStatus().name());
        }
        if (invoice.getPaidAt() == null) {
            invoice.setPaidAt(LocalDateTime.now());
        }
        invoice.setStatus(CommonInvoiceStatus.PAID);
        if (invoice.getClosedAt() == null) {
            invoice.setClosedAt(invoice.getPaidAt());
        }
        if (normalize(invoice.getClosedBy()).isBlank()) {
            String manualActor = normalize(invoice.getManualPaidBy());
            invoice.setClosedBy(limit(manualActor.isBlank() ? "payment-confirmation" : manualActor, 160));
        }
        invoice.setCloseReason("PAID");
        invoice.setNextReminderAt(null);
    }

    private boolean closeOrderAsPaidForConfirmedItem(CommonInvoice invoice, CommonInvoiceOrder item) {
        try {
            Order order = item == null ? null : item.getOrder();
            if (isOrderPaid(order)) {
                cleanupPaidOrderAfterCommonBilling(order);
            } else {
                closeOrderAsPaidWithoutNextOrder(order, true);
            }
            return true;
        } catch (Exception e) {
            Long orderId = item == null || item.getOrder() == null ? null : item.getOrder().getId();
            log.warn("Не удалось закрыть заказ {} после подтвержденной оплаты отдельной ссылки", orderId, e);
            if (invoice != null) {
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setLastError(limit(
                        "close_failed: платеж получен, но заказ не закрылся: " + orderId,
                        512
                ));
                invoiceRepository.save(invoice);
            }
            return false;
        }
    }

    private boolean isAlreadyClosedOrder(CommonInvoiceOrder item, Set<Long> alreadyClosedOrderIds) {
        if (item == null || item.getOrder() == null || item.getOrder().getId() == null
                || alreadyClosedOrderIds == null || alreadyClosedOrderIds.isEmpty()) {
            return false;
        }
        return alreadyClosedOrderIds.contains(item.getOrder().getId());
    }

    private boolean isOrderPaid(Order order) {
        return "Оплачено".equals(statusTitle(order));
    }

    private void closeOrderAsPaidWithoutNextOrder(Order order) throws Exception {
        closeOrderAsPaidWithoutNextOrder(order, false);
    }

    private void closeOrderAsPaidWithoutNextOrder(Order order, boolean standalonePaymentAlreadyConfirmed) throws Exception {
        if (!standalonePaymentAlreadyConfirmed) {
            lockAndEnsureNoCompetingStandaloneBankPayment(order);
        }
        orderTransactionService.handlePaymentStatus(order, false);
        cleanupPaidOrderAfterCommonBilling(order);
    }

    private void lockAndEnsureNoCompetingStandaloneBankPayment(Order order) {
        if (order == null || order.getId() == null) {
            return;
        }
        // The invoice entry point already owns the canonical Order row. This
        // current/locking read is therefore ordered strictly as
        // Order -> PaymentLink and observes an Init/GetQr reservation that
        // committed while CommonBilling was waiting for the Order lock.
        boolean competingPayment = paymentLinkRepository.findByOrderIdForUpdate(order.getId())
                .stream()
                .anyMatch(StandaloneBankPaymentPolicy::hasStartedProviderPayment);
        if (competingPayment) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У заказа есть незавершенный T-Bank/СБП платеж. Проверьте его в журнале перед закрытием общего счета."
            );
        }
    }

    private void cleanupPaidOrderAfterCommonBilling(Order order) {
        Long orderId = order == null ? null : order.getId();
        if (orderId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanupPaidOrderAfterCommit(orderId);
                }
            });
            return;
        }
        performPaidOrderCleanup(order);
    }

    private void cleanupPaidOrderAfterCommit(Long orderId) {
        try {
            writeTransaction(() -> {
                Order lockedOrder = orderAggregateMutationLockService.lock(orderId);
                performPaidOrderCleanup(lockedOrder);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Не удалось выполнить отложенную очистку оплаченного заказа {}", orderId, e);
        }
    }

    /** Both downstream operations are idempotent and safe to retry after commit. */
    private void performPaidOrderCleanup(Order order) {
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
                observabilityMetrics.recordCaughtFailure(COMMON_INVOICE_CLOSE, OPEN_NEXT_ORDER);
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
        boolean preserveExactContractorPayment = hasExactContractorSourceEvidence(invoice);
        long amount = items.stream().mapToLong(CommonInvoiceOrder::getAmountKopecks).sum();
        long paid = items.stream().filter(CommonInvoiceOrder::isPaid).mapToLong(CommonInvoiceOrder::getAmountKopecks).sum()
                + confirmedCommonInvoicePrepaymentKopecks(invoice);
        invoice.setAmountKopecks(amount);
        invoice.setPaidKopecks(Math.min(
                amount,
                (preserveRecordedAttentionPayment || preserveExactContractorPayment)
                        ? Math.max(recordedPaid, paid)
                        : paid
        ));
        boolean preserveMigrationPaymentEvidence = isMigrationPaymentRegistryAttention(invoice);
        if (!preserveMigrationPaymentEvidence
                && invoice.getStatus() != CommonInvoiceStatus.PAID
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
                ensurePartialPaymentNextAction(invoice);
            }
        }
        invoiceRepository.save(invoice);
    }

    private void ensurePartialPaymentNextAction(CommonInvoice invoice) {
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.PARTIALLY_PAID) {
            return;
        }
        if (invoice.getSentAt() != null && invoice.getNextReminderAt() == null) {
            invoice.setNextReminderAt(LocalDateTime.now().plusDays(REMINDER_INTERVAL_DAYS));
        }
    }

    private void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        // Standalone confirmations are synchronized separately and only while
        // a LockedInvoicePaymentPrelude owns Order and PaymentLink locks.
        if (isMigrationPaymentRegistryAttention(invoice)) {
            return;
        }
        // Superseded rows remain available as an immutable financial snapshot,
        // but no longer own the current order membership.
        if (items != null
                && !items.isEmpty()
                && items.stream().noneMatch(CommonInvoiceOrder::isActiveMembership)) {
            return;
        }
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
        // Amount calculation must succeed before immutable zero/no-recipient
        // markers can be written. If completion accrual then fails, this whole
        // transaction rolls back both item amounts and routing state.
        ensureCompletionRewardsBeforeCommonRouting(items);
        recalculateInvoice(invoice, items);
        if (invoice != null) {
            if (allOrdersReady(items) && applyCommonInvoicePrepaymentIfReady(invoice, items)) {
                return;
            }
            if (invoice.getStatus() == CommonInvoiceStatus.COLLECTING && areInvoiceItemsReady(items)) {
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

    private boolean isIdempotentArchivedWebhook(
            CommonInvoicePaymentRef ref,
            String webhookStatus,
            boolean success,
            String errorCode
    ) {
        String currentStatus = normalize(ref == null ? null : ref.getStatus()).toUpperCase(Locale.ROOT);
        String status = normalize(webhookStatus).toUpperCase(Locale.ROOT);
        if (PAYMENT_REF_CONFIRMED.equals(status)) {
            return PAYMENT_REF_PREPAID.equals(currentStatus)
                    || PAYMENT_REF_CONFIRMED.equals(currentStatus)
                    || PAYMENT_REF_APPLYING.equals(currentStatus)
                    || PAYMENT_REF_APPLIED.equals(currentStatus);
        }
        return currentStatus.equals(status) && isTerminalPaymentWebhook(status, success, errorCode);
    }

    private Optional<CommonInvoicePaymentRef> lockedPaymentRef(CommonInvoicePaymentRef ref) {
        if (ref == null || ref.getId() == null) {
            return Optional.ofNullable(ref);
        }
        return paymentRefRepository.findByIdForUpdate(ref.getId());
    }

    private CommonInvoiceDetailsResponse invoiceDetails(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        List<CommonInvoicePaymentRef> paymentRefs = paymentRefEvidenceRows(invoice);
        Map<String, String> terminalLabels = paymentTerminalLabels(invoice, paymentRefs);
        return new CommonInvoiceDetailsResponse(
                toInvoiceSummary(
                        invoice,
                        items,
                        terminalLabels.get(normalize(invoice == null ? null : invoice.getTbankTerminalKey()))
                ),
                items.stream().map(this::toOrderResponse).toList(),
                toOrderCards(items),
                toNextCycleOrders(items),
                toPaymentRefEvidence(paymentRefs, terminalLabels),
                paymentEvidenceToken(invoice, paymentRefs)
        );
    }

    private List<CommonInvoicePaymentRef> paymentRefEvidenceRows(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null
                || (!isPaymentInitManualCheckAttention(invoice)
                && !isMigrationPaymentRegistryAttention(invoice))) {
            return List.of();
        }
        return filterPaymentRefEvidenceRows(
                paymentRefRepository.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId())
        );
    }

    private List<CommonInvoicePaymentRef> filterPaymentRefEvidenceRows(
            List<CommonInvoicePaymentRef> paymentRefs
    ) {
        return (paymentRefs == null ? List.<CommonInvoicePaymentRef>of() : paymentRefs).stream()
                .filter(ref -> ref != null && (
                        isPreparedPaymentRef(ref)
                                || !normalize(ref.getTbankOrderId()).isBlank()
                                || !normalize(ref.getTbankPaymentId()).isBlank()
                ))
                .toList();
    }

    private List<CommonInvoicePaymentRefResponse> toPaymentRefEvidence(
            List<CommonInvoicePaymentRef> paymentRefs,
            Map<String, String> terminalLabels
    ) {
        return (paymentRefs == null ? List.<CommonInvoicePaymentRef>of() : paymentRefs).stream()
                .map(ref -> new CommonInvoicePaymentRefResponse(
                        ref.getId(),
                        paymentRefStatus(ref),
                        normalize(ref.getTbankOrderId()).isBlank() ? null : ref.getTbankOrderId(),
                        normalize(ref.getTbankPaymentId()).isBlank() ? null : ref.getTbankPaymentId(),
                        ref.getAmountKopecks(),
                        terminalLabels == null
                                ? null
                                : terminalLabels.get(normalize(ref.getTbankTerminalKey())),
                        normalize(ref.getTbankTerminalKey()).isBlank() ? null : ref.getTbankTerminalKey(),
                        normalize(ref.getReason()).isBlank() ? null : ref.getReason()
                ))
                .toList();
    }

    private Map<String, String> paymentTerminalLabels(
            CommonInvoice invoice,
            List<CommonInvoicePaymentRef> paymentRefs
    ) {
        Set<String> terminalKeys = new HashSet<>();
        String invoiceTerminalKey = normalize(invoice == null ? null : invoice.getTbankTerminalKey());
        if (!invoiceTerminalKey.isBlank()) {
            terminalKeys.add(invoiceTerminalKey);
        }
        for (CommonInvoicePaymentRef ref : paymentRefs == null
                ? List.<CommonInvoicePaymentRef>of()
                : paymentRefs) {
            String terminalKey = normalize(ref == null ? null : ref.getTbankTerminalKey());
            if (!terminalKey.isBlank()) {
                terminalKeys.add(terminalKey);
            }
        }
        if (terminalKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, PaymentProfile> profilesByTerminal = paymentProfileService.findByTerminalKeys(terminalKeys);
        if (profilesByTerminal == null) {
            profilesByTerminal = Map.of();
        }
        Map<String, String> labels = new HashMap<>();
        for (String terminalKey : terminalKeys) {
            PaymentProfile profile = profilesByTerminal.get(terminalKey);
            String label = normalize(profile == null ? null : profile.getName());
            if (label.isBlank()) {
                label = terminalKey;
            }
            labels.put(terminalKey, label);
        }
        return labels;
    }

    private String paymentEvidenceToken(
            CommonInvoice invoice,
            List<CommonInvoicePaymentRef> paymentRefs
    ) {
        if (invoice == null || invoice.getId() == null
                || (!isPaymentInitManualCheckAttention(invoice)
                && !isMigrationPaymentRegistryAttention(invoice))) {
            return null;
        }
        StringBuilder evidence = new StringBuilder(512);
        appendEvidenceField(evidence, invoice.getId());
        appendEvidenceField(evidence, invoice.getStatus());
        appendEvidenceField(evidence, invoice.getLastError());
        appendEvidenceField(evidence, invoice.getTbankOrderId());
        appendEvidenceField(evidence, invoice.getTbankPaymentId());
        appendEvidenceField(evidence, invoice.getTbankTerminalKey());
        appendEvidenceField(evidence, invoice.getTbankPaymentAmountKopecks());
        appendEvidenceField(evidence, invoice.getTbankPaymentCreatedAt());
        appendEvidenceField(evidence, invoice.getPaymentUrl());
        for (CommonInvoicePaymentRef ref : paymentRefs == null
                ? List.<CommonInvoicePaymentRef>of()
                : paymentRefs) {
            appendEvidenceField(evidence, ref.getId());
            appendEvidenceField(evidence, ref.getStatus());
            appendEvidenceField(evidence, ref.getTbankOrderId());
            appendEvidenceField(evidence, ref.getTbankPaymentId());
            appendEvidenceField(evidence, ref.getTbankTerminalKey());
            appendEvidenceField(evidence, ref.getAmountKopecks());
            appendEvidenceField(evidence, ref.getReason());
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            evidence.toString().getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void appendEvidenceField(StringBuilder target, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        target.append(text.length()).append(':').append(text).append('|');
    }

    private List<CommonInvoiceNextCycleResponse> toNextCycleOrders(List<CommonInvoiceOrder> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Long> sourceOrderIds = items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(order -> order != null && order.getId() != null)
                .map(Order::getId)
                .toList();
        if (sourceOrderIds.isEmpty()) {
            return List.of();
        }
        return nextOrderRequestRepository.findBySourceOrderIdsWithCreatedOrder(sourceOrderIds).stream()
                .filter(request -> request.getCreatedOrder() != null)
                .map(this::toNextCycleResponse)
                .toList();
    }

    private CommonInvoiceNextCycleResponse toNextCycleResponse(NextOrderRequest request) {
        Order created = request.getCreatedOrder();
        CommonInvoiceOrder linkedItem = created == null || created.getId() == null
                ? null
                : invoiceOrderRepository.findByOrderIdWithInvoice(created.getId()).orElse(null);
        CommonInvoice linkedInvoice = linkedItem == null ? null : linkedItem.getInvoice();
        return new CommonInvoiceNextCycleResponse(
                request.getSourceOrder() == null ? null : request.getSourceOrder().getId(),
                created == null ? null : created.getId(),
                linkedInvoice == null ? null : linkedInvoice.getId(),
                linkedInvoice == null ? null : linkedInvoice.getStatus().name(),
                created == null || created.getCompany() == null ? "" : created.getCompany().getTitle(),
                created == null || created.getFilial() == null ? "" : created.getFilial().getTitle(),
                statusTitle(created)
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
        CommonInvoiceSummaryResponse current = invoiceRepository.findCurrentPresentationForAccount(
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
        return toAccountResponse(account, companyLinks, current);
    }

    private CommonBillingAccountResponse toAccountResponse(
            CommonBillingAccount account,
            List<CommonBillingAccountCompany> companyLinks,
            CommonInvoiceSummaryResponse current
    ) {
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

    private Map<Long, CommonInvoiceSummaryResponse> currentInvoiceSummaries(
            List<CommonBillingAccount> accounts
    ) {
        if (accounts == null || accounts.isEmpty()) {
            return Map.of();
        }
        List<Long> accountIds = accounts.stream()
                .map(CommonBillingAccount::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (accountIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, CommonInvoice> invoiceByAccountId = new HashMap<>();
        for (int start = 0; start < accountIds.size(); start += BULK_QUERY_CHUNK_SIZE) {
            List<Long> chunk = accountIds.subList(
                    start,
                    Math.min(start + BULK_QUERY_CHUNK_SIZE, accountIds.size())
            );
            for (CommonInvoice invoice : invoiceRepository.findLatestCurrentForAccounts(
                    chunk,
                    CURRENT_INVOICE_STATUSES
            )) {
                if (invoice == null
                        || invoice.getId() == null
                        || invoice.getAccount() == null
                        || invoice.getAccount().getId() == null) {
                    continue;
                }
                invoiceByAccountId.merge(
                        invoice.getAccount().getId(),
                        invoice,
                        (left, right) -> left.getId() >= right.getId() ? left : right
                );
            }
        }
        if (invoiceByAccountId.isEmpty()) {
            return Map.of();
        }

        List<Long> invoiceIds = invoiceByAccountId.values().stream()
                .map(CommonInvoice::getId)
                .distinct()
                .toList();
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoiceId = new HashMap<>();
        for (int start = 0; start < invoiceIds.size(); start += BULK_QUERY_CHUNK_SIZE) {
            List<Long> chunk = invoiceIds.subList(
                    start,
                    Math.min(start + BULK_QUERY_CHUNK_SIZE, invoiceIds.size())
            );
            for (CommonInvoiceOrder item : invoiceOrderRepository.findByInvoiceIdsWithOrders(chunk)) {
                if (item == null || item.getInvoice() == null || item.getInvoice().getId() == null) {
                    continue;
                }
                itemsByInvoiceId.computeIfAbsent(item.getInvoice().getId(), ignored -> new ArrayList<>())
                        .add(item);
            }
        }

        Map<Long, CommonInvoiceSummaryResponse> summaries = new HashMap<>();
        invoiceByAccountId.forEach((accountId, invoice) -> {
            List<CommonInvoiceOrder> items = itemsByInvoiceId.getOrDefault(invoice.getId(), List.of());
            refreshInvoiceAmounts(invoice, items);
            summaries.put(accountId, toInvoiceSummary(invoice, items));
        });
        return summaries;
    }

    private CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return toInvoiceSummary(invoice, items, null);
    }

    private CommonInvoiceSummaryResponse toInvoiceSummary(
            CommonInvoice invoice,
            List<CommonInvoiceOrder> items,
            String tbankTerminalLabel
    ) {
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
                invoice.getClosedAt(),
                invoice.getClosedBy(),
                invoice.getCloseReason(),
                invoice.getLastError(),
                invoice.getPaymentSuccessNotificationError(),
                invoice.getTbankOrderId(),
                invoice.getTbankPaymentId(),
                invoice.getTbankPaymentAmountKopecks(),
                normalize(tbankTerminalLabel).isBlank() ? null : tbankTerminalLabel,
                normalize(invoice.getTbankTerminalKey()).isBlank() ? null : invoice.getTbankTerminalKey(),
                normalize(invoice.getPaymentRouteType()),
                normalize(invoice.getPaymentRouteProfileName()),
                invoice.getPaymentRouteManualTaskId(),
                isFrozenLiveContractorSource(invoice),
                invoice.getPaymentRouteSelectedAt(),
                normalize(invoice.getInvoicePurpose()),
                invoice.getSupersedesInvoice() == null ? null : invoice.getSupersedesInvoice().getId()
        );
    }

    private OrderDTOList toManagerBoardCard(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        CommonInvoiceSummaryResponse summary = toInvoiceSummary(invoice, items);
        BadReviewTaskSummary badReviewSummary = aggregateBadReviewSummary(items);
        Company company = chatCompany(invoice, items);
        Manager invoiceManager = manager(invoice, items);
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
                .managerPayText(invoiceManager == null ? "" : normalize(invoiceManager.getPayText()))
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
        String invoiceBoardStatus = boardStatus(invoice, items);
        return boardStatus.isBlank()
                || "Все".equals(boardStatus)
                || invoiceBoardStatus.equals(boardStatus);
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

    private void ensureCommonInvoiceCanBeDeleted(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null || invoice.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден");
        }
        String rawOperation = normalize(invoice.getLastError());
        if (MESSAGE_SEND_IN_PROGRESS.equals(rawOperation) || PAYMENT_INIT_IN_PROGRESS.equals(rawOperation)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя удалить общий счет во время активной операции"
            );
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID
                || invoice.getStatus() == CommonInvoiceStatus.PARTIALLY_PAID
                || invoice.getPaidKopecks() > 0
                || items.stream().anyMatch(CommonInvoiceOrder::isPaid)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя удалить общий счет, по которому уже была оплата"
            );
        }
        boolean hasCurrentPaymentLink = !normalize(invoice.getPaymentUrl()).isBlank()
                || !normalize(invoice.getTbankOrderId()).isBlank()
                || !normalize(invoice.getTbankPaymentId()).isBlank()
                || !normalize(invoice.getTbankTerminalKey()).isBlank();
        if (hasCurrentPaymentLink || paymentRefRepository.existsByInvoice_Id(invoice.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя удалить общий счет с платежной ссылкой T-Bank. Сначала разберите платеж вручную"
            );
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
        if (item == null || !item.isActiveMembership()) {
            return false;
        }
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
        if (invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.COLLECTING
                && publicationBlockerService.hasOverdueBlockers(items, LocalDateTime.now())) {
            return STATUS_NEEDS_ATTENTION;
        }
        return switch (effectiveInvoiceStatus(invoice, items)) {
            case COLLECTING -> STATUS_WAITING_COMMON_INVOICE;
            case READY -> STATUS_PUBLIC;
            case INVOICED -> STATUS_TO_PAY;
            case REMINDER, PARTIALLY_PAID -> STATUS_REMINDER;
            case NEEDS_ATTENTION -> STATUS_NEEDS_ATTENTION;
            case UNPAID -> STATUS_NOT_PAID;
            case BAN -> STATUS_BAN;
            case ARCHIVED -> STATUS_ARCHIVE;
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
                !hasFrozenCommonPaymentRoute(item.getInvoice())
                        && item.getInvoice().getStatus() != CommonInvoiceStatus.PAID
                        && item.getInvoice().getStatus() != CommonInvoiceStatus.UNPAID
                        && item.getInvoice().getStatus() != CommonInvoiceStatus.BAN
                        && item.getInvoice().getStatus() != CommonInvoiceStatus.ARCHIVED
                        && item.getInvoice().getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION,
                item.getPaidAt(),
                resolvedPaymentMethod(item),
                normalize(item.getManualPaidBy()),
                normalize(item.getManualPaymentComment()),
                normalize(item.getManualPaymentReceiptUrl())
        );
    }

    private String resolvedPaymentMethod(CommonInvoiceOrder item) {
        String method = normalize(item.getPaymentMethod()).toUpperCase(Locale.ROOT);
        if (!method.isBlank()) {
            return method;
        }
        if (!item.isPaid()) {
            return "";
        }
        CommonInvoice invoice = item.getInvoice();
        if (invoice != null && (!normalize(invoice.getTbankPaymentId()).isBlank()
                || !normalize(invoice.getTbankOrderId()).isBlank())) {
            return PAYMENT_METHOD_TBANK;
        }
        return "MANUAL_LEGACY";
    }

    private void applyManualPaymentEvidence(
            CommonInvoice invoice,
            List<CommonInvoiceOrder> items,
            ManualPaymentConfirmationRequest request,
            Principal principal
    ) {
        validateManualPaymentEvidence(request);
        String actor = principal == null ? "" : normalize(principal.getName());
        LocalDateTime confirmedAt = LocalDateTime.now();
        mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_MANUAL);
        invoice.setManualPaidBy(actor);
        invoice.setManualPaymentComment(normalize(request.comment()));
        invoice.setManualPaymentReceiptUrl(normalize(request.receiptUrl()));
        invoice.setManualConfirmedAt(confirmedAt);
        for (CommonInvoiceOrder item : items) {
            if (!item.isPaid()) {
                applyManualPaymentEvidence(item, request, actor);
            }
        }
        invoiceOrderRepository.saveAll(items);
        invoiceRepository.save(invoice);
    }

    private void applyManualPaymentEvidence(
            CommonInvoice invoice,
            CommonInvoiceOrder item,
            ManualPaymentConfirmationRequest request,
            Principal principal
    ) {
        validateManualPaymentEvidence(request);
        String actor = principal == null ? "" : normalize(principal.getName());
        mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_MANUAL);
        invoice.setManualPaidBy(actor);
        invoice.setManualPaymentComment(normalize(request.comment()));
        invoice.setManualPaymentReceiptUrl(normalize(request.receiptUrl()));
        invoice.setManualConfirmedAt(LocalDateTime.now());
        applyManualPaymentEvidence(item, request, actor);
        invoiceRepository.save(invoice);
    }

    private void applyManualPaymentEvidence(
            CommonInvoiceOrder item,
            ManualPaymentConfirmationRequest request,
            String actor
    ) {
        item.setPaymentMethod(PAYMENT_METHOD_MANUAL);
        item.setManualPaidBy(actor);
        item.setManualPaymentComment(normalize(request.comment()));
        item.setManualPaymentReceiptUrl(normalize(request.receiptUrl()));
    }

    private void validateManualPaymentEvidence(ManualPaymentConfirmationRequest request) {
        String comment = normalize(request == null ? null : request.comment());
        String receiptUrl = normalize(request == null ? null : request.receiptUrl());
        if (comment.isBlank() && receiptUrl.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Для ручного подтверждения укажите комментарий или ссылку на чек"
            );
        }
    }

    private void mergeInvoicePaymentMethod(CommonInvoice invoice, String method) {
        String current = normalize(invoice.getPaymentMethod()).toUpperCase(Locale.ROOT);
        if (current.isBlank()) {
            invoice.setPaymentMethod(method);
        } else if (!current.equals(method)) {
            invoice.setPaymentMethod(PAYMENT_METHOD_MIXED);
        }
    }

    private String invoiceMessage(CommonInvoice invoice, List<CommonInvoiceOrder> items, boolean reminder) {
        StringBuilder builder = new StringBuilder();
        builder.append(invoice.getAccount().getName()).append("\n\n");
        builder.append(reminder ? "Напоминаем об оплате общего счета." : "Все заказы из общего счета выполнены.");
        builder.append("\n\nЗаказов: ").append(items.size());
        builder.append("\nК оплате: ").append(money(amountRubles(remainingKopecks(invoice)))).append(" руб.");
        if (isManagerTextCommonRoute(invoice) || isManualMobileBankCommonRoute(invoice)) {
            builder.append("\n\n").append(commonPaymentInstructionText(invoice));
            builder.append("\nСтраница общего счета: ").append(publicInvoiceUrl(invoice));
        } else {
            builder.append("\nСсылка на оплату: ").append(publicInvoiceUrl(invoice));
        }
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

    private String commonPaymentInstructionText(CommonInvoice invoice) {
        if (invoice != null
                && invoice.getContractorAllocationId() != null
                && invoice.getPaymentRouteManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            return contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(
                    invoice,
                    remainingKopecks(invoice)
            ).map(snapshot -> commonContractorPaymentInstruction(snapshot)).orElse("");
        }
        return normalize(invoice == null ? null : invoice.getPaymentRouteInstructionText());
    }

    private String commonContractorPaymentInstruction(ContractorPaymentRequisitesSnapshot snapshot) {
        if (snapshot == null) {
            return "";
        }
        String transfer = normalize(snapshot.paymentPhone());
        String label = transfer.matches("[0-9]{16,19}")
                ? "Оплата по номеру карты: "
                : "Оплата по мобильному банку: ";
        StringBuilder instruction = new StringBuilder(label).append(transfer)
                .append("\nПолучатель: ").append(normalize(snapshot.recipientName()));
        if (!normalize(snapshot.bankName()).isBlank()) {
            instruction.append("\nБанк: ").append(normalize(snapshot.bankName()));
        }
        if (!normalize(snapshot.paymentComment()).isBlank()) {
            instruction.append("\nКомментарий: ").append(normalize(snapshot.paymentComment()));
        }
        return instruction.toString();
    }

    private String telegramCopyTransferNumber(CommonInvoice invoice) {
        if (invoice == null || !isManualMobileBankCommonRoute(invoice)) {
            return null;
        }
        String value;
        if (invoice.getContractorAllocationId() != null
                && invoice.getPaymentRouteManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            value = contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(
                    invoice,
                    remainingKopecks(invoice)
            ).map(ContractorPaymentRequisitesSnapshot::paymentPhone).orElse(null);
        } else {
            value = invoice.getPaymentRouteManualPhone();
        }
        return com.hunt.otziv.contractor_payments.service.ContractorPaymentTransferNumber.isValid(value)
                ? com.hunt.otziv.contractor_payments.service.ContractorPaymentTransferNumber.normalize(value)
                : null;
    }

    private void ensureCommonPaymentRouteSelected(CommonInvoice invoice, long remainingKopecks) {
        if (invoice == null || invoice.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден");
        }
        if (hasFrozenCommonPaymentRoute(invoice)) {
            if (invoice.getContractorAllocationId() == null) {
                return;
            }
            FrozenCommonRouteAction action = contractorPaymentLiveRoutingService.frozenCommonRouteAction(
                    invoice.getId(),
                    invoice.getContractorAllocationId()
            );
            if (action == FrozenCommonRouteAction.KEEP) {
                return;
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Предыдущий платеж общего счета требует сверки вручную; автоматическая повторная выдача "
                            + "реквизитов заблокирована"
            );
        }

        List<Order> routeOrders = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId()).stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .toList();
        Manager routeManager = manager(invoice);
        contractorPaymentShadowService.prepareCommonInvoiceSource(
                invoice,
                routeOrders,
                routeManager,
                remainingKopecks,
                LocalDateTime.now()
        );

        PaymentRouteSelection route;
        ContractorPaymentAllocation liveAllocation = null;
        if (hasCurrentCommonPaymentRoute(invoice)) {
            route = legacyTbankRoute(invoice, remainingKopecks);
        } else if (contractorPaymentLiveRoutingService.enabledForNewRoutes()
                && invoice.isShadowRouteContractorEligible()) {
            liveAllocation = contractorPaymentLiveRoutingService.reserveForCommonInvoice(
                    invoice,
                    routeOrders,
                    routeManager,
                    remainingKopecks
            );
            route = isContractorRecipient(liveAllocation)
                    ? contractorCommonPaymentRoute(invoice, liveAllocation)
                    : paymentLinkService().selectCommonInvoiceRoute(routeManager, remainingKopecks);
        } else {
            route = paymentLinkService().selectCommonInvoiceRoute(routeManager, remainingKopecks);
        }
        applyCommonPaymentRoute(invoice, route, remainingKopecks);
        if (liveAllocation != null) {
            invoice.setContractorAllocationId(liveAllocation.getId());
            if (isContractorRecipient(liveAllocation)) {
                // Contractor PII is stored only in the encrypted allocation
                // snapshot, never in legacy common-invoice columns.
                invoice.setPaymentRouteManualPhone(null);
                invoice.setPaymentRouteManualRecipient(null);
                invoice.setPaymentRouteManualBankName(limit(liveAllocation.getBankNameSnapshot(), 120));
                // Custom comments and generated instruction text are resolved
                // from the encrypted allocation snapshot only.
                invoice.setPaymentRouteManualComment(null);
                invoice.setPaymentRouteInstructionText(null);
            }
        }
        invoiceRepository.save(invoice);
        scheduleContractorShadowRoute(invoice.getId(), invoice.getShadowRouteGeneration());
        log.info(
                "За общим счетом {} закреплен маршрут оплаты {}: profile={}, task={}, amountKopecks={}",
                invoice.getId(),
                invoice.getPaymentRouteType(),
                invoice.getPaymentRouteProfileId(),
                invoice.getPaymentRouteManualTaskId(),
                invoice.getPaymentRouteAmountKopecks()
        );
    }

    private boolean isContractorRecipient(ContractorPaymentAllocation allocation) {
        return allocation != null
                && (allocation.getRecipientType() == ContractorRecipientType.SPECIALIST
                || allocation.getRecipientType() == ContractorRecipientType.MANAGER);
    }

    private PaymentRouteSelection contractorCommonPaymentRoute(
            CommonInvoice invoice,
            ContractorPaymentAllocation allocation
    ) {
        String recipient = normalize(allocation.getRecipientNameSnapshot());
        String phone = normalize(allocation.getPaymentPhoneSnapshot());
        if (recipient.isBlank() || phone.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "В зафиксированном платежном профиле отсутствуют обязательные реквизиты"
            );
        }
        return new PaymentRouteSelection(
                PaymentMethod.MANUAL_MOBILE_BANK.name(),
                null,
                "CONTRACTOR",
                allocation.getRecipientType() == ContractorRecipientType.SPECIALIST
                        ? "Платёжный профиль специалиста"
                        : "Платёжный профиль менеджера",
                "",
                ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE.name(),
                null,
                ManualPaymentType.MOBILE_BANK.name(),
                "",
                "",
                "",
                "",
                "",
                ""
        );
    }

    private void scheduleContractorShadowRoute(Long invoiceId, String routeGeneration) {
        if (invoiceId == null) {
            return;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            reserveContractorShadowRouteSafely(invoiceId, routeGeneration);
                        }
                    }
            );
            return;
        }
        reserveContractorShadowRouteSafely(invoiceId, routeGeneration);
    }

    private void reserveContractorShadowRouteSafely(Long invoiceId, String routeGeneration) {
        try {
            contractorPaymentShadowService.reserveForCommonInvoiceId(invoiceId, routeGeneration);
        } catch (RuntimeException e) {
            log.error(
                    "Не удалось записать тестовый маршрут общего счета: invoiceId={}, code={}",
                    invoiceId,
                    e.getClass().getSimpleName()
            );
        }
    }

    private void scheduleContractorShadowRelease(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        Runnable release = () -> {
            try {
                contractorPaymentShadowService.releaseForUnpaidCommonInvoice(
                        invoiceId,
                        "Общий счет переведен в статус \"Не оплачено\""
                );
            } catch (RuntimeException e) {
                log.error("Не удалось освободить тестовый резерв общего счета {}", invoiceId, e);
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            release.run();
                        }
                    }
            );
        } else {
            release.run();
        }
    }

    private void scheduleContractorShadowReconcile(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        Runnable reconcile = () -> {
            try {
                contractorPaymentShadowService.reconcileCommonInvoiceId(invoiceId);
            } catch (RuntimeException e) {
                // The paid common invoice remains a durable retry source for
                // the claim-based contractor reconciliation worker.
                log.error(
                        "Не удалось сразу сверить назначение оплаченного общего счета invoiceId={}, code={}",
                        invoiceId,
                        e.getClass().getSimpleName()
                );
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            reconcile.run();
                        }
                    }
            );
            return;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn(
                    "Пропущена немедленная сверка общего счета без transaction synchronization invoiceId={}",
                    invoiceId
            );
            return;
        }
        reconcile.run();
    }

    private boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return invoice != null
                && invoice.getPaymentRouteSelectedAt() != null
                && !normalize(invoice.getPaymentRouteType()).isBlank();
    }

    private PaymentLinkService paymentLinkService() {
        return paymentLinkServiceProvider.getObject();
    }

    private void ensureCommonPaymentRouteAllowsCompositionChange(CommonInvoice invoice) {
        if (hasFrozenCommonPaymentRoute(invoice)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состав общего счета уже зафиксирован платежным маршрутом. Создайте следующий общий счет."
            );
        }
    }

    private String validateCommonInvoiceManualCardPaymentReason(
            CommonInvoiceManualCardPaymentRequest request
    ) {
        String reason = normalize(request == null ? null : request.reason());
        if (reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите причину ручной оплаты");
        }
        if (reason.length() > 500) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Причина не должна превышать 500 символов"
            );
        }
        return reason;
    }

    private void ensureNoCurrentCommonTbankPaymentForManualCard(CommonInvoice invoice) {
        boolean hasProviderEvidence = !normalize(invoice.getTbankOrderId()).isBlank()
                || !normalize(invoice.getTbankPaymentId()).isBlank()
                || !normalize(invoice.getTbankTerminalKey()).isBlank()
                || invoice.getTbankPaymentAmountKopecks() != null
                || invoice.getTbankPaymentCreatedAt() != null
                || !normalize(invoice.getPaymentUrl()).isBlank();
        if (hasProviderEvidence) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У общего счета есть собственный T-Bank платеж. Сначала сверьте или закройте его."
            );
        }
    }

    private void ensureCommonPaymentRefsSafeForManualCard(List<CommonInvoicePaymentRef> refs) {
        List<String> unsafeStatuses = (refs == null ? List.<CommonInvoicePaymentRef>of() : refs).stream()
                .map(this::paymentRefStatus)
                .filter(status -> !MANUAL_COMMON_PAYMENT_SAFE_REF_STATUSES.contains(status))
                .distinct()
                .toList();
        if (!unsafeStatuses.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "В реестре общего счета есть незавершенный T-Bank платеж: "
                            + String.join(", ", unsafeStatuses)
            );
        }
    }

    private PaymentRouteSelection legacyTbankRoute(CommonInvoice invoice, long remainingKopecks) {
        PaymentProfile profile = paymentProfileService.findByTerminalKey(invoice.getTbankTerminalKey())
                .orElseGet(() -> paymentProfileService.selectForManager(manager(invoice)));
        profile = paymentProfileService.lockForRouting(profile);
        TbankPaymentProfile runtimeProfile = normalize(invoice.getTbankTerminalKey()).isBlank()
                ? paymentProfileService.toRuntime(profile)
                : paymentProfileService.toRuntimeForTerminal(profile, invoice.getTbankTerminalKey());
        if (runtimeProfile == null) {
            runtimeProfile = paymentProfileService.toRuntime(profile);
        }
        return new PaymentRouteSelection(
                TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK,
                profile == null ? null : profile.getId(),
                normalize(profile == null ? null : profile.getCode()),
                normalize(profile == null ? null : profile.getName()),
                normalize(runtimeProfile == null ? invoice.getTbankTerminalKey() : runtimeProfile.terminalKey()),
                null,
                null,
                null,
                "",
                "",
                "",
                "",
                "",
                ""
        );
    }

    private PaymentProfile lockedCommonPaymentProfile(CommonInvoice invoice) {
        PaymentProfile assigned = paymentProfileService.selectForManager(manager(invoice));
        if (assigned != null && Objects.equals(assigned.getId(), invoice.getPaymentRouteProfileId())) {
            return paymentProfileService.lockForRouting(assigned);
        }
        return paymentProfileService.lockByIdForRouting(invoice.getPaymentRouteProfileId());
    }

    private void applyCommonPaymentRoute(
            CommonInvoice invoice,
            PaymentRouteSelection route,
            long remainingKopecks
    ) {
        if (route == null || normalize(route.routeType()).isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось выбрать способ оплаты общего счета");
        }
        invoice.setPaymentRouteType(limit(normalize(route.routeType()).toUpperCase(Locale.ROOT), 32));
        invoice.setPaymentRouteProfileId(route.paymentProfileId());
        invoice.setPaymentRouteProfileCode(limit(route.paymentProfileCode(), 64));
        invoice.setPaymentRouteProfileName(limit(route.paymentProfileName(), 120));
        invoice.setPaymentRouteTerminalKey(limit(route.paymentProfileTerminalKey(), 64));
        invoice.setPaymentRouteManualSource(enumValue(ManualPaymentSource.class, route.manualSource()));
        invoice.setPaymentRouteManualTaskId(route.manualTaskId());
        invoice.setPaymentRouteManualType(enumValue(ManualPaymentType.class, route.manualPaymentType()));
        invoice.setPaymentRouteManualPhone(limit(route.manualPhone(), 32));
        invoice.setPaymentRouteManualRecipient(limit(route.manualRecipientName(), 160));
        invoice.setPaymentRouteManualBankName(null);
        invoice.setPaymentRouteManualUrl(limit(route.manualPaymentUrl(), 512));
        invoice.setPaymentRouteManualButton(limit(route.manualPaymentButtonLabel(), 80));
        invoice.setPaymentRouteManualComment(limit(route.manualComment(), 255));
        invoice.setPaymentRouteInstructionText(limit(route.instructionText(), 1000));
        invoice.setPaymentRouteAmountKopecks(remainingKopecks);
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now());
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        String clean = normalize(value).toUpperCase(Locale.ROOT);
        return clean.isBlank() ? null : Enum.valueOf(type, clean);
    }

    private boolean isManagerTextCommonRoute(CommonInvoice invoice) {
        return TbankRuntimeSettingsService.PAYMENT_SOURCE_MANAGER_TEXT.equals(
                normalize(invoice == null ? null : invoice.getPaymentRouteType()).toUpperCase(Locale.ROOT)
        );
    }

    private boolean isManualMobileBankCommonRoute(CommonInvoice invoice) {
        if (invoice == null || invoice.getPaymentRouteManualType() != ManualPaymentType.MOBILE_BANK) {
            return false;
        }
        return PaymentMethod.MANUAL_MOBILE_BANK.name().equals(
                normalize(invoice.getPaymentRouteType()).toUpperCase(Locale.ROOT)
        ) || isManagerTextCommonRoute(invoice);
    }

    private boolean isTbankCommonRoute(CommonInvoice invoice) {
        return TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK.equals(
                normalize(invoice == null ? null : invoice.getPaymentRouteType()).toUpperCase(Locale.ROOT)
        );
    }

    private String safeCommonManualPaymentUrl(CommonInvoice invoice) {
        return PaymentUrlPolicy.safe(
                invoice == null ? null : invoice.getPaymentRouteManualUrl(),
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
        );
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
            contractorCompletionRewardService.ensureOrderCompletionAccrual(order.getId());
            order.setStatus(publicStatus);
            orderRepository.save(order);
        }
    }

    private void ensureCompletionRewardsBeforeCommonRouting(List<CommonInvoiceOrder> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        items.stream()
                .filter(Objects::nonNull)
                .filter(item -> !item.isPaid())
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .filter(this::canMarkCommonInvoiceItemReady)
                .map(Order::getId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(contractorCompletionRewardService::ensureOrderCompletionAccrual);
    }

    private void markInvoiceOrdersToPay(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null || isBadReviewSuccessor(invoice)) {
            return;
        }
        markInvoiceOrdersToStatus(invoice.getId(), STATUS_TO_PAY);
    }

    private void markInvoiceOrdersReminder(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null || isBadReviewSuccessor(invoice)) {
            return;
        }
        markInvoiceOrdersToStatus(invoice.getId(), STATUS_REMINDER);
    }

    private boolean isBadReviewSuccessor(CommonInvoice invoice) {
        return invoice != null && "BAD_REVIEW_SUCCESSOR".equals(invoice.getInvoicePurpose());
    }

    private void markInvoiceOrdersToStatus(Long invoiceId, String status) {
        List<String> failures = new ArrayList<>();
        for (CommonInvoiceOrder item : invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId)) {
            if (item == null || item.isPaid()) {
                continue;
            }
            Order order = item.getOrder();
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

    private CommonInvoicePaymentRef createPreparedPaymentInitRef(
            CommonInvoice invoice,
            String tbankOrderId,
            TbankPaymentProfile runtimeProfile,
            long amountKopecks
    ) {
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setTbankOrderId(limit(tbankOrderId, 36));
        ref.setTbankTerminalKey(runtimeProfile == null ? null : limit(runtimeProfile.terminalKey(), 64));
        ref.setAmountKopecks(amountKopecks > 0 ? amountKopecks : null);
        ref.setStatus(PAYMENT_REF_INIT_PREPARED);
        ref.setReason("provider_init_prepared");
        paymentRefRepository.save(ref);
        return ref;
    }

    private Optional<CommonInvoicePaymentRef> lockedPreparedPaymentRef(PreparedCommonPaymentInit prepared) {
        if (prepared == null || prepared.paymentRefId() == null) {
            return Optional.empty();
        }
        Optional<CommonInvoicePaymentRef> locked = paymentRefRepository.findByIdForUpdate(prepared.paymentRefId());
        if (locked.isPresent()
                && (!matchesPreparedPaymentRef(locked.get(), prepared)
                || !PREPARED_PAYMENT_REF_LIFECYCLE_STATUSES.contains(
                normalize(locked.get().getStatus()).toUpperCase(Locale.ROOT)))) {
            throw invoiceMembershipChanged("подготовленная T-Bank ссылка сменила реквизиты, статус или общий счет");
        }
        return locked;
    }

    private boolean matchesPreparedPaymentRef(
            CommonInvoicePaymentRef ref,
            PreparedCommonPaymentInit prepared
    ) {
        return ref != null
                && prepared != null
                && Objects.equals(prepared.invoiceId(), paymentRefInvoiceId(ref))
                && normalize(prepared.tbankOrderId()).equals(normalize(ref.getTbankOrderId()))
                && (prepared.runtimeProfile() == null
                || normalize(prepared.runtimeProfile().terminalKey()).equals(normalize(ref.getTbankTerminalKey())))
                && (prepared.remainingKopecks() <= 0
                || (ref.getAmountKopecks() != null
                && ref.getAmountKopecks() == prepared.remainingKopecks()));
    }

    private boolean matchesPreparedCurrentIntent(
            CommonInvoice invoice,
            PreparedCommonPaymentInit prepared
    ) {
        if (invoice == null || prepared == null || prepared.runtimeProfile() == null) {
            return false;
        }
        return normalize(prepared.tbankOrderId()).equals(normalize(invoice.getTbankOrderId()))
                && normalize(prepared.runtimeProfile().terminalKey()).equals(normalize(invoice.getTbankTerminalKey()))
                && invoice.getTbankPaymentAmountKopecks() != null
                && invoice.getTbankPaymentAmountKopecks() == prepared.remainingKopecks();
    }

    private PaymentInitFinishResult paymentInitAlreadyHandledByWebhook(
            CommonInvoice invoice,
            PreparedCommonPaymentInit prepared,
            CommonInvoicePaymentRef preparedRef,
            TbankInitResponse response,
            String paymentUrl
    ) {
        if (invoice == null
                || preparedRef == null
                || response == null
                || !matchesPreparedPaymentRef(preparedRef, prepared)) {
            return null;
        }
        String responsePaymentId = normalize(response.paymentId());
        String refPaymentId = normalize(preparedRef.getTbankPaymentId());
        if (responsePaymentId.isBlank() || refPaymentId.isBlank() || !responsePaymentId.equals(refPaymentId)) {
            return null;
        }
        String status = normalize(preparedRef.getStatus()).toUpperCase(Locale.ROOT);
        if (PAYMENT_REF_PREPAID.equals(status)
                || PAYMENT_REF_CONFIRMED.equals(status)
                || PAYMENT_REF_APPLYING.equals(status)
                || PAYMENT_REF_APPLIED.equals(status)) {
            return new PaymentInitFinishResult(
                    new PublicPaymentInitResponse(paymentUrl, responsePaymentId, status),
                    null,
                    null
            );
        }
        if ("REJECTED".equals(status) || PAYMENT_REF_REFUNDED_STATUSES.contains(status)) {
            return new PaymentInitFinishResult(
                    null,
                    HttpStatus.CONFLICT,
                    "T-Bank уже сообщил конечный статус платежа: " + status
            );
        }
        return null;
    }

    private boolean hasForeignPaymentIdBinding(
            String tbankPaymentId,
            Long currentInvoiceId,
            Long allowedPaymentRefId
    ) {
        String paymentId = normalize(tbankPaymentId);
        if (paymentId.isBlank()) {
            return false;
        }
        boolean invoiceCollision = invoiceRepository.findIdsByTbankPaymentId(paymentId)
                .stream()
                .anyMatch(id -> !Objects.equals(id, currentInvoiceId));
        if (invoiceCollision) {
            return true;
        }
        return paymentRefRepository.findByTbankPaymentId(paymentId)
                .map(CommonInvoicePaymentRef::getId)
                .filter(id -> !Objects.equals(id, allowedPaymentRefId))
                .isPresent();
    }

    private void quarantineWebhookPaymentIdCollision(
            CommonInvoice invoice,
            CommonInvoicePaymentRef currentRef,
            String tbankPaymentId
    ) {
        if (invoice == null) {
            return;
        }
        if (currentRef != null && Objects.equals(invoice.getId(), paymentRefInvoiceId(currentRef))) {
            currentRef.setStatus(PAYMENT_REF_INIT_CONFLICT);
            currentRef.setReason(limit("webhook_payment_id_collision:" + normalize(tbankPaymentId), 160));
            paymentRefRepository.save(currentRef);
        }
        clearCurrentPaymentRef(invoice);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(
                "payment_init_response_collision: webhook PaymentId уже связан с другим платежом; "
                        + "ссылка заблокирована, нужна ручная сверка",
                512
        ));
        invoiceRepository.save(invoice);
    }

    private void quarantineDuplicateProviderIdentityInvoices(
            String identityName,
            String identityValue,
            Collection<Long> invoiceIds
    ) {
        Set<Long> expectedInvoiceIds = invoiceIds == null
                ? Set.of()
                : invoiceIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        if (expectedInvoiceIds.isEmpty()) {
            return;
        }
        Map<Long, CommonInvoice> invoiceSnapshots = loadInvoiceSnapshots(expectedInvoiceIds);
        Map<Long, InvoiceOrderBinding> expectedBindings = invoiceBindings(expectedInvoiceIds);
        Set<Long> accountIds = invoiceSnapshots.values().stream()
                .map(CommonInvoice::getAccount)
                .filter(Objects::nonNull)
                .map(CommonBillingAccount::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        Map<Long, CommonBillingAccount> accountSnapshots = loadAccountSnapshots(accountIds);
        lockOrderAggregatesWithEntities(expectedBindings.keySet());
        lockAccountsInCanonicalOrder(accountSnapshots);
        Map<Long, CommonInvoice> lockedInvoices = lockInvoicesInCanonicalOrder(invoiceSnapshots);
        Set<Long> currentInvoiceIds = new TreeSet<>(providerIdentityInvoiceIds(identityName, identityValue));
        if (!currentInvoiceIds.equals(expectedInvoiceIds)
                || !invoiceBindings(currentInvoiceIds).equals(expectedBindings)) {
            throw invoiceMembershipChanged("состав конфликтующих PaymentId изменился");
        }
        for (Long invoiceId : expectedInvoiceIds) {
            CommonInvoice invoice = lockedInvoices.get(invoiceId);
            if (invoice == null) {
                throw invoiceMembershipChanged("конфликтующий общий счет исчез");
            }
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit(
                    "payment_registry_collision: " + identityName + " " + identityValue
                            + " связан с несколькими общими счетами; webhook не применен",
                    512
            ));
            invoiceRepository.save(invoice);
        }
    }

    private void quarantineWebhookIdentityConstraint(
            Map<String, String> payload,
            RuntimeException failure
    ) {
        String orderId = normalize(payload == null ? null : payload.get("OrderId"));
        String paymentId = normalize(payload == null ? null : payload.get("PaymentId"));
        List<Long> invoiceIdsByOrderId = orderId.isBlank()
                ? List.of()
                : invoiceRepository.findIdsByTbankOrderId(orderId);
        if (new HashSet<>(invoiceIdsByOrderId).size() > 1) {
            quarantineDuplicateProviderIdentityInvoices("OrderId", orderId, invoiceIdsByOrderId);
            return;
        }
        List<Long> invoiceIdsByPaymentId = paymentId.isBlank()
                ? List.of()
                : invoiceRepository.findIdsByTbankPaymentId(paymentId);
        if (new HashSet<>(invoiceIdsByPaymentId).size() > 1) {
            quarantineDuplicateProviderIdentityInvoices("PaymentId", paymentId, invoiceIdsByPaymentId);
            return;
        }
        CommonInvoicePaymentRef ref = findProviderPaymentRefCandidate(orderId, paymentId).orElse(null);
        Long invoiceId = paymentRefInvoiceId(ref);
        if (invoiceId == null && !orderId.isBlank()) {
            invoiceId = invoiceIdsByOrderId.stream().findFirst().orElse(null);
        }
        if (invoiceId == null && !paymentId.isBlank()) {
            invoiceId = invoiceIdsByPaymentId.stream().findFirst().orElse(null);
        }
        CommonInvoice invoice = invoiceId == null
                ? null
                : lockedInvoice(invoiceId).orElse(null);
        CommonInvoicePaymentRef lockedRef = lockedPaymentRef(ref).orElse(ref);
        if (lockedRef != null && Objects.equals(invoiceId, paymentRefInvoiceId(lockedRef))) {
            if (isCurrentPaymentRegistryConstraintViolation(failure)) {
                if (!paymentId.isBlank()
                        && !hasForeignPaymentIdBinding(paymentId, invoiceId, lockedRef.getId())) {
                    lockedRef.setTbankPaymentId(limit(paymentId, 64));
                }
                String terminalKey = normalize(payload == null ? null : payload.get("TerminalKey"));
                if (!terminalKey.isBlank()) {
                    lockedRef.setTbankTerminalKey(limit(terminalKey, 64));
                }
                enrichPaymentRefAmountFromWebhook(lockedRef, payload);
            }
            lockedRef.setStatus(PAYMENT_REF_INIT_CONFLICT);
            lockedRef.setReason(limit(
                    (isCurrentPaymentRegistryConstraintViolation(failure)
                            ? "webhook_current_registry_constraint:"
                            : "webhook_identity_constraint:") + paymentId,
                    160
            ));
            paymentRefRepository.save(lockedRef);
            if (isCurrentPaymentRegistryConstraintViolation(failure)
                    && !normalize(lockedRef.getTbankPaymentId()).isBlank()) {
                entityManager.flush();
            }
        }
        if (invoice != null) {
            if (matchesCurrentPaymentRef(invoice, orderId, paymentId)) {
                clearCurrentPaymentRef(invoice);
            } else {
                invoice.setPaymentUrl(null);
            }
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(
                    (isCurrentPaymentRegistryConstraintViolation(failure)
                            ? "payment_registry_collision: у общего счета обнаружено несколько активных T-Bank ссылок"
                            : "payment_init_response_collision: PaymentId " + paymentId
                                    + " нарушил уникальность durable-реестра")
                            + "; нужна ручная сверка (" + readableException(failure) + ")",
                    512
            ));
            invoiceRepository.save(invoice);
        }
    }

    private List<Long> providerIdentityInvoiceIds(String identityName, String identityValue) {
        if ("OrderId".equals(identityName)) {
            return invoiceRepository.findIdsByTbankOrderId(identityValue);
        }
        if ("PaymentId".equals(identityName)) {
            return invoiceRepository.findIdsByTbankPaymentId(identityValue);
        }
        throw new IllegalArgumentException("Unsupported payment identity: " + identityName);
    }

    private boolean isDurablePaymentRegistryConstraintViolation(RuntimeException failure) {
        return isPaymentIdentityConstraintViolation(failure)
                || isCurrentPaymentRegistryConstraintViolation(failure);
    }

    private boolean isCurrentPaymentRegistryConstraintViolation(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            String message = normalize(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("uk_common_invoice_payment_refs_current_invoice")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isPaymentIdentityConstraintViolation(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            String message = normalize(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("uk_common_invoice_payment_ref_payment")
                    || (message.contains("tbank_payment_id")
                    && (message.contains("duplicate") || message.contains("unique")))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void enrichPaymentRefAmountFromWebhook(
            CommonInvoicePaymentRef paymentRef,
            Map<String, String> payload
    ) {
        if (paymentRef == null || paymentRef.getAmountKopecks() != null) {
            return;
        }
        String amount = normalize(payload == null ? null : payload.get("Amount"));
        if (amount.isBlank()) {
            return;
        }
        try {
            long parsed = Long.parseLong(amount);
            if (parsed <= 0) {
                throw new NumberFormatException("amount must be positive");
            }
            paymentRef.setAmountKopecks(parsed);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная сумма webhook", e);
        }
    }

    private long parseWebhookAmount(Map<String, String> payload) {
        String amount = normalize(payload == null ? null : payload.get("Amount"));
        if (amount.isBlank()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(amount);
            if (parsed <= 0) {
                throw new NumberFormatException("amount must be positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная сумма webhook", e);
        }
    }

    private void updateCurrentPaymentAnchorFromWebhook(
            CommonInvoicePaymentRef anchor,
            String paymentId,
            String terminalKey,
            Map<String, String> payload,
            String targetStatus,
            String reason
    ) {
        if (anchor == null) {
            return;
        }
        if (!normalize(paymentId).isBlank()) {
            anchor.setTbankPaymentId(limit(paymentId, 64));
        }
        if (!normalize(terminalKey).isBlank()) {
            anchor.setTbankTerminalKey(limit(terminalKey, 64));
        }
        enrichPaymentRefAmountFromWebhook(anchor, payload);
        String currentStatus = normalize(anchor.getStatus()).toUpperCase(Locale.ROOT);
        boolean pendingUpdate = PAYMENT_REF_CURRENT.equals(targetStatus);
        if (!pendingUpdate
                || currentStatus.isBlank()
                || PAYMENT_REF_INIT_PREPARED.equals(currentStatus)
                || PAYMENT_REF_INIT_CONFLICT.equals(currentStatus)
                || PAYMENT_REF_CURRENT.equals(currentStatus)) {
            anchor.setStatus(limit(targetStatus, 32));
            anchor.setReason(limit(reason, 160));
        }
        paymentRefRepository.save(anchor);
        entityManager.flush();
    }

    private boolean isTerminalPaymentWebhook(String status, boolean success, String errorCode) {
        String normalizedStatus = normalize(status).toUpperCase(Locale.ROOT);
        return PAYMENT_REF_CONFIRMED.equals(normalizedStatus)
                || "REJECTED".equals(normalizedStatus)
                || PAYMENT_REF_REFUNDED_STATUSES.contains(normalizedStatus)
                || (!success && !normalize(errorCode).isBlank() && !"0".equals(normalize(errorCode)));
    }

    private String durableTerminalWebhookStatus(String status, boolean success, String errorCode) {
        String normalizedStatus = normalize(status).toUpperCase(Locale.ROOT);
        if (PAYMENT_REF_CONFIRMED.equals(normalizedStatus)
                || "REJECTED".equals(normalizedStatus)
                || PAYMENT_REF_REFUNDED_STATUSES.contains(normalizedStatus)) {
            return normalizedStatus;
        }
        if (!success && !normalize(errorCode).isBlank() && !"0".equals(normalize(errorCode))) {
            return "REJECTED";
        }
        return normalizedStatus.isBlank() ? "REJECTED" : normalizedStatus;
    }

    private void resolvePreparedPaymentInitAfterManualCheck(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null) {
            return;
        }
        String previousAttention = normalize(invoice.getLastError());
        if (paymentRefRepository.existsByInvoice_IdAndStatusIn(
                invoice.getId(),
                PAYMENT_INIT_MANUAL_BLOCKING_REF_STATUSES
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя завершить ручную сверку: платеж еще активен, отменяется или уже подтвержден"
            );
        }
        List<CommonInvoicePaymentRef> unresolved = new ArrayList<>();
        unresolved.addAll(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(
                invoice.getId(),
                PAYMENT_REF_INIT_PREPARED
        ));
        unresolved.addAll(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(
                invoice.getId(),
                PAYMENT_REF_INIT_CONFLICT
        ));
        if (unresolved.stream().anyMatch(this::canCancelInitializedPaymentRef)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя завершить ручную сверку: найден созданный платеж, который сначала нужно отменить"
            );
        }
        for (CommonInvoicePaymentRef ref : unresolved) {
            ref.setStatus(PAYMENT_REF_ARCHIVED);
            ref.setReason(manualPaymentInitCheckAuditReason(ref.getReason()));
        }
        if (!unresolved.isEmpty()) {
            paymentRefRepository.saveAll(unresolved);
        }

        if (normalize(invoice.getTbankOrderId()).isBlank()
                && normalize(invoice.getTbankPaymentId()).isBlank()) {
            return;
        }
        Optional<CommonInvoicePaymentRef> existing = lockedPaymentRefByProviderBinding(
                invoice.getTbankOrderId(),
                invoice.getTbankPaymentId()
        );
        if (existing.isPresent() && !Objects.equals(invoice.getId(), paymentRefInvoiceId(existing.get()))) {
            throw invoiceMembershipChanged("T-Bank ссылка для ручной сверки принадлежит другому счету");
        }
        CommonInvoicePaymentRef ref = existing.orElseGet(() -> {
            CommonInvoicePaymentRef created = new CommonInvoicePaymentRef();
            created.setInvoice(invoice);
            return created;
        });
        copyCurrentPaymentBindingToRef(invoice, ref);
        ref.setStatus(PAYMENT_REF_ARCHIVED);
        boolean alreadyAudited = unresolved.stream().anyMatch(unresolvedRef ->
                Objects.equals(unresolvedRef.getId(), ref.getId())
        );
        if (!alreadyAudited) {
            ref.setReason(manualPaymentInitCheckAuditReason(previousAttention));
        }
        paymentRefRepository.save(ref);
        flushPaymentRefProviderEvidence(ref);
        clearCurrentPaymentRef(invoice);
        Authentication authentication = currentAuthentication();
        log.warn(
                "Common invoice {} payment-init evidence manually reconciled by {}",
                invoice.getId(),
                authentication == null ? "unknown" : normalize(authentication.getName())
        );
    }

    private String manualPaymentInitCheckAuditReason(String previousReason) {
        Authentication authentication = currentAuthentication();
        String actor = authentication == null ? "unknown" : normalize(authentication.getName());
        String previous = normalize(previousReason);
        return limit(
                PAYMENT_INIT_MANUALLY_CHECKED_BY_PREFIX + limit(actor.isBlank() ? "unknown" : actor, 40)
                        + (previous.isBlank() ? "" : "; previous=" + previous),
                160
        );
    }

    private void archiveCurrentPaymentRef(CommonInvoice invoice, String reason) {
        if (invoice == null
                || (normalize(invoice.getTbankOrderId()).isBlank() && normalize(invoice.getTbankPaymentId()).isBlank())) {
            return;
        }
        Optional<CommonInvoicePaymentRef> existing = lockedPaymentRefByProviderBinding(
                invoice.getTbankOrderId(),
                invoice.getTbankPaymentId()
        );
        if (existing.isPresent()) {
            CommonInvoicePaymentRef ref = existing.get();
            if (!Objects.equals(invoice.getId(), paymentRefInvoiceId(ref))) {
                throw invoiceMembershipChanged("T-Bank ссылка принадлежит другому общему счету");
            }
            copyCurrentPaymentBindingToRef(invoice, ref);
            String status = normalize(ref.getStatus());
            if (status.isBlank()
                    || PAYMENT_REF_INIT_PREPARED.equals(status)
                    || PAYMENT_REF_INIT_CONFLICT.equals(status)
                    || PAYMENT_REF_CURRENT.equals(status)
                    || PAYMENT_REF_ARCHIVED.equals(status)) {
                ref.setStatus(canCancelCurrentPaymentRef(invoice)
                        ? PAYMENT_REF_CANCEL_PENDING
                        : PAYMENT_REF_ARCHIVED);
            }
            ref.setReason(limit(reason, 160));
            paymentRefRepository.save(ref);
            flushPaymentRefProviderEvidence(ref);
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
        flushPaymentRefProviderEvidence(ref);
    }

    private boolean canCancelCurrentPaymentRef(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() != CommonInvoiceStatus.PAID
                && !normalize(invoice.getTbankPaymentId()).isBlank()
                && !normalize(invoice.getTbankTerminalKey()).isBlank()
                && invoice.getTbankPaymentAmountKopecks() != null
                && invoice.getTbankPaymentAmountKopecks() > 0;
    }

    private PreparedArchivedPaymentCancel prepareArchivedPaymentCancel(Long refId, Long candidateInvoiceId) {
        Long expectedInvoiceId = candidateInvoiceId == null
                ? paymentRefRepository.findInvoiceIdById(refId).orElse(null)
                : candidateInvoiceId;
        CommonInvoice invoice = expectedInvoiceId == null
                ? null
                : lockedInvoice(expectedInvoiceId).orElse(null);
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(refId).orElse(null);
        if (ref == null) {
            return null;
        }
        if (!Objects.equals(expectedInvoiceId, paymentRefInvoiceId(ref))) {
            throw invoiceMembershipChanged("архивная платежная ссылка сменила общий счет");
        }
        String status = normalize(ref == null ? null : ref.getStatus());
        if ((!PAYMENT_REF_CANCEL_PENDING.equals(status)
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
            markArchivedPaymentCancelFailedFinal(ref, invoice);
            return null;
        }
        if (invoice != null && invoice.getStatus() == CommonInvoiceStatus.PAID) {
            ref.setStatus(PAYMENT_REF_ARCHIVED);
            ref.setReason(limit("paid_invoice_cancel_skipped", 160));
            paymentRefRepository.save(ref);
            log.warn("Автоотмена архивной T-Bank ссылки общего счета пропущена: ref={}, invoice={} уже PAID",
                    ref.getId(), invoice.getId());
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
        return new PreparedArchivedPaymentCancel(ref.getId(), expectedInvoiceId, paymentId, terminalKey, amount);
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

    private void finishArchivedPaymentCancel(PreparedArchivedPaymentCancel prepared, String status) {
        if (prepared == null) {
            return;
        }
        CommonInvoice invoice = prepared.invoiceId() == null
                ? null
                : lockedInvoice(prepared.invoiceId()).orElse(null);
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(prepared.refId()).orElse(null);
        if (ref == null || !PAYMENT_REF_CANCELING.equals(normalize(ref.getStatus()))) {
            return;
        }
        if (!Objects.equals(prepared.invoiceId(), paymentRefInvoiceId(ref))) {
            throw invoiceMembershipChanged("архивная платежная ссылка сменила общий счет");
        }
        String normalizedStatus = normalize(status);
        if (PAYMENT_REF_CANCEL_FAILED.equals(normalizedStatus)
                && cancelAttempts(ref) >= PAYMENT_REF_CANCEL_MAX_ATTEMPTS) {
            normalizedStatus = PAYMENT_REF_CANCEL_FAILED_FINAL;
        }
        ref.setStatus(limit(normalizedStatus, 32));
        paymentRefRepository.save(ref);
        if (PAYMENT_REF_CANCEL_FAILED_FINAL.equals(normalizedStatus)) {
            markInvoiceNeedsAttentionForFinalCancelFailure(ref, invoice);
        }
    }

    private void markArchivedPaymentCancelFailedFinal(CommonInvoicePaymentRef ref, CommonInvoice invoice) {
        if (ref == null) {
            return;
        }
        ref.setStatus(PAYMENT_REF_CANCEL_FAILED_FINAL);
        paymentRefRepository.save(ref);
        markInvoiceNeedsAttentionForFinalCancelFailure(ref, invoice);
    }

    private void markInvoiceNeedsAttentionForFinalCancelFailure(
            CommonInvoicePaymentRef ref,
            CommonInvoice invoice
    ) {
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

    private Long paymentRefInvoiceId(CommonInvoicePaymentRef ref) {
        return ref == null || ref.getInvoice() == null ? null : ref.getInvoice().getId();
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
        Optional<CommonInvoicePaymentRef> existing = lockedPaymentRefByProviderBinding(
                invoice.getTbankOrderId(),
                invoice.getTbankPaymentId()
        );
        if (existing.isPresent()) {
            CommonInvoicePaymentRef ref = existing.get();
            if (!Objects.equals(invoice.getId(), paymentRefInvoiceId(ref))) {
                throw invoiceMembershipChanged("T-Bank ссылка принадлежит другому общему счету");
            }
            copyCurrentPaymentBindingToRef(invoice, ref);
            ref.setStatus(limit(status, 32));
            ref.setReason(limit(reason, 160));
            paymentRefRepository.save(ref);
            flushPaymentRefProviderEvidence(ref);
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
        flushPaymentRefProviderEvidence(ref);
        clearCurrentPaymentRef(invoice);
    }

    private void flushPaymentRefProviderEvidence(CommonInvoicePaymentRef ref) {
        if (ref != null && !normalize(ref.getTbankPaymentId()).isBlank()) {
            entityManager.flush();
        }
    }

    private Optional<CommonInvoicePaymentRef> lockedPaymentRefByProviderBinding(
            String tbankOrderId,
            String tbankPaymentId
    ) {
        Optional<CommonInvoicePaymentRef> candidate = normalize(tbankOrderId).isBlank()
                ? Optional.empty()
                : paymentRefRepository.findByTbankOrderId(tbankOrderId);
        if (candidate.isEmpty() && !normalize(tbankPaymentId).isBlank()) {
            candidate = paymentRefRepository.findByTbankPaymentId(tbankPaymentId);
        }
        if (candidate.isEmpty() || candidate.get().getId() == null) {
            return Optional.empty();
        }
        return paymentRefRepository.findByIdForUpdate(candidate.get().getId());
    }

    private Optional<CommonInvoicePaymentRef> findProviderPaymentRefCandidate(
            String tbankOrderId,
            String tbankPaymentId
    ) {
        Optional<CommonInvoicePaymentRef> candidate = normalize(tbankOrderId).isBlank()
                ? Optional.empty()
                : paymentRefRepository.findByTbankOrderId(tbankOrderId);
        if (candidate.isEmpty() && !normalize(tbankPaymentId).isBlank()) {
            candidate = paymentRefRepository.findByTbankPaymentId(tbankPaymentId);
        }
        return candidate;
    }

    private void quarantineMissingCurrentPaymentAnchor(
            CommonInvoice invoice,
            String webhookOrderId,
            String webhookPaymentId
    ) {
        if (invoice == null) {
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setPaymentUrl(null);
        invoice.setLastError(limit(
                "payment_registry_missing: текущая T-Bank ссылка "
                        + paymentRefLabel(webhookOrderId, webhookPaymentId)
                        + " не найдена в durable-реестре; webhook не применен, нужна ручная сверка",
                512
        ));
        invoiceRepository.save(invoice);
    }

    private void copyCurrentPaymentBindingToRef(CommonInvoice invoice, CommonInvoicePaymentRef ref) {
        if (invoice == null || ref == null) {
            return;
        }
        if (!normalize(invoice.getTbankOrderId()).isBlank()) {
            ref.setTbankOrderId(limit(invoice.getTbankOrderId(), 36));
        }
        if (!normalize(invoice.getTbankPaymentId()).isBlank()) {
            ref.setTbankPaymentId(limit(invoice.getTbankPaymentId(), 64));
        }
        if (!normalize(invoice.getTbankTerminalKey()).isBlank()) {
            ref.setTbankTerminalKey(limit(invoice.getTbankTerminalKey(), 64));
        }
        if (invoice.getTbankPaymentAmountKopecks() != null) {
            ref.setAmountKopecks(invoice.getTbankPaymentAmountKopecks());
        }
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
        String responseOrderId = normalize(response.orderId());
        String preparedOrderId = normalize(prepared.tbankOrderId());
        String paymentId = normalize(response.paymentId());
        CommonInvoicePaymentRef ref = lockedPreparedPaymentRef(prepared).orElse(null);
        if (ref == null) {
            ref = new CommonInvoicePaymentRef();
            ref.setInvoice(invoice);
            ref.setTbankOrderId(preparedOrderId.isBlank() ? null : limit(preparedOrderId, 36));
            ref.setTbankTerminalKey(prepared.runtimeProfile() == null
                    ? null
                    : limit(prepared.runtimeProfile().terminalKey(), 64));
            ref.setAmountKopecks(prepared.remainingKopecks() > 0 ? prepared.remainingKopecks() : null);
        }
        if (ref != null
                && !normalize(ref.getTbankPaymentId()).isBlank()
                && !paymentId.isBlank()
                && !normalize(ref.getTbankPaymentId()).equals(paymentId)) {
            ref.setStatus(PAYMENT_REF_INIT_CONFLICT);
            ref.setReason(limit(
                    "provider_payment_mismatch:" + paymentId + ":" + reason,
                    160
            ));
            paymentRefRepository.save(ref);
            return;
        }
        if (hasForeignPaymentIdBinding(paymentId, invoice.getId(), ref.getId())) {
            ref.setStatus(PAYMENT_REF_INIT_CONFLICT);
            ref.setReason(limit("response_payment_id_collision:" + paymentId + ":" + reason, 160));
            paymentRefRepository.save(ref);
            return;
        }
        if (!paymentId.isBlank()) {
            ref.setTbankPaymentId(limit(paymentId, 64));
        }
        String responseTerminalKey = normalize(response.terminalKey());
        ref.setTbankTerminalKey(responseTerminalKey.isBlank()
                ? (prepared.runtimeProfile() == null
                ? null
                : limit(prepared.runtimeProfile().terminalKey(), 64))
                : limit(responseTerminalKey, 64));
        ref.setAmountKopecks(response.amount() != null && response.amount() > 0
                ? response.amount()
                : (prepared.remainingKopecks() > 0 ? prepared.remainingKopecks() : null));
        String currentStatus = normalize(ref.getStatus());
        if (currentStatus.isBlank()
                || PAYMENT_REF_INIT_PREPARED.equals(currentStatus)
                || PAYMENT_REF_INIT_CONFLICT.equals(currentStatus)
                || PAYMENT_REF_CURRENT.equals(currentStatus)
                || PAYMENT_REF_ARCHIVED.equals(currentStatus)) {
            ref.setStatus(canCancelInitializedPaymentRef(ref)
                    ? PAYMENT_REF_CANCEL_PENDING
                    : PAYMENT_REF_INIT_CONFLICT);
        }
        String providerOrderEvidence = responseOrderId.isBlank() || responseOrderId.equals(preparedOrderId)
                ? ""
                : "provider_order_mismatch=" + responseOrderId + ":";
        ref.setReason(limit(providerOrderEvidence + reason, 160));
        paymentRefRepository.save(ref);
        if (!paymentId.isBlank()) {
            entityManager.flush();
        }
    }

    private void markPreparedPaymentInitConflict(
            CommonInvoice invoice,
            PreparedCommonPaymentInit prepared,
            String reason
    ) {
        if (invoice == null || prepared == null) {
            return;
        }
        CommonInvoicePaymentRef ref = lockedPreparedPaymentRef(prepared).orElse(null);
        if (ref == null) {
            String orderId = normalize(prepared.tbankOrderId());
            if (orderId.isBlank()) {
                return;
            }
            ref = new CommonInvoicePaymentRef();
            ref.setInvoice(invoice);
            ref.setTbankOrderId(limit(orderId, 36));
            ref.setTbankTerminalKey(prepared.runtimeProfile() == null
                    ? null
                    : limit(prepared.runtimeProfile().terminalKey(), 64));
            ref.setAmountKopecks(prepared.remainingKopecks() > 0 ? prepared.remainingKopecks() : null);
        }
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

    public record ManagerBoardPage(
            List<OrderDTOList> cards,
            long totalCards,
            int linkedOrderCount
    ) {
        public ManagerBoardPage {
            cards = cards == null ? List.of() : List.copyOf(cards);
        }
    }

    public record ManagerBoardMetrics(
            Map<String, Integer> cardCounts,
            Map<String, Integer> linkedOrderCounts
    ) {
        public ManagerBoardMetrics {
            cardCounts = cardCounts == null ? Map.of() : Map.copyOf(cardCounts);
            linkedOrderCounts = linkedOrderCounts == null ? Map.of() : Map.copyOf(linkedOrderCounts);
        }
    }

    private record BoardInvoiceView(
            CommonInvoice invoice,
            List<CommonInvoiceOrder> items
    ) {
    }

    private record InvoiceOrderBinding(
            Long invoiceId,
            Long accountId
    ) {
    }

    private record PreparedCompanyReconcile(
            Long linkId,
            Long accountId,
            Long companyId,
            String leaseToken,
            int attempt
    ) {
    }

    private record LockedInvoicePaymentPrelude(
            CommonInvoice invoice,
            Map<Long, List<PaymentLink>> paymentLinksByOrder
    ) {
    }

    private record PreparedCommonInvoiceMessage(
            Long invoiceId,
            Company chatCompany,
            String managerClientId,
            String groupId,
            String message,
            String telegramCopyTransferNumber,
            boolean reminder,
            boolean manual
    ) {
    }

    private record PreparedCommonPaymentInit(
            Long invoiceId,
            Long paymentRefId,
            String email,
            long remainingKopecks,
            TbankPaymentProfile runtimeProfile,
            String tbankOrderId,
            PublicPaymentInitResponse cachedResponse,
            String deferredFailure
    ) {
    }

    private record ConfirmedStandaloneApplication(
            CommonInvoiceOrder item,
            PaymentLink link,
            long amountKopecks
    ) {
    }

    private record PaymentInitFinishResult(
            PublicPaymentInitResponse response,
            HttpStatus failureStatus,
            String failureMessage
    ) {
    }

    private record PreparedArchivedPaymentCancel(
            Long refId,
            Long invoiceId,
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
