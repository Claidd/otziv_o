package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceManualPaymentWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceTochkaReconciliationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDeliveryService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDetailsAssembler.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceInitializationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentRouteChangeContextResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentRouteChangeRequest;
import com.hunt.otziv.common_billing.dto.InvoicePaymentModeChangeRequest;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService.FrozenCommonRouteAction;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.dto.PaymentRouteSelection;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentRouteChangeTarget;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.u_users.model.Manager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

/** Changes a frozen common-invoice route with separate prepare, bank observation and finish phases. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoicePaymentRouteWorkflow {

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceDeliveryService invoiceDelivery;

    private final CommonInvoiceDetailsAssembler invoiceDetailsAssembler;

    private final com.hunt.otziv.payments.service.CommonInvoiceRouteSelector invoiceRouteSelector;

    private final CommonInvoiceInitializationService invoiceInitialization;

    private final CommonInvoiceCancellationService invoiceCancellation;

    private final CommonInvoicePresenter invoicePresenter;

    private final CommonInvoiceSettlementService settlementService;

    private final CommonBillingAccountRepository accountRepository;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final CommonInvoicePaymentRefRepository paymentRefRepository;

    private final PaperInvoiceManagerNotificationService paperInvoiceManagerNotificationService;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final PaymentProfileService paymentProfileService;

    private final TbankClient tbankClient;

    public CommonInvoiceDetailsResponse sendInvoice(Long invoiceId, boolean manual) {
        return invoiceDelivery.sendInvoice(invoiceId, manual);
    }

    CommonInvoiceDetailsResponse sendInvoice(Long invoiceId, boolean manual, boolean paymentRouteChanged) {
        return invoiceDelivery.sendInvoice(invoiceId, manual, paymentRouteChanged);
    }

    CommonInvoiceDetailsResponse invoiceAfterOrderPrelude(Long invoiceId) {
        return manualPaymentWorkflow.invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommonInvoiceDetailsResponse changeInvoicePaymentMode(Long invoiceId, InvoicePaymentModeChangeRequest request, Principal principal) {
        InvoicePaymentMode requestedMode = request == null ? null : request.mode();
        if (requestedMode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите способ оплаты общего счёта");
        }
        if (requestedMode != InvoicePaymentMode.AUTO_ROUTING && requestedMode != InvoicePaymentMode.OWNER_PAPER_INVOICE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для общего счёта доступны только автоматическое распределение и бумажный счёт владельца");
        }
        if (request == null || !request.confirmedUnpaid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвердите, что клиент не оплатил по ранее отправленному способу");
        }
        String actor = principal == null ? "system" : normalize(principal.getName());
        PreparedInvoicePaymentModeChange prepared = writeTransaction(() -> prepareInvoicePaymentModeChange(invoiceId, requestedMode, actor));
        if (prepared.providerPaymentRefId() == null) {
            return writeTransaction(() -> invoiceAfterOrderPrelude(invoiceId));
        }
        PaperInvoiceBankResolution resolution = resolvePaperInvoiceBankSession(prepared);
        PaperInvoiceModeSwitchResult result = writeTransaction(() -> finishInvoicePaymentModeChange(prepared, resolution));
        if (!result.switched()) {
            throw new ResponseStatusException(result.failureStatus(), result.failureMessage());
        }
        return writeTransaction(() -> invoiceAfterOrderPrelude(invoiceId));
    }

    @Transactional(readOnly = true)
    public CommonInvoicePaymentRouteChangeContextResponse commonInvoicePaymentRouteChangeContext(Long invoiceId) {
        CommonInvoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счёт не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoicePaymentRef> paymentRefs = paymentRefRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId);
        List<CommonInvoiceOrder> routeItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        String blockReason = commonInvoiceRouteChangePreviewBlockReason(invoice, paymentRefs);
        PaymentProfile ownerBankTargetProfile = null;
        String ownerBankReissueBlockReason = null;
        if (!isTbankCommonRoute(invoice)) {
            ownerBankReissueBlockReason = "Текущий способ оплаты не является банковской ссылкой владельца";
        } else if (blockReason != null) {
            ownerBankReissueBlockReason = blockReason;
        } else if (ownerBankReissueEvidenceBlockReason(invoice, paymentRefs) != null) {
            ownerBankReissueBlockReason = ownerBankReissueEvidenceBlockReason(invoice, paymentRefs);
        } else {
            try {
                ownerBankTargetProfile = paymentProfileService.selectForManager(manager(invoice));
                if (ownerBankTargetProfile == null || ownerBankTargetProfile.getId() == null) {
                    ownerBankReissueBlockReason = "Для менеджера не выбран платежный профиль";
                } else if (Objects.equals(invoice.getPaymentRouteProfileId(), ownerBankTargetProfile.getId())) {
                    ownerBankReissueBlockReason = "В счете уже используется актуальный платежный профиль менеджера";
                }
            } catch (RuntimeException failure) {
                ownerBankReissueBlockReason = "Актуальный платежный профиль менеджера недоступен: " + readableException(failure);
            }
        }
        return new CommonInvoicePaymentRouteChangeContextResponse(commonInvoiceRouteLabel(invoice), isTbankCommonRoute(invoice) ? CommonInvoicePaymentRouteChangeTarget.OWNER_TBANK : CommonInvoicePaymentRouteChangeTarget.EMPLOYEE_REQUISITES, commonInvoiceRouteRecipient(invoice, routeItems), invoice.getStatus() == null ? "UNKNOWN" : invoice.getStatus().name(), blockReason == null, blockReason == null ? "" : blockReason, commonInvoiceRouteChangeToken(invoice, paymentRefs), invoice.getPaymentRouteProfileId(), ownerBankTargetProfile == null ? null : ownerBankTargetProfile.getId(), normalize(ownerBankTargetProfile == null ? null : ownerBankTargetProfile.getName()), normalize(ownerBankTargetProfile == null ? null : ownerBankTargetProfile.getProvider()), ownerBankReissueBlockReason == null, ownerBankReissueBlockReason == null ? "" : ownerBankReissueBlockReason);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommonInvoiceDetailsResponse changeCommonInvoicePaymentRoute(Long invoiceId, CommonInvoicePaymentRouteChangeRequest request, Principal principal) {
        if (request == null || request.target() == null || (request.target() != CommonInvoicePaymentRouteChangeTarget.EMPLOYEE_REQUISITES && request.target() != CommonInvoicePaymentRouteChangeTarget.OWNER_TBANK && request.target() != CommonInvoicePaymentRouteChangeTarget.OWNER_BANK_REISSUE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для общего счёта можно выбрать реквизиты сотрудника, банковскую ссылку владельца " + "или переиздать ее по актуальному профилю");
        }
        if (!request.confirmedUnpaid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвердите, что клиент ещё не оплатил по прежнему способу");
        }
        String actor = principal == null ? "system" : normalize(principal.getName());
        boolean changed = writeTransaction(() -> replaceCommonInvoicePaymentRouteLocked(invoiceId, request, actor));
        return changed ? sendInvoice(invoiceId, true, true) : writeTransaction(() -> invoiceAfterOrderPrelude(invoiceId));
    }

    boolean replaceCommonInvoicePaymentRouteLocked(Long invoiceId, CommonInvoicePaymentRouteChangeRequest request, String actor) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счёт не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoicePaymentRef> paymentRefs = paymentRefRepository.findByInvoiceIdForUpdate(invoiceId);
        String expectedToken = normalize(request.expectedPaymentEvidenceToken());
        String currentToken = commonInvoiceRouteChangeToken(invoice, paymentRefs);
        if (expectedToken.isBlank() || !MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8), currentToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Способ оплаты общего счёта уже изменился. Обновите карточку и повторите");
        }
        String blockReason = commonInvoiceRouteChangeBlockReason(invoice, paymentRefs);
        if (blockReason != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, blockReason);
        }
        boolean currentlyOwnerTbank = isTbankCommonRoute(invoice);
        boolean ownerBankReissue = request.target() == CommonInvoicePaymentRouteChangeTarget.OWNER_BANK_REISSUE;
        if (ownerBankReissue && !currentlyOwnerTbank) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Переиздать по актуальному банковскому профилю можно только ссылку владельца");
        }
        if ((request.target() == CommonInvoicePaymentRouteChangeTarget.OWNER_TBANK && currentlyOwnerTbank) || (request.target() == CommonInvoicePaymentRouteChangeTarget.EMPLOYEE_REQUISITES && !currentlyOwnerTbank)) {
            return false;
        }
        if (ownerBankReissue) {
            String evidenceBlockReason = ownerBankReissueEvidenceBlockReason(invoice, paymentRefs);
            if (evidenceBlockReason != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, evidenceBlockReason);
            }
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        long remaining = remainingKopecks(invoice);
        if (remaining <= 0 || !Objects.equals(invoice.getPaymentRouteAmountKopecks(), remaining)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма общего счёта изменилась. Обновите счёт перед сменой способа оплаты");
        }
        List<Order> routeOrders = items.stream().map(CommonInvoiceOrder::getOrder).filter(Objects::nonNull).toList();
        Manager routeManager = paymentProfileService.lockManagerForRouting(managerForNewCommonPaymentAction(invoice, items));
        if (ownerBankReissue) {
            PaymentProfile targetProfile = paymentProfileService.lockForRouting(paymentProfileService.selectForManager(routeManager));
            if (targetProfile == null || targetProfile.getId() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Для менеджера не выбран актуальный платежный профиль");
            }
            if (request.expectedTargetPaymentProfileId() == null || !Objects.equals(request.expectedTargetPaymentProfileId(), targetProfile.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежный профиль менеджера изменился. Обновите карточку счета и подтвердите переиздание заново");
            }
            if (Objects.equals(invoice.getPaymentRouteProfileId(), targetProfile.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "В счете уже используется актуальный платежный профиль менеджера");
            }
        }
        String reason = "Способ оплаты общего счёта изменён по просьбе клиента";
        if (invoice.getPaymentRouteManualSource() == ManualPaymentSource.MANUAL_TASK) {
            taskReceiptIntegrationService.release(invoice, reason);
        } else if (invoice.getContractorAllocationId() != null) {
            contractorPaymentLiveRoutingService.releaseCommonInvoiceRouteForReplacement(invoice, reason, "LIVE_COMMON:ROUTE_REPLACED");
        }
        clearCommonPaymentRouteForModeSwitch(invoice);
        invoice.setStatus(invoice.getPaidKopecks() > 0 ? CommonInvoiceStatus.PARTIALLY_PAID : CommonInvoiceStatus.READY);
        invoice.setSentAt(null);
        invoice.setLastReminderAt(null);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
        PaymentRouteSelection route;
        ContractorPaymentAllocation allocation = null;
        if (request.target() == CommonInvoicePaymentRouteChangeTarget.EMPLOYEE_REQUISITES) {
            Optional<PaymentRouteSelection> taskRoute = invoiceRouteSelector.selectCommonInvoiceTaskRoute(invoice, routeManager, remaining);
            if (taskRoute.isPresent()) {
                route = taskRoute.get();
            } else {
                allocation = requireLiveCommonInvoiceAllocation(invoice, contractorPaymentLiveRoutingService.reserveContractorForCommonInvoice(invoice, routeOrders, routeManager, remaining));
                if (!isContractorRecipient(allocation)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось выбрать специалиста или менеджера для общего счёта");
                }
                route = contractorCommonPaymentRoute(invoice, allocation);
            }
        } else {
            allocation = requireLiveCommonInvoiceAllocation(invoice, contractorPaymentLiveRoutingService.reserveOwnerForCommonInvoice(invoice, routeOrders, routeManager, remaining));
            route = invoiceRouteSelector.selectCommonInvoiceOwnerAcquiringRoute(routeManager, remaining);
        }
        applyCommonPaymentRoute(invoice, route, remaining);
        if (allocation != null) {
            invoice.setContractorAllocationId(allocation.getId());
            if (isContractorRecipient(allocation)) {
                invoice.setPaymentRouteManualPhone(null);
                invoice.setPaymentRouteManualRecipient(null);
                invoice.setPaymentRouteManualBankName(limit(allocation.getBankNameSnapshot(), 120));
                invoice.setPaymentRouteManualComment(null);
                invoice.setPaymentRouteInstructionText(null);
            }
        }
        // Durable outbox-like marker: if the process stops after this transaction commits but
        // before the immediate send starts, the ordinary unsent scheduler can still deliver the
        // route-change warning and must not silently treat the new route as already communicated.
        invoice.setLastError(PAYMENT_ROUTE_CHANGED_MESSAGE_PENDING);
        invoiceRepository.save(invoice);
        log.info("Common invoice payment route replaced: invoiceId={}, target={}, actor={}, allocationId={}", invoiceId, request.target(), actor, invoice.getContractorAllocationId());
        return true;
    }

    String commonInvoiceRouteChangeBlockReason(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        return commonInvoiceRouteChangeBlockReason(invoice, paymentRefs, false);
    }

    String commonInvoiceRouteChangePreviewBlockReason(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        return commonInvoiceRouteChangeBlockReason(invoice, paymentRefs, true);
    }

    String ownerBankReissueEvidenceBlockReason(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        boolean invoiceEvidence = hasCurrentTbankPaymentBinding(invoice) || !normalize(invoice == null ? null : invoice.getPaymentUrl()).isBlank() || PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice == null ? null : invoice.getLastError()));
        boolean registryEvidence = (paymentRefs == null ? List.<CommonInvoicePaymentRef>of() : paymentRefs).stream().anyMatch(ref -> ref != null && (isPreparedPaymentRef(ref) || !providerOrderId(ref).isBlank() || !providerPaymentId(ref).isBlank() || !normalize(ref.getStatus()).isBlank()));
        if (!invoiceEvidence && !registryEvidence) {
            return null;
        }
        return "Банковский профиль нельзя переиздать: по счету уже есть платежные идентификаторы. " + "Сначала завершите сверку текущей попытки";
    }

    String commonInvoiceRouteChangeBlockReason(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs, boolean preview) {
        if (invoice == null || invoice.getId() == null) {
            return "Общий счёт не найден";
        }
        if (hasUnresolvedPaymentMessage(invoice)) {
            return "Отправка прежних реквизитов ещё не подтверждена. Сначала выполните сверку сообщения";
        }
        String managerBlockReason = commonPaymentManagerBlockReason(invoice);
        if (managerBlockReason != null) {
            return managerBlockReason;
        }
        if (invoice.getInvoicePaymentMode() == InvoicePaymentMode.OWNER_PAPER_INVOICE) {
            return "Для счёта включён бумажный режим. Сначала верните автоматическое распределение";
        }
        if (!hasFrozenCommonPaymentRoute(invoice)) {
            return "Способ оплаты общего счёта ещё не сформирован";
        }
        if (!SEND_INVOICE_STATUSES.contains(invoice.getStatus())) {
            return "Общий счёт уже закрыт или требует отдельной сверки";
        }
        if (invoice.getPaidKopecks() > 0 || invoice.getClientReportedAt() != null || invoice.getManualConfirmedAt() != null) {
            return "По общему счёту уже есть признаки оплаты; сначала выполните сверку";
        }
        if (hasCurrentTbankPaymentBinding(invoice) || !normalize(invoice.getPaymentUrl()).isBlank() || PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError()))) {
            return "У счёта есть активная сессия T-Bank. Если клиент уже перевёл деньги по прежним " + "реквизитам, не меняйте способ оплаты: нажмите «Оплачен» и укажите фактического " + "получателя — система сама сверит и отменит ссылку T-Bank";
        }
        boolean unresolvedProviderAttempt = (paymentRefs == null ? List.<CommonInvoicePaymentRef>of() : paymentRefs).stream().filter(Objects::nonNull).map(this::paymentRefStatus).anyMatch(status -> !MANUAL_COMMON_PAYMENT_SAFE_REF_STATUSES.contains(status));
        if (unresolvedProviderAttempt) {
            return "По общему счёту есть незавершённая банковская попытка; нужна сверка T-Bank";
        }
        FrozenCommonRouteAction frozenRouteAction = invoice.getContractorAllocationId() == null ? FrozenCommonRouteAction.KEEP : preview ? contractorPaymentLiveRoutingService.previewFrozenCommonRouteAction(invoice.getId(), invoice.getContractorAllocationId()) : contractorPaymentLiveRoutingService.frozenCommonRouteAction(invoice.getId(), invoice.getContractorAllocationId());
        if (frozenRouteAction != FrozenCommonRouteAction.KEEP) {
            return "Резерв прежнего получателя уже изменился и требует ручной сверки";
        }
        return null;
    }

    private boolean hasUnresolvedPaymentMessage(CommonInvoice invoice) {
        if (invoiceInitialization.hasUnresolvedLegacyMessage(invoice)) {
            return true;
        }
        return (invoice.getPaymentMessageOperationId() != null && !invoice.isPaymentMessageConfirmed())
                || MESSAGE_SEND_IN_PROGRESS.equals(normalize(invoice.getLastError()))
                || PAYMENT_ROUTE_CHANGED_MESSAGE_IN_PROGRESS.equals(normalize(invoice.getLastError()));
    }

    String commonInvoiceRouteLabel(CommonInvoice invoice) {
        return invoicePresenter.commonInvoiceRouteLabel(invoice);
    }

    String commonInvoiceRouteRecipient(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoicePresenter.commonInvoiceRouteRecipient(invoice, items);
    }

    String commonInvoiceRouteChangeToken(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        StringBuilder snapshot = new StringBuilder(512);
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getId());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getStatus());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getInvoicePaymentMode());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getPaymentMessageOperationId());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.isPaymentMessageConfirmed());
        appendEvidenceField(snapshot, manager(invoice) == null ? null : manager(invoice).getId());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getPaidKopecks());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getPaymentRouteType());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getPaymentRouteSelectedAt());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getPaymentRouteAmountKopecks());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getContractorAllocationId());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getPaymentRouteManualTaskId());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getPaymentRouteManualTaskGeneration());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getTbankOrderId());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getTbankPaymentId());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getTbankTerminalKey());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getTbankPaymentAmountKopecks());
        appendEvidenceField(snapshot, invoice == null ? null : invoice.getPaymentUrl());
        for (CommonInvoicePaymentRef ref : paymentRefs == null ? List.<CommonInvoicePaymentRef>of() : paymentRefs) {
            if (ref == null) {
                continue;
            }
            appendEvidenceField(snapshot, ref.getId());
            appendEvidenceField(snapshot, ref.getStatus());
            appendEvidenceField(snapshot, ref.getTbankPaymentId());
            appendEvidenceField(snapshot, normalizedPaymentProvider(ref));
            appendEvidenceField(snapshot, ref.getPaymentProfileId());
            appendEvidenceField(snapshot, providerOrderId(ref));
            appendEvidenceField(snapshot, providerPaymentId(ref));
            appendEvidenceField(snapshot, providerMerchantId(ref));
            appendEvidenceField(snapshot, ref.getProviderPaymentMode());
            appendEvidenceField(snapshot, ref.getProviderTestMode());
            appendEvidenceField(snapshot, ref.getProviderStatus());
            appendEvidenceField(snapshot, ref.getProviderExpiresAt());
            appendEvidenceField(snapshot, ref.getAmountKopecks());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    PreparedInvoicePaymentModeChange prepareInvoicePaymentModeChange(Long invoiceId, InvoicePaymentMode requestedMode, String actor) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счёт не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if (invoice.getStatus() == CommonInvoiceStatus.PAID || invoice.getStatus() == CommonInvoiceStatus.ARCHIVED || invoice.getStatus() == CommonInvoiceStatus.DISABLED || invoice.getStatus() == CommonInvoiceStatus.BAN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Закрытый общий счёт изменить нельзя");
        }
        if (invoice.getInvoicePaymentMode() == requestedMode) {
            return PreparedInvoicePaymentModeChange.completed(invoiceId, requestedMode, actor);
        }
        if (hasUnresolvedPaymentMessage(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Отправка прежних реквизитов ещё не подтверждена. Сначала выполните сверку сообщения");
        }
        if (requestedMode == InvoicePaymentMode.AUTO_ROUTING && invoice.getPaperInvoiceIssuedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Бумажный счёт уже отправлен клиенту. Сначала проверьте его оплату вручную");
        }
        ensurePaperModeSwitchHasNoFinancialEvidence(invoice);
        if (requestedMode == InvoicePaymentMode.OWNER_PAPER_INVOICE && hasCurrentTbankPaymentBinding(invoice)) {
            CommonInvoicePaymentRef paymentRef = currentPaymentRefForPaperModeSwitch(invoice);
            String refStatus = normalize(paymentRef.getStatus()).toUpperCase(Locale.ROOT);
            if (isPaperModeSwitchSafeTerminalStatus(refStatus)) {
                recordCurrentPaymentRef(invoice, refStatus, "paper_invoice_mode_switch_provider_already_closed");
                applyInvoicePaymentModeChange(invoice, requestedMode, actor);
                return PreparedInvoicePaymentModeChange.completed(invoiceId, requestedMode, actor);
            }
            if (isPaperModeSwitchFinancialStatus(refStatus)) {
                String providerLabel = PROVIDER_TOCHKA.equals(normalizedPaymentProvider(paymentRef)) ? "Точка Банк" : "T-Bank";
                throw new ResponseStatusException(HttpStatus.CONFLICT, providerLabel + " уже зафиксировал платёжный статус " + refStatus + ". Бумажный режим не включён; нужна сверка оплаты");
            }
            ensureTbankCancellationSupported(paymentRef, "Бумажный режим не включён");
            if (!canCancelInitializedPaymentRef(paymentRef)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Банковская попытка заполнена не полностью. Бумажный режим не включён; нужна сверка T-Bank");
            }
            if (PAYMENT_REF_CANCELING.equals(refStatus) && !isStaleArchivedPaymentCancel(paymentRef)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Отмена прежней T-Bank-сессии уже выполняется. Повторите после обновления счёта");
            }
            paymentRef.setStatus(PAYMENT_REF_CANCELING);
            paymentRef.setCancelAttempts(cancelAttempts(paymentRef) + 1);
            paymentRef.setReason(limit("paper_invoice_mode_switch_canceling", 160));
            paymentRefRepository.save(paymentRef);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("paper_invoice_mode_switch_in_progress: отменяется прежняя T-Bank-сессия", 512));
            invoiceRepository.save(invoice);
            return new PreparedInvoicePaymentModeChange(invoiceId, requestedMode, actor, paymentRef.getId(), normalize(paymentRef.getTbankOrderId()), normalize(paymentRef.getTbankPaymentId()), normalize(paymentRef.getTbankTerminalKey()), paymentRef.getAmountKopecks());
        }
        ensurePaperModeSwitchHasNoUnresolvedProviderEvidence(invoice);
        applyInvoicePaymentModeChange(invoice, requestedMode, actor);
        return PreparedInvoicePaymentModeChange.completed(invoiceId, requestedMode, actor);
    }

    PaperInvoiceBankResolution resolvePaperInvoiceBankSession(PreparedInvoicePaymentModeChange prepared) {
        PaymentProfile profile = paymentProfileService.findByTerminalKey(prepared.terminalKey()).orElse(null);
        if (profile == null) {
            return PaperInvoiceBankResolution.ambiguous(HttpStatus.CONFLICT, "Не найден платёжный профиль прежней T-Bank-сессии. Бумажный режим не включён");
        }
        TbankPaymentProfile runtimeProfile = paymentProfileService.toRuntimeForTerminal(profile, prepared.terminalKey());
        if (!runtimeProfile.hasCredentials()) {
            return PaperInvoiceBankResolution.ambiguous(HttpStatus.CONFLICT, "Для прежней T-Bank-сессии недоступны банковские реквизиты. Бумажный режим не включён");
        }
        TbankGetStateResponse state;
        try {
            state = tbankClient.getState(runtimeProfile, prepared.paymentId());
        } catch (RuntimeException failure) {
            log.warn("Не удалось сверить T-Bank перед включением бумажного общего счёта: invoiceId={}, paymentId={}", prepared.invoiceId(), maskPaymentId(prepared.paymentId()), failure);
            return PaperInvoiceBankResolution.ambiguous(HttpStatus.BAD_GATEWAY, "T-Bank не подтвердил состояние прежней ссылки. Ничего не переключено; повторите позже");
        }
        String stateMismatch = paperModeSwitchStateMismatch(prepared, state, runtimeProfile);
        if (stateMismatch != null) {
            return PaperInvoiceBankResolution.ambiguous(HttpStatus.CONFLICT, stateMismatch);
        }
        String providerStatus = normalize(state.status()).toUpperCase(Locale.ROOT);
        if (isPaperModeSwitchSafeTerminalStatus(providerStatus)) {
            return PaperInvoiceBankResolution.safe(providerStatus);
        }
        if (isPaperModeSwitchFinancialStatus(providerStatus)) {
            return PaperInvoiceBankResolution.blockedByPayment(providerStatus);
        }
        if (!"NEW".equals(providerStatus) && !"FORM_SHOWED".equals(providerStatus)) {
            return PaperInvoiceBankResolution.ambiguous(HttpStatus.CONFLICT, "T-Bank вернул неоднозначный статус " + readableProviderStatus(providerStatus) + ". Бумажный режим не включён; нужна сверка");
        }
        try {
            TbankCancelResponse response = tbankClient.cancel(runtimeProfile, new TbankCancelCommand(prepared.paymentId(), prepared.amountKopecks()));
            String cancelMismatch = paperModeSwitchCancelMismatch(prepared, response, runtimeProfile);
            if (cancelMismatch != null) {
                return PaperInvoiceBankResolution.ambiguous(HttpStatus.CONFLICT, cancelMismatch);
            }
            String cancelStatus = normalize(response.status()).toUpperCase(Locale.ROOT);
            if (PAYMENT_REF_CANCELED.equals(cancelStatus)) {
                return PaperInvoiceBankResolution.safe(cancelStatus);
            }
            if (isPaperModeSwitchFinancialStatus(cancelStatus)) {
                return PaperInvoiceBankResolution.blockedByPayment(cancelStatus);
            }
            return PaperInvoiceBankResolution.ambiguous(HttpStatus.CONFLICT, "T-Bank не подтвердил отмену прежней ссылки: статус " + readableProviderStatus(cancelStatus) + ". Бумажный режим не включён");
        } catch (RuntimeException failure) {
            log.warn("Исход отмены T-Bank перед включением бумажного общего счёта неоднозначен: invoiceId={}, paymentId={}", prepared.invoiceId(), maskPaymentId(prepared.paymentId()), failure);
            return PaperInvoiceBankResolution.ambiguous(HttpStatus.BAD_GATEWAY, "T-Bank не подтвердил отмену прежней ссылки. Счёт заблокирован для безопасной повторной сверки");
        }
    }

    PaperInvoiceModeSwitchResult finishInvoicePaymentModeChange(PreparedInvoicePaymentModeChange prepared, PaperInvoiceBankResolution resolution) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счёт не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        CommonInvoicePaymentRef paymentRef = paymentRefRepository.findByIdForUpdate(prepared.providerPaymentRefId()).orElse(null);
        if (!paperModeSwitchBindingUnchanged(invoice, paymentRef, prepared)) {
            if (paymentRef != null && resolution.switchAllowed()) {
                paymentRef.setStatus(limit(resolution.providerStatus(), 32));
                paymentRef.setReason(limit("paper_invoice_mode_switch_canceled_but_state_changed", 160));
                paymentRefRepository.save(paymentRef);
            }
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit("paper_invoice_mode_switch_state_changed: состояние счёта изменилось во время сверки; обновите данные", 512));
            invoiceRepository.save(invoice);
            return PaperInvoiceModeSwitchResult.failed(HttpStatus.CONFLICT, "Состояние общего счёта изменилось во время сверки. Обновите карточку; повторная оплата не создана");
        }
        if (!resolution.switchAllowed()) {
            paymentRef.setStatus(limit(resolution.durableRefStatus(), 32));
            paymentRef.setReason(limit("paper_invoice_mode_switch_blocked:" + resolution.providerStatus(), 160));
            paymentRefRepository.save(paymentRef);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            String failurePrefix = isPaperModeSwitchFinancialStatus(resolution.providerStatus()) ? "paper_invoice_mode_switch_payment_detected: " : "paper_invoice_mode_switch_retry: ";
            invoice.setLastError(limit(failurePrefix + resolution.failureMessage(), 512));
            invoiceRepository.save(invoice);
            return PaperInvoiceModeSwitchResult.failed(resolution.failureStatus(), resolution.failureMessage());
        }
        paymentRef.setStatus(limit(resolution.providerStatus(), 32));
        paymentRef.setReason(limit("paper_invoice_mode_switch_provider_closed", 160));
        paymentRefRepository.save(paymentRef);
        clearCurrentPaymentRef(invoice);
        applyInvoicePaymentModeChange(invoice, prepared.requestedMode(), prepared.actor());
        return PaperInvoiceModeSwitchResult.switchedSuccessfully();
    }

    void applyInvoicePaymentModeChange(CommonInvoice invoice, InvoicePaymentMode requestedMode, String actor) {
        Long invoiceId = invoice.getId();
        if (invoice.getContractorAllocationId() != null) {
            contractorPaymentLiveRoutingService.releaseCommonInvoiceRouteForPaperInvoice(invoice);
        }
        clearCommonPaymentRouteForModeSwitch(invoice);
        invoice.setInvoicePaymentMode(requestedMode);
        invoice.setPaperInvoiceIssuedAt(null);
        invoice.setSentAt(null);
        invoice.setLastReminderAt(null);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        invoice.setStatus(areInvoiceItemsReady(items) ? CommonInvoiceStatus.READY : CommonInvoiceStatus.COLLECTING);
        CommonBillingAccount account = invoice.getAccount();
        account.setInvoicePaymentMode(requestedMode);
        accountRepository.save(account);
        invoiceRepository.save(invoice);
        paperInvoiceManagerNotificationService.closeAfterCommit(invoiceId);
        log.info("Common invoice payment mode changed: invoiceId={}, mode={}, actor={}", invoiceId, requestedMode, actor);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markPaperInvoiceIssued(Long invoiceId, Principal principal) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счёт не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if (!isOwnerPaperInvoice(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Для общего счёта не включён бумажный счёт");
        }
        if (invoice.getSentAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала отправьте клиенту уведомление о завершении общего счёта");
        }
        if (invoice.getPaperInvoiceIssuedAt() == null) {
            LocalDateTime now = LocalDateTime.now();
            invoice.setPaperInvoiceIssuedAt(now);
            invoice.setNextReminderAt(nextAutomaticPaymentReminderAt(now));
            invoice.setLastError(null);
            invoiceRepository.save(invoice);
            log.info("Common paper invoice marked issued: invoiceId={}, actor={}", invoiceId, principal == null ? "system" : principal.getName());
        }
        paperInvoiceManagerNotificationService.closeAfterCommit(invoiceId);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    void ensurePaperModeSwitchHasNoFinancialEvidence(CommonInvoice invoice) {
        manualPaymentWorkflow.ensurePaperModeSwitchHasNoFinancialEvidence(invoice);
    }

    void ensurePaperModeSwitchHasNoUnresolvedProviderEvidence(CommonInvoice invoice) {
        manualPaymentWorkflow.ensurePaperModeSwitchHasNoUnresolvedProviderEvidence(invoice);
    }

    boolean hasCurrentTbankPaymentBinding(CommonInvoice invoice) {
        return manualPaymentWorkflow.hasCurrentTbankPaymentBinding(invoice);
    }

    CommonInvoicePaymentRef currentPaymentRefForPaperModeSwitch(CommonInvoice invoice) {
        return manualPaymentWorkflow.currentPaymentRefForPaperModeSwitch(invoice);
    }

    boolean paperModeSwitchBindingUnchanged(CommonInvoice invoice, CommonInvoicePaymentRef paymentRef, PreparedInvoicePaymentModeChange prepared) {
        return invoice != null && paymentRef != null && invoice.getInvoicePaymentMode() != prepared.requestedMode() && invoice.getPaidKopecks() == 0 && invoice.getClientReportedAt() == null && invoice.getManualConfirmedAt() == null && prepared.tbankOrderId().equals(normalize(invoice.getTbankOrderId())) && prepared.paymentId().equals(normalize(invoice.getTbankPaymentId())) && prepared.terminalKey().equals(normalize(invoice.getTbankTerminalKey())) && Objects.equals(prepared.amountKopecks(), invoice.getTbankPaymentAmountKopecks()) && prepared.tbankOrderId().equals(normalize(paymentRef.getTbankOrderId())) && prepared.paymentId().equals(normalize(paymentRef.getTbankPaymentId())) && prepared.terminalKey().equals(normalize(paymentRef.getTbankTerminalKey())) && Objects.equals(prepared.amountKopecks(), paymentRef.getAmountKopecks());
    }

    String paperModeSwitchStateMismatch(PreparedInvoicePaymentModeChange prepared, TbankGetStateResponse state, TbankPaymentProfile runtimeProfile) {
        if (state == null || !state.success()) {
            return "T-Bank не подтвердил состояние прежней ссылки. Бумажный режим не включён";
        }
        if (!normalize(state.paymentId()).isBlank() && !prepared.paymentId().equals(normalize(state.paymentId()))) {
            return "PaymentId ответа T-Bank не совпал с общим счётом. Бумажный режим не включён";
        }
        if (!normalize(state.orderId()).isBlank() && !prepared.tbankOrderId().isBlank() && !prepared.tbankOrderId().equals(normalize(state.orderId()))) {
            return "OrderId ответа T-Bank не совпал с общим счётом. Бумажный режим не включён";
        }
        if (!normalize(state.terminalKey()).isBlank() && !normalize(runtimeProfile.terminalKey()).equals(normalize(state.terminalKey()))) {
            return "TerminalKey ответа T-Bank не совпал с общим счётом. Бумажный режим не включён";
        }
        if (state.amount() != null && state.amount() != prepared.amountKopecks()) {
            return "Сумма ответа T-Bank не совпала с общим счётом. Бумажный режим не включён";
        }
        return null;
    }

    String paperModeSwitchCancelMismatch(PreparedInvoicePaymentModeChange prepared, TbankCancelResponse response, TbankPaymentProfile runtimeProfile) {
        if (response == null || !response.success()) {
            return "T-Bank не подтвердил отмену прежней ссылки. Бумажный режим не включён";
        }
        if (!normalize(response.paymentId()).isBlank() && !prepared.paymentId().equals(normalize(response.paymentId()))) {
            return "PaymentId отмены T-Bank не совпал с общим счётом. Бумажный режим не включён";
        }
        if (!normalize(response.orderId()).isBlank() && !prepared.tbankOrderId().isBlank() && !prepared.tbankOrderId().equals(normalize(response.orderId()))) {
            return "OrderId отмены T-Bank не совпал с общим счётом. Бумажный режим не включён";
        }
        if (!normalize(response.terminalKey()).isBlank() && !normalize(runtimeProfile.terminalKey()).equals(normalize(response.terminalKey()))) {
            return "TerminalKey отмены T-Bank не совпал с общим счётом. Бумажный режим не включён";
        }
        if (response.amount() != null && response.amount() != prepared.amountKopecks()) {
            return "Сумма отмены T-Bank не совпала с общим счётом. Бумажный режим не включён";
        }
        return null;
    }

    boolean isPaperModeSwitchSafeTerminalStatus(String status) {
        return settlementService.isPaperModeSwitchSafeTerminalStatus(status);
    }

    boolean isPaperModeSwitchFinancialStatus(String status) {
        return settlementService.isPaperModeSwitchFinancialStatus(status);
    }

    String readableProviderStatus(String status) {
        return settlementService.readableProviderStatus(status);
    }

    String maskPaymentId(String paymentId) {
        return CommonInvoicePaymentIdentity.maskPaymentId(paymentId);
    }

    void clearCommonPaymentRouteForModeSwitch(CommonInvoice invoice) {
        invoice.setPaymentRouteType(null);
        invoice.setPaymentRouteProfileId(null);
        invoice.setPaymentRouteProfileCode(null);
        invoice.setPaymentRouteProfileName(null);
        invoice.setContractorAllocationId(null);
        invoice.setPaymentRouteTerminalKey(null);
        invoice.setPaymentRouteManualSource(null);
        invoice.setPaymentRouteManualTaskId(null);
        invoice.setPaymentRouteManualTaskSourceGeneration(null);
        invoice.setPaymentRouteManualTaskGeneration(null);
        invoice.setPaymentRouteManualTaskAccountingMode(null);
        invoice.setPaymentRouteManualType(null);
        invoice.setPaymentRouteManualPhone(null);
        invoice.setPaymentRouteManualRecipient(null);
        invoice.setPaymentRouteManualBankName(null);
        invoice.setPaymentRouteManualUrl(null);
        invoice.setPaymentRouteManualButton(null);
        invoice.setPaymentRouteManualComment(null);
        invoice.setPaymentRouteInstructionText(null);
        invoice.setPaymentRouteAmountKopecks(null);
        invoice.setPaymentRouteSelectedAt(null);
    }

    boolean isOwnerPaperInvoice(CommonInvoice invoice) {
        return CommonInvoiceRouteState.isOwnerPaperInvoice(invoice);
    }

    void ensureTbankCancellationSupported(CommonInvoicePaymentRef paymentRef, String operationFailure) {
        manualPaymentWorkflow.ensureTbankCancellationSupported(paymentRef, operationFailure);
    }

    boolean isEligibleCommonBillingManager(Manager manager) {
        return invoiceInitialization.isEligibleCommonBillingManager(manager);
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return settlementService.lockedInvoice(invoiceId);
    }

    <T> T writeTransaction(Supplier<T> action) {
        return settlementService.writeTransaction(action);
    }

    boolean areInvoiceItemsReady(List<CommonInvoiceOrder> items) {
        return settlementService.areInvoiceItemsReady(items);
    }

    boolean isPreparedPaymentRef(CommonInvoicePaymentRef ref) {
        return invoiceDetailsAssembler.isPreparedPaymentRef(ref);
    }

    String paymentRefStatus(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefStatus(ref);
    }

    String normalizedPaymentProvider(CommonInvoicePaymentRef ref) {
        return settlementService.normalizedPaymentProvider(ref);
    }

    String providerOrderId(CommonInvoicePaymentRef ref) {
        return CommonInvoicePaymentIdentity.providerOrderId(ref);
    }

    String providerPaymentId(CommonInvoicePaymentRef ref) {
        return settlementService.providerPaymentId(ref);
    }

    String providerMerchantId(CommonInvoicePaymentRef ref) {
        return CommonInvoicePaymentIdentity.providerMerchantId(ref);
    }

    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.refreshInvoiceAmounts(invoice, items);
    }

    void appendEvidenceField(StringBuilder target, Object value) {
        invoiceDetailsAssembler.appendEvidenceField(target, value);
    }

    void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        invoiceDelivery.ensureCommonInvoiceVisibleForCurrentUser(invoice);
    }

    boolean isContractorRecipient(ContractorPaymentAllocation allocation) {
        return invoiceInitialization.isContractorRecipient(allocation);
    }

    ContractorPaymentAllocation requireLiveCommonInvoiceAllocation(CommonInvoice invoice, ContractorPaymentAllocation allocation) {
        return invoiceInitialization.requireLiveCommonInvoiceAllocation(invoice, allocation);
    }

    PaymentRouteSelection contractorCommonPaymentRoute(CommonInvoice invoice, ContractorPaymentAllocation allocation) {
        return invoiceInitialization.contractorCommonPaymentRoute(invoice, allocation);
    }

    boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return CommonInvoiceRouteState.hasFrozenCommonPaymentRoute(invoice);
    }

    void applyCommonPaymentRoute(CommonInvoice invoice, PaymentRouteSelection route, long remainingKopecks) {
        invoiceInitialization.applyCommonPaymentRoute(invoice, route, remainingKopecks);
    }

    boolean isTbankCommonRoute(CommonInvoice invoice) {
        return invoicePresenter.isTbankCommonRoute(invoice);
    }

    Manager manager(CommonInvoice invoice) {
        return invoiceInitialization.manager(invoice);
    }

    Manager manager(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceInitialization.manager(invoice, items);
    }

    String commonPaymentManagerBlockReason(CommonInvoice invoice) {
        Manager assigned = manager(invoice);
        if (assigned == null) {
            return "Для общего счёта не назначен менеджер. Назначьте менеджера в настройках связи " + "перед созданием нового способа оплаты";
        }
        if (isEligibleCommonBillingManager(assigned)) {
            return null;
        }
        return "Назначенный менеджер общего счёта неактивен или больше не имеет роли менеджера. " + "Переназначьте менеджера в настройках связи перед созданием или отправкой оплаты";
    }

    Manager managerForNewCommonPaymentAction(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceInitialization.managerForNewCommonPaymentAction(invoice, items);
    }

    Manager managerForNewCommonPaymentAction(CommonInvoice invoice) {
        return invoiceInitialization.managerForNewCommonPaymentAction(invoice);
    }

    LocalDateTime nextAutomaticPaymentReminderAt(LocalDateTime from) {
        return settlementService.nextAutomaticPaymentReminderAt(from);
    }

    long remainingKopecks(CommonInvoice invoice) {
        return settlementService.remainingKopecks(invoice);
    }

    void recordCurrentPaymentRef(CommonInvoice invoice, String status, String reason) {
        settlementService.recordCurrentPaymentRef(invoice, status, reason);
    }

    int cancelAttempts(CommonInvoicePaymentRef ref) {
        return invoiceCancellation.cancelAttempts(ref);
    }

    boolean isStaleArchivedPaymentCancel(CommonInvoicePaymentRef ref) {
        return invoiceCancellation.isStaleArchivedPaymentCancel(ref);
    }

    boolean canCancelInitializedPaymentRef(CommonInvoicePaymentRef ref) {
        return settlementService.canCancelInitializedPaymentRef(ref);
    }

    void clearCurrentPaymentRef(CommonInvoice invoice) {
        settlementService.clearCurrentPaymentRef(invoice);
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

    record PreparedInvoicePaymentModeChange(Long invoiceId, InvoicePaymentMode requestedMode, String actor, Long providerPaymentRefId, String tbankOrderId, String paymentId, String terminalKey, long amountKopecks) {

        private static PreparedInvoicePaymentModeChange completed(Long invoiceId, InvoicePaymentMode requestedMode, String actor) {
            return new PreparedInvoicePaymentModeChange(invoiceId, requestedMode, actor, null, "", "", "", 0L);
        }
    }

    record PaperInvoiceModeSwitchResult(boolean switched, HttpStatus failureStatus, String failureMessage) {

        private static PaperInvoiceModeSwitchResult switchedSuccessfully() {
            return new PaperInvoiceModeSwitchResult(true, null, "");
        }

        private static PaperInvoiceModeSwitchResult failed(HttpStatus status, String message) {
            return new PaperInvoiceModeSwitchResult(false, status == null ? HttpStatus.CONFLICT : status, message);
        }
    }
}
