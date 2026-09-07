package com.hunt.otziv.payments.service;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.*;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRequisitesSnapshot;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.payments.dto.PaymentRouteSelection;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentPolicy;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import static com.hunt.otziv.logs.util.LogMasking.maskPaymentId;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceRouteSelector {

    private final PaymentBankObservationService bankObservations;

    static final Set<PaymentLinkStatus> MANUAL_USAGE_STATUSES = Set.of(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, PaymentLinkStatus.MANUAL_REPORTED, PaymentLinkStatus.CONFIRMED);

    static final Set<PaymentMethod> MANUAL_PAYMENT_METHODS = Set.of(PaymentMethod.MANUAL_MOBILE_BANK, PaymentMethod.MANUAL_EXTERNAL_LINK, PaymentMethod.OWNER_PAPER_INVOICE);

    private final PaymentLinkRepository paymentLinkRepository;

    private final TbankRuntimeSettingsService runtimeSettingsService;

    private final PaymentProfileService paymentProfileService;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final ManualPaymentTaskService manualPaymentTaskService;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    private final ContractorActualPaymentAttributionService actualPaymentAttributionService;

    boolean isPaperInvoice(PaymentLink link) {
        return link != null && link.getPaymentMethod() == PaymentMethod.OWNER_PAPER_INVOICE;
    }

    @Transactional
    public PaymentRouteSelection selectCommonInvoiceRoute(Manager manager, long amountKopecks) {
        return selectCommonInvoiceRoute(manager, amountKopecks, false);
    }

    @Transactional
    public PaymentRouteSelection selectCommonInvoiceOwnerAcquiringRoute(Manager manager, long amountKopecks) {
        return selectCommonInvoiceRoute(manager, amountKopecks, true);
    }

    PaymentRouteSelection selectCommonInvoiceRoute(Manager manager, long amountKopecks, boolean forceTbankAcquiring) {
        if (amountKopecks <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета нет суммы к оплате");
        }
        if (!forceTbankAcquiring && TbankRuntimeSettingsService.PAYMENT_SOURCE_MANAGER_TEXT.equals(runtimeSettingsService.paymentInstructionSource())) {
            return new PaymentRouteSelection(TbankRuntimeSettingsService.PAYMENT_SOURCE_MANAGER_TEXT, null, "", "", "", null, null, null, "", "", "", "", "", commonInvoiceManagerText(manager));
        }
        PaymentProfile profile = paymentProfileService.lockForRouting(paymentProfileService.selectForManager(manager));
        boolean tochkaProvider = paymentProfileService.isTochkaProvider(profile);
        TbankPaymentProfile runtimeProfile = tochkaProvider ? null : paymentProfileService.toRuntime(profile);
        TochkaPaymentProfile tochkaProfile = tochkaProvider ? tochkaPaymentProfileResolver.resolve(profile) : null;
        PaymentLink route = new PaymentLink();
        route.setAmountKopecks(amountKopecks);
        route.setReservedAmountKopecks(amountKopecks);
        route.setDescription("Общий счет");
        applyPaymentProfile(route, profile);
        // Common-invoice tasks are reserved exclusively through the typed
        // task ledger in selectCommonInvoiceTaskRoute. Falling back here must
        // never rediscover a legacy task without source generation/binding.
        if (!forceTbankAcquiring && allowLegacyManualProfileRoute() && shouldUseManualPayment(profile, amountKopecks, LocalDateTime.now(), null)) {
            applyManualProfilePayment(route, profile);
        } else {
            applyBankPaymentRoute(route);
        }
        return new PaymentRouteSelection(route.getPaymentMethod() == PaymentMethod.BANK_FORM || route.getPaymentMethod() == PaymentMethod.SBP_QR ? TbankRuntimeSettingsService.PAYMENT_SOURCE_BANK_LINK : route.getPaymentMethod().name(), profile.getId(), normalize(profile.getCode()), normalize(profile.getName()), normalize(tochkaProfile == null ? runtimeProfile == null ? null : runtimeProfile.terminalKey() : tochkaProfile.merchantId()), manualSourceName(route), route.getManualPaymentTask() == null ? null : route.getManualPaymentTask().getId(), manualPaymentTypeName(route), normalize(route.getManualPhone()), manualRecipientName(route), isManualPayment(route) ? manualPaymentUrlForRead(route.getManualPaymentUrl()) : "", manualButtonLabel(route), normalize(route.getManualComment()), isManualPayment(route) ? paymentInstructionText(route, "") : "");
    }

    @Transactional
    public Optional<PaymentRouteSelection> selectCommonInvoiceTaskRoute(com.hunt.otziv.common_billing.model.CommonInvoice invoice, Manager manager, long amountKopecks) {
        // A new task route is safe only while the typed actual-recipient flow
        // is authoritative. Existing frozen routes are handled by the caller
        // before this selector is reached.
        if (!actualPaymentAttributionService.actualRecipientAccountingEnabled()) {
            invoice.setPaymentRouteManualTaskSourceGeneration(null);
            return Optional.empty();
        }
        PaymentProfile profile = paymentProfileService.lockForRouting(paymentProfileService.selectForManager(manager));
        Optional<ManualPaymentTaskRouteSnapshot> snapshot = taskReceiptIntegrationService.reserveForCommonInvoice(invoice, manager.getId(), profile.getId(), amountKopecks);
        if (snapshot.isEmpty()) {
            invoice.setPaymentRouteManualTaskSourceGeneration(null);
            return Optional.empty();
        }
        ManualPaymentTaskRouteSnapshot task = snapshot.get();
        String transferNumber = manualTaskTransferNumber(task.manualPhone());
        return Optional.of(new PaymentRouteSelection(PaymentMethod.MANUAL_MOBILE_BANK.name(), profile.getId(), normalize(profile.getCode()), normalize(profile.getName()), "", ManualPaymentSource.MANUAL_TASK.name(), task.taskId(), ManualPaymentType.MOBILE_BANK.name(), transferNumber, manualRecipientName(task.bankRecipientName()), limit(task.bankName(), 120), "", "", "Платёжное задание #" + task.taskId(), paymentInstructionTextForTask(task), task.source().sourceGeneration(), task.taskGeneration()));
    }

    String commonInvoiceManagerText(Manager manager) {
        String text = normalize(manager == null ? null : manager.getPayText());
        return limit(text.isBlank() ? "Здравствуйте, напоминаем об оплате выполненных заказов. Пришлите чек после оплаты." : text, 1000);
    }

    boolean allowLegacyManualProfileRoute() {
        return !contractorPaymentLiveRoutingService.configuredButBlockedForNewRoutes();
    }

    void applyBankPaymentRoute(PaymentLink link) {
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setManualSource(null);
        link.setManualPaymentTask(null);
        link.setManualPaymentType(null);
        link.setManualPaymentUrl(null);
        link.setManualPaymentButtonLabel(null);
    }

    boolean isFrozenContractorRoute(PaymentLink link) {
        return link != null && link.getContractorAllocationId() != null && link.getManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE;
    }

    boolean shouldUseManualPayment(PaymentProfile profile, long amountKopecks, LocalDateTime now, Long excludedLinkId) {
        if (profile == null || profile.getPaymentPolicy() != PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK || !hasManualPaymentTarget(profile)) {
            return false;
        }
        long monthlyLimit = manualMonthlyHardLimit(profile);
        if (monthlyLimit <= 0 || amountKopecks <= 0 || profile.getId() == null) {
            return false;
        }
        LocalDateTime periodStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        LocalDateTime periodEnd = periodStart.plusMonths(1);
        long alreadyUsed = paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(profile.getId(), MANUAL_PAYMENT_METHODS, MANUAL_USAGE_STATUSES, periodStart, periodEnd, now, PaymentLinkStatus.CONFIRMED, excludedLinkId);
        alreadyUsed += manualPaymentTaskService.commonInvoiceProfileUsageForPeriod(profile.getId(), periodStart, periodEnd);
        return alreadyUsed + amountKopecks <= monthlyLimit;
    }

    long manualMonthlyHardLimit(PaymentProfile profile) {
        Long hardLimit = profile.getManualMonthlyHardLimitKopecks();
        if (hardLimit != null && hardLimit > 0) {
            return hardLimit;
        }
        Long softLimit = profile.getManualMonthlySoftLimitKopecks();
        return softLimit == null || softLimit <= 0 ? PaymentProfile.DEFAULT_MANUAL_MONTHLY_LIMIT_KOPECKS : softLimit;
    }

    boolean hasManualPaymentTarget(PaymentProfile profile) {
        if (manualPaymentType(profile) == ManualPaymentType.MOBILE_BANK) {
            return !normalize(profile.getManualPhone()).isBlank() && !normalize(profile.getManualRecipientName()).isBlank();
        }
        return !manualPaymentUrlForRead(profile.getManualPaymentUrl()).isBlank();
    }

    ManualPaymentType manualPaymentType(PaymentProfile profile) {
        return profile.getManualPaymentType() == null ? ManualPaymentType.MOBILE_BANK : profile.getManualPaymentType();
    }

    ManualPaymentType manualPaymentType(ManualPaymentTask task) {
        return task.getManualPaymentType() == null ? ManualPaymentType.MOBILE_BANK : task.getManualPaymentType();
    }

    String manualTaskTransferNumber(String value) {
        String transferNumber = limit(value, 32);
        if (transferNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У платежного задания нет телефона или номера карты для отправки клиенту");
        }
        return transferNumber;
    }

    PaymentMethod paymentMethodFor(ManualPaymentType type) {
        return type == ManualPaymentType.MOBILE_BANK ? PaymentMethod.MANUAL_MOBILE_BANK : PaymentMethod.MANUAL_EXTERNAL_LINK;
    }

    String manualPaymentUrl(String value) {
        return PaymentUrlPolicy.requireOrDefault(value, ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL, PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL, HttpStatus.BAD_GATEWAY, "Сохраненная ссылка ручной оплаты имеет недопустимый формат");
    }

    String manualPaymentUrlForRead(String value) {
        return PaymentUrlPolicy.safeOrDefault(value, ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL, PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL);
    }

    String manualButtonLabel(String value) {
        String clean = limit(value, 80);
        return clean.isBlank() ? ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_BUTTON_LABEL : clean;
    }

    String manualButtonLabel(PaymentLink link) {
        if (!isManualPayment(link)) {
            return "";
        }
        return manualButtonLabel(link.getManualPaymentButtonLabel());
    }

    String manualRecipientName(String value) {
        String clean = limit(value, 160);
        return clean.isBlank() || ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_BUTTON_LABEL.equals(clean) ? ManualPaymentType.DEFAULT_MANUAL_RECIPIENT_NAME : clean;
    }

    String manualRecipientName(PaymentLink link) {
        if (!isManualPayment(link)) {
            return "";
        }
        return manualRouteReadView(link).recipientName();
    }

    ManualRouteReadView manualRouteReadView(PaymentLink link) {
        if (isPaperInvoice(link)) {
            return ManualRouteReadView.empty();
        }
        boolean contractorRoute = isFrozenContractorRoute(link);
        if (contractorRoute) {
            return contractorPaymentLiveRoutingService.activePaymentLinkRequisites(link).map(snapshot -> ManualRouteReadView.activeContractor(snapshot, manualRecipientName(snapshot.recipientName()))).orElseGet(() -> ManualRouteReadView.redactedContractor());
        }
        if (link == null || !isManualPayment(link)) {
            return ManualRouteReadView.empty();
        }
        return new ManualRouteReadView(false, true, normalize(link.getManualPhone()), manualRecipientName(link.getManualRecipientName()), normalize(link.getManualBankName()), manualPaymentUrlForRead(link.getManualPaymentUrl()), normalize(link.getManualComment()));
    }

    void applyManualProfilePayment(PaymentLink link, PaymentProfile profile) {
        ManualPaymentType type = manualPaymentType(profile);
        link.setPaymentMethod(paymentMethodFor(type));
        link.setManualPaymentType(type);
        link.setManualSource(ManualPaymentSource.PROFILE_MONTHLY_LIMIT);
        link.setManualPaymentTask(null);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setManualPhone(limit(profile.getManualPhone(), 32));
        link.setManualRecipientName(manualRecipientName(profile.getManualRecipientName()));
        link.setManualPaymentUrl(manualPaymentUrl(profile.getManualPaymentUrl()));
        link.setManualPaymentButtonLabel(manualButtonLabel(profile.getManualPaymentButtonLabel()));
        link.setManualComment(manualComment(profile.getManualComment(), link));
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
    }

    String paymentInstructionTextForTask(ManualPaymentTaskRouteSnapshot task) {
        PaymentLink view = new PaymentLink();
        view.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        view.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        view.setManualPhone(manualTaskTransferNumber(task.manualPhone()));
        view.setManualRecipientName(task.bankRecipientName());
        view.setManualBankName(limit(task.bankName(), 120));
        view.setManualPaymentUrl(null);
        view.setManualPaymentButtonLabel(null);
        return paymentInstructionText(view, "");
    }

    void applyPaymentProfile(PaymentLink link, PaymentProfile profile) {
        link.setPaymentProfile(profile);
        link.setPaymentProfileCode(profile.getCode());
        link.setPaymentProfileName(profile.getName());
    }

    String manualSourceName(PaymentLink link) {
        return link.getManualSource() == null ? null : link.getManualSource().name();
    }

    String manualPaymentTypeName(PaymentLink link) {
        if (!isManualPayment(link) || isPaperInvoice(link)) {
            return null;
        }
        return manualPaymentType(link).name();
    }

    ManualPaymentType manualPaymentType(PaymentLink link) {
        if (isPaperInvoice(link)) {
            return null;
        }
        if (link.getManualPaymentType() != null) {
            return link.getManualPaymentType();
        }
        return link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK ? ManualPaymentType.EXTERNAL_LINK : ManualPaymentType.MOBILE_BANK;
    }

    String paymentInstructionText(PaymentLink link, String url) {
        return paymentInstructionText(link, url, manualRouteReadView(link));
    }

    String paymentInstructionText(PaymentLink link, String url, ManualRouteReadView requisites) {
        if (isPaperInvoice(link)) {
            return "Оплата производится по выставленному счёту. Документ будет отправлен в этот чат.";
        }
        if (!isManualPayment(link)) {
            return "Ссылка на оплату: " + url;
        }
        if (requisites.contractorRoute() && !requisites.available()) {
            return "";
        }
        String bankName = requisites.bankName();
        String comment = requisites.comment();
        if (manualPaymentType(link) == ManualPaymentType.EXTERNAL_LINK) {
            return manualPaymentInstruction("Ссылка на оплату: " + requisites.paymentUrl(), requisites.recipientName(), bankName, comment);
        }
        return manualPaymentInstruction(mobileBankPaymentLine(requisites.phone()), requisites.recipientName(), bankName, comment);
    }

    String mobileBankPaymentLine(String rawValue) {
        String value = normalize(rawValue);
        return "Оплата по мобильному банку: " + value;
    }

    String manualPaymentInstruction(String paymentLine, String recipient, String bankName, String comment) {
        String cleanBankName = normalize(bankName);
        String cleanComment = normalize(comment);
        StringBuilder instruction = new StringBuilder().append(paymentLine).append('\n').append("Получатель: ").append(recipient);
        if (!cleanBankName.isBlank()) {
            instruction.append('\n').append("Банк: ").append(cleanBankName);
        }
        if (!cleanComment.isBlank()) {
            instruction.append('\n').append("Комментарий: ").append(cleanComment);
        }
        return instruction.toString();
    }

    String manualComment(PaymentLink link) {
        String stored = manualRouteReadView(link).comment();
        if (!stored.isBlank()) {
            return stored;
        }
        return "";
    }

    String manualComment(String template, PaymentLink link) {
        String clean = limit(template, 255);
        if (clean.isBlank()) {
            return null;
        }
        String comment = limit(clean.replace("{orderId}", orderIdText(link)), 255);
        return comment.isBlank() ? null : comment;
    }

    String orderIdText(PaymentLink link) {
        Long orderId = link.getOrder() == null ? null : link.getOrder().getId();
        return orderId == null ? "" : String.valueOf(orderId);
    }

    boolean isManualPayment(PaymentLink link) {
        return link != null && MANUAL_PAYMENT_METHODS.contains(link.getPaymentMethod());
    }

    String normalize(String value) {
        return bankObservations.normalize(value);
    }

    String limit(String value, int maxLength) {
        return bankObservations.limit(value, maxLength);
    }

    record ManualRouteReadView(boolean contractorRoute, boolean available, String phone, String recipientName, String bankName, String paymentUrl, String comment) {

        private static ManualRouteReadView activeContractor(ContractorPaymentRequisitesSnapshot snapshot, String normalizedRecipientName) {
            return new ManualRouteReadView(true, true, normalizeValue(snapshot.paymentPhone()), normalizeValue(normalizedRecipientName), normalizeValue(snapshot.bankName()), "", normalizeValue(snapshot.paymentComment()));
        }

        private static ManualRouteReadView redactedContractor() {
            return new ManualRouteReadView(true, false, "", "", "", "", "");
        }

        private static ManualRouteReadView empty() {
            return new ManualRouteReadView(false, false, "", "", "", "", "");
        }

        private static String normalizeValue(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
