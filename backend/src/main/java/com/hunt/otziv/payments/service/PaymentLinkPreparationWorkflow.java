package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.common_billing.api.CommonInvoicePaymentOperations;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService.FrozenPaymentLinkAction;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.payments.service.ManualPaymentConfirmationWorkflow.MANUAL_UNPAID_CLOSED_AUDIT_PREFIX;
import static com.hunt.otziv.payments.service.CommonInvoiceRouteSelector.MANUAL_PAYMENT_METHODS;

/**
 * Creates or reuses an order's payment instructions under the order lock,
 * including permission checks and reservation of the selected payment route.
 * Bank HTTP initialization belongs to PaymentLinkInitializationWorkflow.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentLinkPreparationWorkflow {

    private final PaymentLinkAmountPolicy amountPolicy;

    private final PaymentLinkLifecycleService lifecycleService;

    private final BankInitializationStateService bankInitializationState;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentBankObservationService bankObservations;

    static final String PAYMENT_SERVICE_NAME = "Репутационное сопровождение компании в сети Интернет";

    static final String LIVE_ROUTING_MISSING_ALLOCATION_PREFIX = "contractor_live_routing_missing_allocation:";

    static final String LIVE_ROUTING_FAIL_CLOSED_REMINDER_SOURCE = "PAYMENT_LIVE_ROUTING_FAIL_CLOSED";

    static final Set<PaymentLinkStatus> REUSABLE_STATUSES = Set.of(PaymentLinkStatus.CREATED, PaymentLinkStatus.INITIATED, PaymentLinkStatus.AUTHORIZED, PaymentLinkStatus.WAITING_MANUAL_PAYMENT, PaymentLinkStatus.MANUAL_REPORTED);

    static final Set<PaymentLinkStatus> ROUTE_CHANGE_CURRENT_STATUSES = Set.of(PaymentLinkStatus.CREATED, PaymentLinkStatus.INITIATED, PaymentLinkStatus.AUTHORIZED, PaymentLinkStatus.WAITING_MANUAL_PAYMENT, PaymentLinkStatus.MANUAL_REPORTED, PaymentLinkStatus.NEEDS_RECONCILIATION);

    static final Set<PaymentLinkStatus> RECONCILIATION_BLOCKING_STATUSES = Set.of(PaymentLinkStatus.NEEDS_RECONCILIATION);

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final TbankPaymentProperties properties;

    private final TbankRuntimeSettingsService runtimeSettingsService;

    private final PaymentProfileService paymentProfileService;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final ManualPaymentTaskRepository manualPaymentTaskRepository;

    private final CommonInvoicePaymentOperations commonInvoicePayments;

    private final OrderPaymentIntegrityService orderPaymentIntegrityService;

    private final ManagerAccessService managerAccessService;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    private final ContractorPaymentShadowService contractorPaymentShadowService;

    private final ContractorPaymentRuntimeSwitch contractorPaymentRuntimeSwitch;

    private final ContractorActualPaymentAttributionService actualPaymentAttributionService;

    private final PaymentIssueReminderService paymentIssueReminderService;

    private final PaymentLinkTransactionExecutor transactionExecutor;

    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public ManagerPaymentLinkResponse createForOrder(Long orderId) {
        requirePaymentLinksEnabled();
        return prepareForOrder(orderId, null, false).response();
    }

    /**
     * Public-token replacement handles failures inside its existing transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    ManagerPaymentLinkResponse createReplacementInCurrentTransaction(Long orderId) {
        return createForOrder(orderId);
    }

    public ManagerPaymentLinkResponse createForOrderInNewTransaction(Long orderId) {
        return transactionExecutor.required(() -> {
            requirePaymentLinksEnabled();
            return prepareForOrder(orderId, null, false).response();
        });
    }

    /**
     * Manager-facing entry point. The current order row is locked before the
     * object-scope check, so a concurrent reassignment cannot invalidate the
     * authorization between the check and payment-link creation.
     */
    @Transactional
    public ManagerPaymentLinkResponse createForOrderAuthorized(Long orderId, Authentication authentication) {
        return prepareForOrder(orderId, authentication, true).response();
    }

    @Transactional
    public PaymentInstructionPreparation prepareForOrderAuthorized(Long orderId, Authentication authentication) {
        return prepareForOrder(orderId, authentication, true);
    }

    @Transactional
    public ManagerPaymentLinkResponse markPaperInvoiceIssuedAuthorized(Long orderId, Authentication authentication) {
        requireOwnerOrAdmin(authentication);
        Order order = orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        managerAccessService.requireOrderAccess(orderId, authentication);
        PaymentLink link = currentRouteChangeLink(paymentLinkRepository.findByOrderIdForUpdate(orderId)).filter(this::isPaperInvoice).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Для заказа не подготовлен бумажный счёт"));
        if (link.getPaperInvoiceIssuedAt() == null) {
            if (link.getStatus() != PaymentLinkStatus.CREATED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Бумажный счёт уже находится в обработке или требует сверки");
            }
            LocalDateTime now = LocalDateTime.now();
            link.setPaperInvoiceIssuedAt(now);
            link.setInitiatedAt(now);
            link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
            link.setLastError(null);
            paymentLinkRepository.save(link);
            log.info("Paper invoice marked as issued: orderId={}, linkId={}, actor={}", orderId, link.getId(), authentication.getName());
        }
        return toManagerResponseWithShadowRoute(link);
    }

    void requireLiveRouteChangeEnabled() {
        if (!contractorPaymentLiveRoutingService.enabledForNewRoutes()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Смена способа оплаты доступна только при активном LIVE-routing");
        }
    }

    Optional<PaymentLink> currentRouteChangeLink(List<PaymentLink> links) {
        return (links == null ? List.<PaymentLink>of() : links).stream().filter(Objects::nonNull).filter(link -> ROUTE_CHANGE_CURRENT_STATUSES.contains(link.getStatus())).max(Comparator.comparing(PaymentLink::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(PaymentLink::getId, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    void requireOwnerOrAdmin(Authentication authentication) {
        boolean allowed = authentication != null && authentication.getAuthorities().stream().map(authority -> authority == null ? "" : normalize(authority.getAuthority())).anyMatch(role -> "ROLE_OWNER".equals(role) || "ROLE_ADMIN".equals(role));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Бумажный счёт может включить только владелец или администратор");
        }
    }

    /**
     * Releases only the exact pristine source created for a definitely-unsent external message.
     */
    @Transactional
    public boolean cancelFreshUnsentPreparationAuthorized(String token, Long orderId, Authentication authentication) {
        if (token == null || token.isBlank() || orderId == null || orderId <= 0) {
            return false;
        }
        Order order = orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        managerAccessService.requireOrderAccess(order.getId(), authentication);
        PaymentLink link = paymentLinkRepository.findByTokenForUpdate(token).orElse(null);
        if (link == null || link.getOrder() == null || !orderId.equals(link.getOrder().getId())) {
            return false;
        }
        boolean pristine = (link.getStatus() == PaymentLinkStatus.CREATED || link.getStatus() == PaymentLinkStatus.WAITING_MANUAL_PAYMENT) && link.getConfirmedAmountKopecks() == null && link.getTbankPaymentId() == null && link.getManualReportedAt() == null && link.getPaidAt() == null;
        if (!pristine) {
            return false;
        }
        link.setStatus(PaymentLinkStatus.CANCELED);
        link.setLastError("Внешнее сообщение достоверно не отправлено");
        taskReceiptIntegrationService.release(link, link.getLastError());
        paymentLinkRepository.save(link);
        if (link.getContractorAllocationId() != null) {
            contractorPaymentLiveRoutingService.releaseClosedPaymentLink(link);
        }
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    private PaymentInstructionPreparation prepareForOrder(Long orderId, Authentication authentication, boolean requireAuthorization) {
        Optional<Order> lockedOrder = orderRepository.findByIdForCounterUpdate(orderId);
        Order order = (requireAuthorization ? lockedOrder : lockedOrder.or(() -> orderRepository.findByIdForMutation(orderId))).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        if (requireAuthorization) {
            managerAccessService.requireOrderAccess(orderId, authentication);
            requirePaymentLinksEnabled();
        }
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        LocalDateTime now = LocalDateTime.now();
        expireStaleManualLinks(now);
        // expireManualLinks is a bulk update with clearAutomatically=true. Reload the
        // order after that clear so its lazy status/manager/company proxies remain
        // attached for payment-integrity checks and payment-link construction.
        order = orderRepository.findByIdForCounterUpdate(orderId).or(() -> orderRepository.findByIdForMutation(orderId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        List<PaymentLink> lockedOrderLinks = paymentLinkRepository.findByOrderIdForUpdate(orderId);
        recoverOrderBankInitReservationsBeforeCreation(lockedOrderLinks, now);
        ensureNoPendingVerifiedManualRouteTransition(lockedOrderLinks);
        synchronizeClosedContractorRoutes(lockedOrderLinks);
        orderPaymentIntegrityService.assertPaymentCycleAllowed(order);
        if (paymentLinkRepository.existsByOrder_IdAndStatusIn(orderId, RECONCILIATION_BLOCKING_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Предыдущий платеж уже создан в банке и требует сверки. Новый счет заблокирован.");
        }
        long amountKopecks = amountKopecks(payableSum(order));
        if (amountKopecks <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа нет суммы к оплате");
        }
        InvoicePaymentMode configuredMode = invoicePaymentMode(order);
        if (configuredMode == InvoicePaymentMode.OWNER_PAPER_INVOICE) {
            return prepareOwnerPaperInvoice(order, lockedOrderLinks, amountKopecks, now);
        }
        Manager manager = orderManager(order);
        PaymentProfile profile = null;
        Optional<PaymentLink> existing = paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(orderId, REUSABLE_STATUSES, now);
        if (existing.isPresent()) {
            PaymentLink link = existing.get();
            if (hasCompetingBlockingPayment(link, lockedOrderLinks)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа уже есть другой банковский платеж. Проверьте его статус перед продолжением.");
            }
            FrozenPaymentLinkAction linkedRouteAction = link.getContractorAllocationId() == null ? FrozenPaymentLinkAction.KEEP : contractorPaymentLiveRoutingService.frozenPaymentLinkAction(link.getId(), link.getContractorAllocationId());
            if (linkedRouteAction == FrozenPaymentLinkAction.BLOCK_RECONCILIATION) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Предыдущий платеж требует сверки перед созданием нового счета");
            }
            if (linkedRouteAction == FrozenPaymentLinkAction.START_NEW_ATTEMPT) {
                if (!canRetireStaleLink(link)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Предыдущий платеж уже закрыт для назначения получателя, но банковская операция " + "еще требует сверки");
                }
                retireStaleReusableLink(link);
                contractorPaymentLiveRoutingService.releaseClosedPaymentLink(link);
            } else if (isExplicitElectronicPaymentMode(configuredMode)) {
                if (canReuseExplicitPaymentRoute(link, configuredMode, amountKopecks)) {
                    return new PaymentInstructionPreparation(toManagerResponseWithShadowRoute(link), false);
                }
                if (canRetireStaleLink(link)) {
                    retireStaleReusableLink(link);
                    if (link.getContractorAllocationId() != null) {
                        contractorPaymentLiveRoutingService.releaseClosedPaymentLink(link);
                    }
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа уже есть платеж в процессе по другому способу оплаты. " + "Проверьте платеж в журнале перед созданием нового счета.");
                }
            } else if (isFrozenContractorRoute(link)) {
                if (canReuseContractorLink(link, amountKopecks)) {
                    return new PaymentInstructionPreparation(toManagerResponseWithShadowRoute(link), false);
                }
                if (canRetireStaleLink(link)) {
                    retireStaleReusableLink(link);
                    contractorPaymentLiveRoutingService.releaseClosedPaymentLink(link);
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа уже есть платеж в процессе по ранее зафиксированным реквизитам. " + "Проверьте платеж в журнале перед созданием нового счета.");
                }
            } else {
                // A started payment is already bound to its frozen provider/profile.
                // Do not make continued access depend on the manager still being active.
                if (canReuseStartedBankLink(link, amountKopecks)) {
                    return new PaymentInstructionPreparation(toManagerResponseWithShadowRoute(link), false);
                }
                if (canReuseFrozenPaymentRoute(link, amountKopecks)) {
                    return new PaymentInstructionPreparation(toManagerResponseWithShadowRoute(link), false);
                }
                // From here on we may replace or reconfigure the route. Lock and
                // validate the manager only after Order and PaymentLinks are locked.
                manager = paymentProfileService.lockManagerForRouting(manager);
                profile = paymentProfileService.lockForRouting(paymentProfileService.selectForManager(manager));
                PaymentLink candidate = preparedCandidate(order, manager, profile, amountKopecks, now, link.getId());
                if (canReuseLink(link, candidate)) {
                    return new PaymentInstructionPreparation(toManagerResponseWithShadowRoute(link), false);
                }
                if (canRetireStaleLink(link)) {
                    retireStaleReusableLink(link);
                    if (link.getContractorAllocationId() != null) {
                        contractorPaymentLiveRoutingService.releaseClosedPaymentLink(link);
                    }
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа уже есть платеж в процессе по старым реквизитам или сумме. Проверьте платеж в журнале перед созданием нового счета.");
                }
            }
        }
        if (lockedOrderLinks.stream().anyMatch(this::blocksCreationOfAnotherBankPayment)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа уже есть созданный банковский платеж. Проверьте его статус перед новым счетом.");
        }
        if (profile == null) {
            manager = paymentProfileService.lockManagerForRouting(manager);
            profile = paymentProfileService.lockForRouting(paymentProfileService.selectForManager(manager));
        }
        PaymentLink link = paymentLinkRepository.save(newPaymentLink(order, amountKopecks, now));
        if (configuredMode == InvoicePaymentMode.OWNER_TBANK) {
            requireLiveRouteChangeEnabled();
            prepareLivePaymentLinkSourceOrFail(link, now);
            ContractorPaymentAllocation allocation = requireLiveRoutingAllocation(link, contractorPaymentLiveRoutingService.reserveOwnerForPaymentLink(link), "explicit_owner_tbank_returned_null");
            applyPaymentProfile(link, profile);
            applyBankPaymentRoute(link);
            link.setContractorAllocationId(allocation.getId());
            link = paymentLinkRepository.save(link);
            return new PaymentInstructionPreparation(toManagerResponseWithShadowRoute(link), true);
        }
        boolean typedActualRecipientEnabled = actualPaymentAttributionService.actualRecipientAccountingEnabled();
        Optional<ManualPaymentTaskRouteSnapshot> taskRoute = typedActualRecipientEnabled ? taskReceiptIntegrationService.reserveForPaymentLink(link, manager.getId(), profile.getId()) : Optional.empty();
        if (taskRoute.isPresent()) {
            applyManualTaskPayment(link, taskRoute.get());
            link = paymentLinkRepository.save(link);
        } else if (configuredMode == InvoicePaymentMode.EMPLOYEE_REQUISITES) {
            requireLiveRouteChangeEnabled();
            prepareLivePaymentLinkSourceOrFail(link, now);
            ContractorPaymentAllocation allocation = requireLiveRoutingAllocation(link, contractorPaymentLiveRoutingService.reserveContractorForPaymentLink(link), "explicit_employee_requisites_returned_null");
            if (!isContractorRecipient(allocation)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось выбрать специалиста или менеджера для оплаты");
            }
            applyContractorPaymentRoute(link, allocation);
            link = paymentLinkRepository.save(link);
        } else {
            boolean liveRoutingEnabled = contractorPaymentLiveRoutingService.enabledForNewRoutes();
            if (liveRoutingEnabled) {
                try {
                    prepareLivePaymentLinkSourceOrFail(link, now);
                    ContractorPaymentAllocation liveAllocation = requireLiveRoutingAllocation(link, contractorPaymentLiveRoutingService.reserveForPaymentLink(link), "reserve_returned_null");
                    if (isContractorRecipient(liveAllocation)) {
                        applyContractorPaymentRoute(link, liveAllocation);
                    } else {
                        applyPaymentProfile(link, profile);
                        applyBankPaymentRoute(link);
                        link.setContractorAllocationId(liveAllocation.getId());
                    }
                    link = paymentLinkRepository.save(link);
                } catch (RuntimeException exception) {
                    if (!isKnownLiveRoutingFailClosed(exception)) {
                        notifyLiveRoutingFailClosedIssue(link, "live_routing_exception", readableRoutingException(exception));
                    }
                    throw exception;
                }
            } else if (contractorPaymentLiveRoutingService.configuredButBlockedForNewRoutes()) {
                failClosedLiveRoutingWithoutAllocation(link, "configured_but_blocked");
            } else {
                applyPaymentProfile(link, profile);
                routePaymentWithoutTask(link, profile, amountKopecks, now, null);
                link = paymentLinkRepository.save(link);
            }
        }
        return new PaymentInstructionPreparation(toManagerResponseWithShadowRoute(link), true);
    }

    private void synchronizeClosedContractorRoutes(List<PaymentLink> links) {
        (links == null ? List.<PaymentLink>of() : links).stream().filter(Objects::nonNull).filter(link -> link.getContractorAllocationId() != null).forEach(contractorPaymentLiveRoutingService::releaseClosedPaymentLink);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    ManagerPaymentLinkResponse toManagerResponseWithShadowRoute(PaymentLink link) {
        Long linkId = link == null ? null : link.getId();
        String routeGeneration = link == null ? null : link.getShadowRouteGeneration();
        if (shouldReserveShadowRoute(link) && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    reserveContractorShadowRouteSafely(linkId, routeGeneration);
                }
            });
        } else if (shouldReserveShadowRoute(link)) {
            reserveContractorShadowRouteSafely(linkId, routeGeneration);
        }
        return toManagerResponse(link);
    }

    private boolean shouldReserveShadowRoute(PaymentLink link) {
        return link != null && link.getId() != null && link.getContractorAllocationId() == null && !isPaperInvoice(link) && link.getManualSource() != ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE && link.getManualSource() != ManualPaymentSource.MANUAL_TASK;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    private PaymentInstructionPreparation prepareOwnerPaperInvoice(Order order, List<PaymentLink> lockedOrderLinks, long amountKopecks, LocalDateTime now) {
        PaymentLink current = currentRouteChangeLink(lockedOrderLinks).orElse(null);
        if (current != null) {
            if (isPaperInvoice(current) && current.getAmountKopecks() == amountKopecks && current.getExpiresAt() != null && current.getExpiresAt().isAfter(now)) {
                return new PaymentInstructionPreparation(toManagerResponseWithShadowRoute(current), false);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа уже есть другой активный способ оплаты. Смените его через карточку заказа после сверки");
        }
        if (lockedOrderLinks.stream().anyMatch(this::blocksCreationOfAnotherBankPayment)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа уже есть платеж, требующий сверки. Бумажный счёт пока создать нельзя");
        }
        paymentProfileService.lockManagerForRouting(orderManager(order));
        PaymentLink link = newPaymentLink(order, amountKopecks, now);
        applyOwnerPaperInvoiceRoute(link);
        link = paymentLinkRepository.save(link);
        return new PaymentInstructionPreparation(toManagerResponseWithShadowRoute(link), true);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void applyOwnerPaperInvoiceRoute(PaymentLink link) {
        link.setPaymentProfile(null);
        link.setPaymentProfileCode("OWNER_PAPER_INVOICE");
        link.setPaymentProfileName("Бумажный счёт владельца");
        link.setPaymentMethod(PaymentMethod.OWNER_PAPER_INVOICE);
        link.setManualPaymentType(null);
        link.setManualSource(ManualPaymentSource.OWNER_PAPER_INVOICE);
        link.setManualPaymentTask(null);
        link.setManualPhone(null);
        link.setManualRecipientName(null);
        link.setManualBankName(null);
        link.setManualPaymentUrl(null);
        link.setManualPaymentButtonLabel(null);
        link.setManualComment(null);
        link.setContractorAllocationId(null);
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setPaperInvoiceIssuedAt(null);
        link.setLastError(null);
    }

    InvoicePaymentMode invoicePaymentMode(Order order) {
        return order == null || order.getInvoicePaymentMode() == null ? InvoicePaymentMode.AUTO_ROUTING : order.getInvoicePaymentMode();
    }

    private boolean isExplicitElectronicPaymentMode(InvoicePaymentMode mode) {
        return mode == InvoicePaymentMode.EMPLOYEE_REQUISITES || mode == InvoicePaymentMode.OWNER_TBANK;
    }

    private boolean canReuseExplicitPaymentRoute(PaymentLink link, InvoicePaymentMode mode, long currentAmountKopecks) {
        if (link == null || link.getAmountKopecks() != currentAmountKopecks || (link.getReservedAmountKopecks() != null && link.getReservedAmountKopecks() != currentAmountKopecks)) {
            return false;
        }
        if (mode == InvoicePaymentMode.OWNER_TBANK) {
            return link.getPaymentMethod() == PaymentMethod.BANK_FORM || link.getPaymentMethod() == PaymentMethod.SBP_QR;
        }
        return mode == InvoicePaymentMode.EMPLOYEE_REQUISITES && (link.getManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE || link.getManualSource() == ManualPaymentSource.MANUAL_TASK);
    }

    private boolean isPaperInvoice(PaymentLink link) {
        return paymentPresenter.isPaperInvoice(link);
    }

    private void reserveContractorShadowRouteSafely(Long linkId, String routeGeneration) {
        try {
            contractorPaymentShadowService.reserveForPaymentLinkId(linkId, routeGeneration);
        } catch (RuntimeException e) {
            log.error("Не удалось записать тестовый маршрут платежной ссылки: linkId={}, code={}", linkId, e.getClass().getSimpleName());
        }
    }

    void ensureOrderNotCoveredByActiveCommonInvoice(Long orderId) {
        CommonInvoicePaymentOperations commonBillingService = commonInvoicePayments;
        final boolean covered;
        try {
            covered = commonBillingService.isOrderInActiveCommonInvoice(orderId);
        } catch (RuntimeException e) {
            log.warn("Не удалось проверить общий счет заказа {} перед созданием отдельной ссылки", orderId, e);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось безопасно проверить общий счет заказа. Отдельная платежная ссылка не создана.", e);
        }
        if (covered) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ уже включен в активный общий счет. Используйте единую ссылку общего счета;" + " отдельная ссылка для этого заказа заблокирована.");
        }
    }

    void requirePaymentLinksEnabled() {
        if (!runtimeSettingsService.isPaymentLinksEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежные ссылки выключены в настройках");
        }
    }

    private PaymentLink preparedCandidate(Order order, Manager manager, PaymentProfile profile, long amountKopecks, LocalDateTime now, Long excludedLinkId) {
        PaymentLink link = newPaymentLink(order, amountKopecks, now);
        applyPaymentProfile(link, profile);
        routePayment(link, manager, profile, amountKopecks, now, excludedLinkId);
        return link;
    }

    PaymentLink newPaymentLink(Order order, long amountKopecks, LocalDateTime now) {
        PaymentLink link = new PaymentLink();
        link.setToken(newToken());
        link.setOrder(order);
        link.setAmountKopecks(amountKopecks);
        link.setReservedAmountKopecks(amountKopecks);
        link.setDescription(description(order));
        String defaultEmail = defaultPayerEmail(order);
        if (!defaultEmail.isBlank()) {
            link.setPayerEmail(defaultEmail);
        }
        link.setExpiresAt(now.plus(properties.getLinkTtl()));
        contractorPaymentShadowService.preparePaymentLinkSource(link, now);
        return link;
    }

    private void routePayment(PaymentLink link, Manager manager, PaymentProfile profile, long amountKopecks, LocalDateTime now, Long excludedLinkId) {
        routePaymentWithoutTask(link, profile, amountKopecks, now, excludedLinkId);
    }

    private void routePaymentWithoutTask(PaymentLink link, PaymentProfile profile, long amountKopecks, LocalDateTime now, Long excludedLinkId) {
        if (allowLegacyManualProfileRoute() && shouldUseManualPayment(profile, amountKopecks, now, excludedLinkId)) {
            applyManualProfilePayment(link, profile);
        } else {
            applyBankPaymentRoute(link);
        }
    }

    private boolean allowLegacyManualProfileRoute() {
        return commonInvoiceRouteSelector.allowLegacyManualProfileRoute();
    }

    private void applyBankPaymentRoute(PaymentLink link) {
        commonInvoiceRouteSelector.applyBankPaymentRoute(link);
    }

    private boolean canReuseLink(PaymentLink current, PaymentLink candidate) {
        return current.getAmountKopecks() == candidate.getAmountKopecks() && current.getReservedAmountKopecks() == candidate.getReservedAmountKopecks() && current.getPaymentMethod() == candidate.getPaymentMethod() && sameId(current.getPaymentProfile(), candidate.getPaymentProfile()) && current.getManualSource() == candidate.getManualSource() && sameId(current.getManualPaymentTask(), candidate.getManualPaymentTask()) && current.getManualPaymentType() == candidate.getManualPaymentType() && normalize(current.getManualPhone()).equals(normalize(candidate.getManualPhone())) && normalize(current.getManualRecipientName()).equals(normalize(candidate.getManualRecipientName())) && normalize(current.getManualPaymentUrl()).equals(normalize(candidate.getManualPaymentUrl())) && normalize(current.getManualPaymentButtonLabel()).equals(normalize(candidate.getManualPaymentButtonLabel())) && normalize(current.getManualComment()).equals(normalize(candidate.getManualComment()));
    }

    private boolean canReuseFrozenPaymentRoute(PaymentLink link, long currentAmountKopecks) {
        if (link == null || link.getAmountKopecks() != currentAmountKopecks) {
            return false;
        }
        Long reserved = link.getReservedAmountKopecks();
        if (reserved != null && reserved != currentAmountKopecks) {
            return false;
        }
        if (link.getPaymentMethod() == PaymentMethod.BANK_FORM || link.getPaymentMethod() == PaymentMethod.SBP_QR) {
            return (link.getPaymentProfile() != null && link.getPaymentProfile().getId() != null) || !normalize(link.getPaymentProfileCode()).isBlank() || !normalize(link.getTbankTerminalKey()).isBlank();
        }
        if (link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK || link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK) {
            return (link.getManualPaymentTask() != null && link.getManualPaymentTask().getId() != null) || (link.getPaymentProfile() != null && link.getPaymentProfile().getId() != null) || link.getContractorAllocationId() != null;
        }
        return false;
    }

    private boolean isFrozenContractorRoute(PaymentLink link) {
        return commonInvoiceRouteSelector.isFrozenContractorRoute(link);
    }

    private boolean canReuseContractorLink(PaymentLink link, long currentAmountKopecks) {
        if (!isFrozenContractorRoute(link) || link.getAmountKopecks() != currentAmountKopecks) {
            return false;
        }
        Long reserved = link.getReservedAmountKopecks();
        return reserved == null || reserved == currentAmountKopecks;
    }

    boolean isContractorRecipient(ContractorPaymentAllocation allocation) {
        return allocation != null && (allocation.getRecipientType() == ContractorRecipientType.SPECIALIST || allocation.getRecipientType() == ContractorRecipientType.MANAGER);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void prepareLivePaymentLinkSourceOrFail(PaymentLink link, LocalDateTime now) {
        contractorPaymentShadowService.prepareLivePaymentLinkSource(link, now);
        if (!paymentLinkSourceSnapshotReady(link)) {
            failClosedLiveRoutingWithoutAllocation(link, "source_snapshot_not_ready");
        }
    }

    private boolean paymentLinkSourceSnapshotReady(PaymentLink link) {
        Order order = link == null ? null : link.getOrder();
        return link != null && order != null && link.getShadowRouteGeneration() != null && !link.getShadowRouteGeneration().isBlank() && link.getShadowRoutePreparedAt() != null && Objects.equals(link.getShadowRouteOrderId(), order.getId()) && Objects.equals(link.getShadowRouteAmountKopecks(), link.getAmountKopecks());
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    ContractorPaymentAllocation requireLiveRoutingAllocation(PaymentLink link, ContractorPaymentAllocation allocation, String reason) {
        if (allocation == null) {
            failClosedLiveRoutingWithoutAllocation(link, reason);
        }
        if (allocation.getId() == null) {
            failClosedLiveRoutingWithoutAllocation(link, "allocation_without_id");
        }
        return allocation;
    }

    private void failClosedLiveRoutingWithoutAllocation(PaymentLink link, String reason) {
        String cleanReason = normalize(reason).isBlank() ? "unknown" : normalize(reason);
        String runtimeStatus = liveRoutingRuntimeStatusSummary();
        String error = LIVE_ROUTING_MISSING_ALLOCATION_PREFIX + cleanReason + "; " + runtimeStatus;
        if (link != null) {
            link.setStatus(PaymentLinkStatus.FAILED);
            link.setLastError(limit(error, 512));
        }
        Long orderId = link == null || link.getOrder() == null ? null : link.getOrder().getId();
        Long linkId = link == null ? null : link.getId();
        Long amount = link == null ? null : link.getAmountKopecks();
        notifyLiveRoutingFailClosedIssue(link, cleanReason, error);
        log.error("LIVE contractor routing failed closed: reason={}, runtime={}, orderId={}, linkId={}, amountKopecks={}, shadowGeneration={}", cleanReason, runtimeStatus, orderId, linkId, amount, link == null ? null : link.getShadowRouteGeneration());
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Новая система оплат включена, но маршрут получателя не зафиксирован. " + "Счет не отправлен; проверьте LIVE-routing: " + cleanReason);
    }

    private boolean isKnownLiveRoutingFailClosed(RuntimeException exception) {
        if (!(exception instanceof ResponseStatusException responseStatusException)) {
            return false;
        }
        String reason = normalize(responseStatusException.getReason()).toLowerCase(Locale.ROOT);
        return reason.contains("live-routing") || reason.contains("маршрут получателя не зафиксирован");
    }

    private void notifyLiveRoutingFailClosedIssue(PaymentLink link, String reason, String details) {
        Order order = link == null ? null : link.getOrder();
        Long orderId = order == null ? null : order.getId();
        if (orderId == null || orderId <= 0) {
            return;
        }
        String cleanReason = normalize(reason);
        if (cleanReason.isBlank()) {
            cleanReason = "unknown";
        }
        String cleanDetails = normalize(details);
        String amount = link == null ? "не указана" : amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString() + " ₽";
        paymentIssueReminderService.notifyOrderIssue(orderId, LIVE_ROUTING_FAIL_CLOSED_REMINDER_SOURCE, orderId, "Платёж требует внимания: заказ №" + orderId, limit("Система остановила выставление счёта: не удалось безопасно зафиксировать LIVE-маршрут оплаты." + "\nЗаказ: №" + orderId + "\nСумма: " + amount + "\nПричина: " + cleanReason + (cleanDetails.isBlank() ? "" : "\nДетали: " + cleanDetails) + "\nКлиенту сомнительный счёт не отправлен. Проверьте реквизиты, допуск и лимиты специалиста/менеджера.", 1000));
    }

    private String readableRoutingException(RuntimeException exception) {
        if (exception instanceof ResponseStatusException responseStatusException) {
            String reason = normalize(responseStatusException.getReason());
            if (!reason.isBlank()) {
                return reason;
            }
        }
        String message = normalize(exception == null ? null : exception.getMessage());
        if (!message.isBlank()) {
            return message;
        }
        return exception == null ? "unknown" : exception.getClass().getSimpleName();
    }

    private String liveRoutingRuntimeStatusSummary() {
        try {
            ContractorPaymentRuntimeSwitch.RuntimeStatus status = contractorPaymentRuntimeSwitch.status();
            if (status == null) {
                return "runtime_status=unavailable";
            }
            return "routingMaster=" + status.liveRoutingMasterEnabled() + ",routingDb=" + status.liveRoutingDatabaseEnabled() + ",routingEnabled=" + status.liveRoutingEnabled() + ",rewardMaster=" + status.rewardAttributionMasterEnabled() + ",rewardDb=" + status.rewardAttributionDatabaseEnabled() + ",rewardLive=" + status.rewardAttributionLiveEnabled() + ",blockers=" + contractorPaymentRuntimeSwitch.liveRoutingBlockers();
        } catch (RuntimeException exception) {
            return "runtime_status_error=" + exception.getClass().getSimpleName();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void applyContractorPaymentRoute(PaymentLink link, ContractorPaymentAllocation allocation) {
        String recipient = normalize(allocation.getRecipientNameSnapshot());
        String phone = normalize(allocation.getPaymentPhoneSnapshot());
        if (recipient.isBlank() || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В зафиксированном платежном профиле отсутствуют обязательные реквизиты");
        }
        link.setContractorAllocationId(allocation.getId());
        link.setPaymentProfile(null);
        link.setPaymentProfileCode("CONTRACTOR");
        link.setPaymentProfileName(allocation.getRecipientType() == ContractorRecipientType.SPECIALIST ? "Платёжный профиль специалиста" : "Платёжный профиль менеджера");
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        link.setManualPaymentTask(null);
        // Contractor PII has one encrypted source of truth: the allocation
        // snapshot. Legacy payment-link columns must stay empty.
        link.setManualPhone(null);
        link.setManualRecipientName(null);
        link.setManualBankName(limit(allocation.getBankNameSnapshot(), 120));
        link.setManualPaymentUrl(null);
        link.setManualPaymentButtonLabel(null);
        // The custom comment may contain personal data. It is rendered only
        // from the encrypted allocation snapshot and never copied to this
        // legacy plaintext column.
        link.setManualComment(null);
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
    }

    private boolean canReuseStartedBankLink(PaymentLink link, long currentAmountKopecks) {
        if (link == null || (link.getPaymentMethod() != PaymentMethod.BANK_FORM && link.getPaymentMethod() != PaymentMethod.SBP_QR) || normalize(link.getTbankPaymentId()).isBlank() || hasBankCancelReservation(link) || link.getBankCancelOriginStatus() != null || link.getAmountKopecks() != currentAmountKopecks) {
            return false;
        }
        Long reservedAmountKopecks = link.getReservedAmountKopecks();
        return reservedAmountKopecks == null || reservedAmountKopecks == currentAmountKopecks;
    }

    private boolean canRetireStaleLink(PaymentLink link) {
        return lifecycleService.canRetireStaleLink(link);
    }

    private boolean sameId(PaymentProfile left, PaymentProfile right) {
        Long leftId = left == null ? null : left.getId();
        Long rightId = right == null ? null : right.getId();
        return leftId == null ? rightId == null : leftId.equals(rightId);
    }

    private boolean sameId(ManualPaymentTask left, ManualPaymentTask right) {
        Long leftId = left == null ? null : left.getId();
        Long rightId = right == null ? null : right.getId();
        return leftId == null ? rightId == null : leftId.equals(rightId);
    }

    private void retireStaleReusableLink(PaymentLink link) {
        taskReceiptIntegrationService.release(link, "Платежная ссылка пересоздана из-за изменения суммы или маршрута оплаты");
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setLastError("Платежная ссылка пересоздана из-за изменения суммы или маршрута оплаты");
        paymentLinkRepository.save(link);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void expireStaleManualLinks(LocalDateTime now) {
        String reason = "Срок действия ручной платежной ссылки истек";
        paymentLinkRepository.findExpiredManualLinksForUpdate(MANUAL_PAYMENT_METHODS, Set.of(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, PaymentLinkStatus.MANUAL_REPORTED), now).forEach(link -> {
            taskReceiptIntegrationService.release(link, reason);
            link.setStatus(PaymentLinkStatus.EXPIRED);
            link.setLastError(reason);
            paymentLinkRepository.save(link);
        });
    }

    private boolean hasBankCancelReservation(PaymentLink link) {
        return paymentPresenter.hasBankCancelReservation(link);
    }

    private void recoverOrderBankInitReservationsBeforeCreation(List<PaymentLink> orderLinks, LocalDateTime now) {
        bankInitializationState.recoverOrderBankInitReservationsBeforeCreation(orderLinks, now);
    }

    private boolean blocksCreationOfAnotherBankPayment(PaymentLink link) {
        return bankInitializationState.blocksCreationOfAnotherBankPayment(link);
    }

    private boolean hasCompetingBlockingPayment(PaymentLink current, List<PaymentLink> orderLinks) {
        return bankInitializationState.hasCompetingBlockingPayment(current, orderLinks);
    }

    private void ensureNoPendingVerifiedManualRouteTransition(List<PaymentLink> links) {
        boolean pendingCommonInvoiceTransition = links != null && links.stream().anyMatch(link -> link != null && link.getStatus() == PaymentLinkStatus.CANCELED && isManualPayment(link) && normalize(link.getLastError()).startsWith(MANUAL_UNPAID_CLOSED_AUDIT_PREFIX + ":"));
        if (pendingCommonInvoiceTransition) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная инструкция закрыта после проверки отсутствия перевода. " + "Сначала завершите перенос заказа в общий счет; " + "до этого новый отдельный способ оплаты заблокирован.");
        }
    }

    private ManagerPaymentLinkResponse toManagerResponse(PaymentLink link) {
        return paymentPresenter.toManagerResponse(link);
    }

    private Manager orderManager(Order order) {
        return bankObservations.orderManager(order);
    }

    private boolean shouldUseManualPayment(PaymentProfile profile, long amountKopecks, LocalDateTime now, Long excludedLinkId) {
        return commonInvoiceRouteSelector.shouldUseManualPayment(profile, amountKopecks, now, excludedLinkId);
    }

    private String manualTaskTransferNumber(String value) {
        return commonInvoiceRouteSelector.manualTaskTransferNumber(value);
    }

    private String manualRecipientName(String value) {
        return commonInvoiceRouteSelector.manualRecipientName(value);
    }

    private String manualRecipientName(PaymentLink link) {
        return commonInvoiceRouteSelector.manualRecipientName(link);
    }

    private void applyManualProfilePayment(PaymentLink link, PaymentProfile profile) {
        commonInvoiceRouteSelector.applyManualProfilePayment(link, profile);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void applyManualTaskPayment(PaymentLink link, ManualPaymentTask task) {
        String transferNumber = manualTaskTransferNumber(task.getManualPhone());
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.MANUAL_TASK);
        link.setManualPaymentTask(task);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setManualPhone(transferNumber);
        link.setManualRecipientName(manualRecipientName(task.getManualRecipientName()));
        link.setManualBankName(limit(task.getManualBankName(), 120));
        link.setManualPaymentUrl(null);
        link.setManualPaymentButtonLabel(null);
        link.setManualComment(manualComment(task.getComment(), link));
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void applyManualTaskPayment(PaymentLink link, ManualPaymentTaskRouteSnapshot snapshot) {
        ManualPaymentTask task = manualPaymentTaskRepository.findByIdForUpdate(snapshot.taskId()).orElseThrow(ManualPaymentTaskRouteErrors::stale);
        applyManualTaskPayment(link, task);
        link.setManualTaskSourceGeneration(snapshot.source().sourceGeneration());
        link.setManualTaskGeneration(snapshot.taskGeneration());
        link.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPhone(manualTaskTransferNumber(snapshot.manualPhone()));
        link.setManualRecipientName(manualRecipientName(snapshot.bankRecipientName()));
        link.setManualBankName(limit(snapshot.bankName(), 120));
        link.setManualPaymentUrl(null);
        link.setManualPaymentButtonLabel(null);
    }

    private void applyPaymentProfile(PaymentLink link, PaymentProfile profile) {
        commonInvoiceRouteSelector.applyPaymentProfile(link, profile);
    }

    private BigDecimal payableSum(Order order) {
        return amountPolicy.payableSum(order);
    }

    private long amountKopecks(BigDecimal amount) {
        return amountPolicy.amountKopecks(amount);
    }

    private BigDecimal amountRubles(long amountKopecks) {
        return paymentPresenter.amountRubles(amountKopecks);
    }

    String description(Order order) {
        return PAYMENT_SERVICE_NAME;
    }

    public record PaymentInstructionPreparation(ManagerPaymentLinkResponse response, boolean createdFresh) {
    }

    private String manualComment(PaymentLink link) {
        return commonInvoiceRouteSelector.manualComment(link);
    }

    private String manualComment(String template, PaymentLink link) {
        return commonInvoiceRouteSelector.manualComment(template, link);
    }

    private String defaultPayerEmail(Order order) {
        Company company = order == null ? null : order.getCompany();
        return company == null ? "" : normalizeEmail(company.getLastPayerEmail());
    }

    private boolean isManualPayment(PaymentLink link) {
        return paymentPresenter.isManualPayment(link);
    }

    String newToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeEmail(String email) {
        return normalize(email).toLowerCase();
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }
}
