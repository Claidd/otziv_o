package com.hunt.otziv.payments.service;

import static com.hunt.otziv.payments.service.CommonInvoiceRouteSelector.*;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.*;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTransferNumber;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinkSummaryResponse;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PaymentLinkAdminSummary;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.TbankPaymentPageMode;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static com.hunt.otziv.logs.util.LogMasking.maskPaymentId;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentLinkPresenter {

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentBankObservationService bankObservations;

    static final Set<PaymentLinkStatus> REFUNDABLE_STATUSES = Set.of(PaymentLinkStatus.AUTHORIZED, PaymentLinkStatus.TEST_CONFIRMED, PaymentLinkStatus.CONFIRMED, PaymentLinkStatus.AMOUNT_MISMATCH);

    private final TbankPaymentProperties properties;

    private final TbankRuntimeSettingsService runtimeSettingsService;

    private final PaymentProfileService paymentProfileService;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final AppSettingService appSettingService;

    boolean isPaperInvoice(PaymentLink link) {
        return commonInvoiceRouteSelector.isPaperInvoice(link);
    }

    boolean isTochkaPaymentLink(PaymentLink link) {
        return bankObservations.isTochkaPaymentLink(link);
    }

    AdminPaymentLinkSummaryResponse toSummaryResponse(PaymentLinkAdminSummary summary) {
        PaymentLinkAdminSummary safe = summary == null ? new PaymentLinkAdminSummary(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L) : summary;
        return new AdminPaymentLinkSummaryResponse(safe.safeTotalElements(), amountRubles(safe.safeTotalAmountKopecks()), safe.safeTotalAmountKopecks(), safe.safePaid(), safe.safeManualPending(), safe.safeConfirmed(), safe.safeNotificationsSent(), safe.safeNotificationErrors(), safe.safeRefundable(), safe.safeRefunded(), safe.safeRejected(), safe.safeReceiptPending(), safe.safeReceiptOverdue());
    }

    boolean hasBankCancelReservation(PaymentLink link) {
        return link != null && !normalize(link.getBankCancelNonce()).isBlank();
    }

    PublicPaymentLinkResponse toPublicResponse(PaymentLink link) {
        Order order = link.getOrder();
        ManualRouteReadView requisites = manualRouteReadView(link);
        boolean payable = isPayable(link, requisites);
        boolean contractorRequisitesVisible = !requisites.contractorRoute() || (requisites.available() && payable);
        String provider = publicPaymentProvider(link);
        boolean tochkaProvider = PaymentProfile.PROVIDER_TOCHKA.equals(provider);
        TochkaPaymentProfile publicTochkaProfile = tochkaProvider ? resolvePublicTochkaProfile(link.getPaymentProfile()) : null;
        boolean tochkaEnabled = publicTochkaProfile != null && (publicTochkaProfile.paymentModes().contains(TochkaPaymentMode.CARD) || publicTochkaProfile.paymentModes().contains(TochkaPaymentMode.SBP));
        boolean providerPayable = payable && (!tochkaProvider || tochkaEnabled);
        return new PublicPaymentLinkResponse(link.getToken(), order == null ? null : order.getId(), companyTitle(order), filialTitle(order), link.getDescription(), amountRubles(link.getAmountKopecks()), link.getAmountKopecks(), link.getDescription(), "", link.getStatus().name(), paymentMethodName(link), provider, link.getExpiresAt(), providerPayable, tochkaProvider ? (tochkaEnabled ? publicTochkaPaymentPageMode(publicTochkaProfile).name() : TbankPaymentPageMode.BANK_ONLY.name()) : paymentPageModeName(), !tochkaProvider, !tochkaProvider && runtimeSettingsService.isTpayEnabled(), !tochkaProvider && runtimeSettingsService.isSberpayEnabled(), !tochkaProvider && runtimeSettingsService.isMirpayEnabled(), manualPaymentTypeName(link), contractorRequisitesVisible ? requisites.phone() : "", contractorRequisitesVisible ? requisites.recipientName() : "", contractorRequisitesVisible ? requisites.bankName() : "", contractorRequisitesVisible && isManualPayment(link) ? requisites.paymentUrl() : "", manualButtonLabel(link), contractorRequisitesVisible ? requisites.comment() : "", link.getReceiptStatus() == null ? null : link.getReceiptStatus().name());
    }

    TochkaPaymentProfile resolvePublicTochkaProfile(PaymentProfile profile) {
        try {
            return profile == null ? null : tochkaPaymentProfileResolver.resolve(profile);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    TbankPaymentPageMode publicTochkaPaymentPageMode(TochkaPaymentProfile profile) {
        boolean card = profile != null && profile.paymentModes().contains(TochkaPaymentMode.CARD);
        boolean sbp = profile != null && profile.paymentModes().contains(TochkaPaymentMode.SBP);
        if (sbp && card) {
            return TbankPaymentPageMode.SBP_PRIMARY;
        }
        if (sbp) {
            return TbankPaymentPageMode.SBP_ONLY;
        }
        return TbankPaymentPageMode.BANK_ONLY;
    }

    String paymentPageModeName() {
        TbankPaymentPageMode mode = runtimeSettingsService.paymentPageMode();
        return (mode == null ? TbankRuntimeSettingsService.DEFAULT_PAYMENT_PAGE_MODE : mode).name();
    }

    String publicPaymentProvider(PaymentLink link) {
        PaymentProfile linkedProfile = link == null ? null : link.getPaymentProfile();
        return linkedProfile == null ? PaymentProfile.PROVIDER_TBANK : paymentProfileService.provider(linkedProfile);
    }

    ManagerPaymentLinkResponse toManagerResponse(PaymentLink link) {
        String url = publicPaymentUrl(link);
        ManualRouteReadView requisites = manualRouteReadView(link);
        return new ManagerPaymentLinkResponse(link.getToken(), url, link.getOrder() == null ? null : link.getOrder().getId(), amountRubles(link.getAmountKopecks()), link.getAmountKopecks(), link.getStatus().name(), paymentMethodName(link), link.getExpiresAt(), paymentInstructionText(link, url, requisites), paymentCopyText(link, url, requisites), telegramCopyTransferNumber(link, requisites));
    }

    String telegramCopyTransferNumber(PaymentLink link, ManualRouteReadView requisites) {
        if (!isManualPayment(link) || manualPaymentType(link) != ManualPaymentType.MOBILE_BANK || requisites == null || !requisites.available()) {
            return null;
        }
        return ContractorPaymentTransferNumber.isValid(requisites.phone()) ? ContractorPaymentTransferNumber.normalize(requisites.phone()) : null;
    }

    AdminPaymentLinkResponse toAdminResponse(PaymentLink link) {
        Order order = link.getOrder();
        ManualRouteReadView requisites = manualRouteReadView(link);
        return new AdminPaymentLinkResponse(link.getId(), link.getToken(), publicPaymentUrl(link), order == null ? null : order.getId(), companyTitle(order), filialTitle(order), link.getDescription(), amountRubles(link.getAmountKopecks()), link.getAmountKopecks(), link.getReservedAmountKopecks(), link.getConfirmedAmountKopecks(), link.getStatus().name(), paymentMethodName(link), paymentProfileCode(link), paymentProfileName(link), manualSourceName(link), manualTaskId(link), manualTaskTitle(link), normalize(link.getTbankTerminalKey()), link.getTbankPaymentId(), link.getTbankOrderId(), link.getPayerEmail(), PaymentUrlPolicy.safe(link.getPaymentUrl(), isTochkaPaymentLink(link) ? PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT : PaymentUrlPolicy.Purpose.TBANK_PAYMENT), manualPaymentTypeName(link), requisites.phone(), requisites.recipientName(), orderSpecialistName(order), requisites.bankName(), isManualPayment(link) ? requisites.paymentUrl() : "", manualButtonLabel(link), requisites.comment(), link.getManualReportedAt(), normalize(link.getManualConfirmedBy()), link.getManualConfirmedAt(), link.getReceiptStatus() == null ? null : link.getReceiptStatus().name(), link.getPaymentSuccessNotifiedAt(), normalize(link.getPaymentSuccessNotificationError()), clientChatPlatform(order), clientChatReady(order), clientChatWarning(order), link.getLastError(), link.getCreatedAt(), link.getUpdatedAt(), link.getExpiresAt(), link.getInitiatedAt(), link.getPaidAt(), link.getSbpQrCreatedAt(), false, null, null, isRefundable(link));
    }

    String clientChatPlatform(Order order) {
        Company company = order == null ? null : order.getCompany();
        String value = company == null ? "" : normalize(company.getUrlChat());
        if (value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.matches("^(?:https?://)?chat\\.whatsapp\\.com/.+")) {
            return "WHATSAPP";
        }
        if (normalized.matches("^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/.+") || normalized.startsWith("tg://resolve?")) {
            return "TELEGRAM";
        }
        if (normalized.matches("^(?:https?://)?(?:web\\.)?max\\.ru/.+")) {
            return "MAX";
        }
        return "UNKNOWN";
    }

    boolean clientChatReady(Order order) {
        Company company = order == null ? null : order.getCompany();
        return switch(clientChatPlatform(order)) {
            case "WHATSAPP" ->
                company != null && !normalize(company.getGroupId()).isBlank() && !normalize(clientChatManager(order, company).map(Manager::getClientId).orElse(null)).isBlank();
            case "TELEGRAM" ->
                company != null && company.getTelegramGroupChatId() != null;
            case "MAX" ->
                company != null && company.getMaxGroupChatId() != null;
            default ->
                false;
        };
    }

    String clientChatWarning(Order order) {
        Company company = order == null ? null : order.getCompany();
        String platform = clientChatPlatform(order);
        if ("UNKNOWN".equals(platform)) {
            return company == null || normalize(company.getUrlChat()).isBlank() ? "ссылка на чат не указана" : "ссылка на чат не распознана";
        }
        if (clientChatReady(order)) {
            return "";
        }
        return switch(platform) {
            case "WHATSAPP" ->
                {
                    boolean hasGroup = company != null && !normalize(company.getGroupId()).isBlank();
                    boolean hasClient = !normalize(clientChatManager(order, company).map(Manager::getClientId).orElse(null)).isBlank();
                    if (!hasGroup && !hasClient) {
                        yield "для WhatsApp нужны groupId компании и clientId менеджера";
                    }
                    yield hasGroup ? "для WhatsApp не задан clientId менеджера" : "для WhatsApp не задан groupId компании";
                }
            case "TELEGRAM" ->
                "для Telegram не сохранен chatId группы";
            case "MAX" ->
                "для MAX не сохранен chatId группы";
            default ->
                "чат не готов";
        };
    }

    Optional<Manager> clientChatManager(Order order, Company company) {
        if (order != null && order.getManager() != null) {
            return Optional.of(order.getManager());
        }
        return Optional.ofNullable(company == null ? null : company.getManager());
    }

    boolean isPayable(PaymentLink link, ManualRouteReadView requisites) {
        return !isPaperInvoice(link) && !link.getExpiresAt().isBefore(LocalDateTime.now()) && (!isManualPayment(link) || hasEffectiveManualPaymentTarget(link, requisites)) && link.getStatus() != PaymentLinkStatus.CONFIRMED && link.getStatus() != PaymentLinkStatus.AMOUNT_MISMATCH && link.getStatus() != PaymentLinkStatus.TEST_CONFIRMED && link.getStatus() != PaymentLinkStatus.CANCELED && link.getStatus() != PaymentLinkStatus.REVERSED && link.getStatus() != PaymentLinkStatus.PARTIAL_REVERSED && link.getStatus() != PaymentLinkStatus.REFUNDED && link.getStatus() != PaymentLinkStatus.PARTIAL_REFUNDED && link.getStatus() != PaymentLinkStatus.REJECTED && link.getStatus() != PaymentLinkStatus.EXPIRED && link.getStatus() != PaymentLinkStatus.AUTHORIZED && link.getStatus() != PaymentLinkStatus.NEEDS_RECONCILIATION && link.getStatus() != PaymentLinkStatus.FAILED;
    }

    boolean isRefundable(PaymentLink link) {
        if (link == null || normalize(link.getTbankPaymentId()).isBlank() || hasBankCancelReservation(link) || link.getBankCancelOriginStatus() != null) {
            return false;
        }
        if (isTochkaPaymentLink(link)) {
            return (link.getStatus() == PaymentLinkStatus.CONFIRMED || link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED || link.getStatus() == PaymentLinkStatus.AMOUNT_MISMATCH) && "APPROVED".equals(normalize(link.getProviderTerminalStatus()));
        }
        return REFUNDABLE_STATUSES.contains(link.getStatus());
    }

    PaymentProfile selectProfile(Order order) {
        return bankObservations.selectProfile(order);
    }

    ManualPaymentType manualPaymentType(PaymentProfile profile) {
        return commonInvoiceRouteSelector.manualPaymentType(profile);
    }

    ManualPaymentType manualPaymentType(ManualPaymentTask task) {
        return commonInvoiceRouteSelector.manualPaymentType(task);
    }

    boolean hasEffectiveManualPaymentTarget(PaymentLink link, ManualRouteReadView requisites) {
        if (isPaperInvoice(link)) {
            return false;
        }
        boolean external = link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK || link.getManualPaymentType() == ManualPaymentType.EXTERNAL_LINK;
        if (external) {
            return !requisites.paymentUrl().isBlank();
        }
        boolean mobileBank = link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK || link.getManualPaymentType() == ManualPaymentType.MOBILE_BANK;
        return !mobileBank || !requisites.phone().isBlank();
    }

    String manualButtonLabel(String value) {
        return commonInvoiceRouteSelector.manualButtonLabel(value);
    }

    String manualButtonLabel(PaymentLink link) {
        return commonInvoiceRouteSelector.manualButtonLabel(link);
    }

    String orderSpecialistName(Order order) {
        User user = order == null || order.getWorker() == null ? null : order.getWorker().getUser();
        String fio = normalize(user == null ? null : user.getFio());
        return fio.isBlank() ? normalize(user == null ? null : user.getUsername()) : fio;
    }

    ManualRouteReadView manualRouteReadView(PaymentLink link) {
        return commonInvoiceRouteSelector.manualRouteReadView(link);
    }

    String paymentProfileCode(PaymentLink link) {
        String profileCode = normalize(link.getPaymentProfileCode());
        if (!profileCode.isBlank()) {
            return profileCode;
        }
        return profileForDisplay(link).getCode();
    }

    String paymentProfileName(PaymentLink link) {
        String profileName = normalize(link.getPaymentProfileName());
        if (!profileName.isBlank()) {
            return profileName;
        }
        return profileForDisplay(link).getName();
    }

    String manualSourceName(PaymentLink link) {
        return commonInvoiceRouteSelector.manualSourceName(link);
    }

    String manualPaymentTypeName(PaymentLink link) {
        return commonInvoiceRouteSelector.manualPaymentTypeName(link);
    }

    ManualPaymentType manualPaymentType(PaymentLink link) {
        return commonInvoiceRouteSelector.manualPaymentType(link);
    }

    Long manualTaskId(PaymentLink link) {
        ManualPaymentTask task = link.getManualPaymentTask();
        return task == null ? null : task.getId();
    }

    String manualTaskTitle(PaymentLink link) {
        ManualPaymentTask task = link.getManualPaymentTask();
        if (task == null) {
            return "";
        }
        String recipient = normalize(task.getManualRecipientName());
        if (!recipient.isBlank()) {
            return recipient;
        }
        String label = normalize(task.getManualPaymentButtonLabel());
        return label.isBlank() ? "Ручное задание #" + task.getId() : label;
    }

    PaymentProfile profileForDisplay(PaymentLink link) {
        if (link.getPaymentProfile() != null) {
            return link.getPaymentProfile();
        }
        String terminalKey = normalize(link.getTbankTerminalKey());
        if (!terminalKey.isBlank()) {
            Optional<PaymentProfile> byTerminal = paymentProfileService.findByTerminalKey(terminalKey);
            if (byTerminal.isPresent()) {
                return byTerminal.get();
            }
        }
        String profileCode = normalize(link.getPaymentProfileCode());
        if (!profileCode.isBlank()) {
            Optional<PaymentProfile> byCode = paymentProfileService.findByCode(profileCode);
            if (byCode.isPresent()) {
                return byCode.get();
            }
        }
        return selectProfile(link.getOrder());
    }

    BigDecimal amountRubles(long amountKopecks) {
        return BigDecimal.valueOf(amountKopecks, 2);
    }

    String paymentCopyText(PaymentLink link, String url, ManualRouteReadView requisites) {
        if (isPaperInvoice(link)) {
            String text = "Здравствуйте, ваш заказ выполнен. К оплате: " + amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString() + " руб.\n\n" + paymentInstructionText(link, "", requisites) + "\n\n" + paymentAfterword(link);
            return withOrderHeading(link.getOrder(), text);
        }
        if (isDirectRecipientMobileBankPayment(link, requisites)) {
            return withOrderHeading(link == null ? null : link.getOrder(), directRecipientMobileBankPaymentCopyText(link, requisites));
        }
        String template = appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAYMENT_LINK_COPY_TEXT, ScheduledClientMessageService.DEFAULT_PAYMENT_LINK_COPY_TEXT);
        if (template == null || template.isBlank()) {
            template = ScheduledClientMessageService.DEFAULT_PAYMENT_LINK_COPY_TEXT;
        }
        String afterword = paymentAfterword(link);
        String text = renderPaymentTemplate(template, Map.ofEntries(Map.entry("company", companyTitle(link.getOrder())), Map.entry("filial", filialTitle(link.getOrder())), Map.entry("companyAndFilial", heading(link.getOrder())), Map.entry("sum", amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString()), Map.entry("paymentInstruction", paymentInstructionText(link, url, requisites)), Map.entry("paymentLink", paymentLinkValue(link, url, requisites)), Map.entry("tbankPaymentLink", url), Map.entry("recipient", requisites.recipientName()), Map.entry("comment", requisites.comment()), Map.entry("paymentAfterword", afterword), Map.entry("afterword", afterword)));
        String clientText = isManualPayment(link) ? text : removeReceiptRequest(text);
        return withOrderHeading(link == null ? null : link.getOrder(), clientText);
    }

    boolean isDirectRecipientMobileBankPayment(PaymentLink link, ManualRouteReadView requisites) {
        return isManualPayment(link) && manualPaymentType(link) == ManualPaymentType.MOBILE_BANK && requisites != null && requisites.available() && (requisites.contractorRoute() || link.getManualSource() == ManualPaymentSource.MANUAL_TASK);
    }

    String directRecipientMobileBankPaymentCopyText(PaymentLink link, ManualRouteReadView requisites) {
        StringBuilder text = new StringBuilder().append("Здравствуйте, ваш заказ выполнен. К оплате: ").append(amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString()).append(" руб.").append("\n\n").append(mobileBankPaymentLine(requisites.phone())).append('\n').append("Получатель: ").append(requisites.recipientName());
        String bankName = normalize(requisites.bankName());
        if (!bankName.isBlank()) {
            text.append('\n').append("Банк: ").append(bankName);
        }
        text.append("\n\n").append(paymentAfterword(link));
        return normalizeText(text.toString());
    }

    String paymentInstructionText(PaymentLink link, String url) {
        return commonInvoiceRouteSelector.paymentInstructionText(link, url);
    }

    String paymentInstructionText(PaymentLink link, String url, ManualRouteReadView requisites) {
        return commonInvoiceRouteSelector.paymentInstructionText(link, url, requisites);
    }

    String mobileBankPaymentLine(String rawValue) {
        return commonInvoiceRouteSelector.mobileBankPaymentLine(rawValue);
    }

    String paymentAfterword(PaymentLink link) {
        if (isPaperInvoice(link)) {
            return "После оплаты отправьте платёжное поручение в этот чат.";
        }
        if (!isManualPayment(link)) {
            return "";
        }
        return "После оплаты отправьте чек в этот чат.";
    }

    String removeReceiptRequest(String text) {
        return normalizeText(text.replaceAll("(?iu)\\n?\\s*После оплаты отправьте чек в этот чат\\.?", "").replaceAll("(?iu)\\n?\\s*Пришлите чек,? пожалуйста,? как оплатите\\.?", ""));
    }

    String paymentLinkValue(PaymentLink link, String url, ManualRouteReadView requisites) {
        if (isPaperInvoice(link)) {
            return "";
        }
        if (!isManualPayment(link)) {
            return url;
        }
        if (manualPaymentType(link) == ManualPaymentType.EXTERNAL_LINK) {
            return requisites.paymentUrl();
        }
        return requisites.phone();
    }

    String renderPaymentTemplate(String template, Map<String, String> variables) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result.replace("\r\n", "\n").replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    String normalizeText(String result) {
        return (result == null ? "" : result).replace("\r\n", "\n").replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    String withOrderHeading(Order order, String text) {
        String cleanText = normalizeText(text);
        String orderHeading = heading(order);
        if (orderHeading.isBlank() || cleanText.equals(orderHeading) || cleanText.startsWith(orderHeading + "\n")) {
            return cleanText;
        }
        return normalizeText(orderHeading + "\n\n" + cleanText);
    }

    String heading(Order order) {
        String company = companyTitle(order);
        String filial = filialTitle(order);
        if (company.isBlank()) {
            return filial;
        }
        if (filial.isBlank()) {
            return company;
        }
        return company + " - " + filial;
    }

    String companyTitle(Order order) {
        Company company = order == null ? null : order.getCompany();
        return company == null ? "" : normalize(company.getTitle());
    }

    String filialTitle(Order order) {
        Filial filial = order == null ? null : order.getFilial();
        return filial == null ? "" : normalize(filial.getTitle());
    }

    String publicPaymentUrl(PaymentLink link) {
        return properties.getPublicBaseUrl() + "/pay/" + link.getToken();
    }

    String paymentMethodName(PaymentLink link) {
        return link.getPaymentMethod() == null ? PaymentMethod.BANK_FORM.name() : link.getPaymentMethod().name();
    }

    boolean isManualPayment(PaymentLink link) {
        return commonInvoiceRouteSelector.isManualPayment(link);
    }

    String normalize(String value) {
        return commonInvoiceRouteSelector.normalize(value);
    }
}
