package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.common_billing.dto.CommonBillingCompanyResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRequisitesSnapshot;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.u_users.model.Worker;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoicePresenter {

    private final CommonInvoiceSettlementService settlementService;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    private final PaymentProfileService paymentProfileService;

    private final TbankPaymentProperties properties;

    String commonInvoiceRouteLabel(CommonInvoice invoice) {
        if (isTbankCommonRoute(invoice)) {
            return "Банковская ссылка владельца";
        }
        if (isManualMobileBankCommonRoute(invoice)) {
            return "Оплата по реквизитам";
        }
        return normalize(invoice == null ? null : invoice.getPaymentRouteType()).isBlank() ? "Не сформирован" : normalize(invoice.getPaymentRouteType());
    }

    String commonInvoiceRouteRecipient(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (isTbankCommonRoute(invoice)) {
            String provider = normalize(frozenPaymentRouteProvider(invoice)).toUpperCase(Locale.ROOT);
            String providerLabel = PROVIDER_TOCHKA.equals(provider) ? "Точка Банк" : PROVIDER_TBANK.equals(provider) ? "T‑Bank" : "Банк владельца";
            String profileName = normalize(invoice == null ? null : invoice.getPaymentRouteProfileName());
            if (!profileName.isBlank()) {
                return providerLabel + " · " + profileName;
            }
            return provider.isBlank() ? "Владелец" : providerLabel;
        }
        if (!isManualMobileBankCommonRoute(invoice)) {
            return normalize(invoice == null ? null : invoice.getPaymentRouteProfileName());
        }
        String specialist = commonInvoiceSpecialistNames(items);
        String recipient = "";
        if (invoice != null && invoice.getContractorAllocationId() != null && invoice.getPaymentRouteManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            try {
                recipient = normalize(requiredPreparedCommonContractorRequisites(invoice).recipientName());
            } catch (RuntimeException ignored) {
                // Context remains readable; mutation still performs strict locked validation.
            }
        } else {
            recipient = normalize(invoice == null ? null : invoice.getPaymentRouteManualRecipient());
        }
        if (recipient.isBlank()) {
            recipient = specialist;
        }
        if (recipient.isBlank()) {
            recipient = normalize(invoice == null ? null : invoice.getPaymentRouteProfileName());
        }
        if (specialist.isBlank()) {
            return recipient;
        }
        if (recipient.equalsIgnoreCase(specialist)) {
            return recipient + " (специалист заказа)";
        }
        return recipient + " · специалист заказа: " + specialist;
    }

    String commonInvoiceSpecialistNames(List<CommonInvoiceOrder> items) {
        List<String> names = (items == null ? List.<CommonInvoiceOrder>of() : items).stream().map(CommonInvoiceOrder::getOrder).filter(Objects::nonNull).map(Order::getWorker).filter(Objects::nonNull).map(this::commonInvoiceSpecialistName).filter(name -> !name.isBlank()).distinct().toList();
        return String.join(", ", names);
    }

    String commonInvoiceSpecialistName(Worker worker) {
        if (worker == null) {
            return "";
        }
        String name = normalize(worker.getUser() == null ? null : worker.getUser().getFio());
        if (name.isBlank()) {
            name = normalize(worker.getUser() == null ? null : worker.getUser().getUsername());
        }
        if (name.isBlank() && worker.getId() != null) {
            name = "Специалист #" + worker.getId();
        }
        return name;
    }

    boolean isFrozenLiveContractorSource(CommonInvoice invoice) {
        return settlementService.isFrozenLiveContractorSource(invoice);
    }

    CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return toInvoiceSummary(invoice, items, null);
    }

    CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items, String tbankTerminalLabel) {
        long remaining = remainingKopecks(invoice);
        return new CommonInvoiceSummaryResponse(invoice.getId(), invoice.getAccount().getId(), invoice.getAccount().getName(), invoice.getTitle(), invoice.getToken(), publicInvoiceUrl(invoice), invoice.getStatus().name(), items.size(), (int) items.stream().filter(CommonInvoiceOrder::isReady).count(), (int) items.stream().filter(CommonInvoiceOrder::isPaid).count(), amountRubles(invoice.getAmountKopecks()), amountRubles(invoice.getPaidKopecks()), amountRubles(remaining), invoice.getAmountKopecks(), invoice.getPaidKopecks(), remaining, invoice.getSentAt(), invoice.getLastReminderAt(), invoice.getNextReminderAt(), invoice.getClosedAt(), invoice.getClosedBy(), invoice.getCloseReason(), invoice.getLastError(), invoice.getPaymentSuccessNotificationError(), invoice.getTbankOrderId(), invoice.getTbankPaymentId(), invoice.getTbankPaymentAmountKopecks(), normalize(tbankTerminalLabel).isBlank() ? null : tbankTerminalLabel, normalize(invoice.getTbankTerminalKey()).isBlank() ? null : invoice.getTbankTerminalKey(), normalize(invoice.getPaymentRouteType()), frozenPaymentRouteProvider(invoice), normalize(invoice.getPaymentRouteProfileName()), commonInvoiceRouteRecipient(invoice, items), invoice.getPaymentRouteManualTaskId(), isFrozenLiveContractorSource(invoice), invoice.getPaymentRouteSelectedAt(), normalize(invoice.getInvoicePurpose()), invoice.getSupersedesInvoice() == null ? null : invoice.getSupersedesInvoice().getId(), (invoice.getInvoicePaymentMode() == null ? InvoicePaymentMode.AUTO_ROUTING : invoice.getInvoicePaymentMode()).name(), invoice.getPaperInvoiceIssuedAt());
    }

    String frozenPaymentRouteProvider(CommonInvoice invoice) {
        if (invoice == null || !isTbankCommonRoute(invoice)) {
            return null;
        }
        Long frozenProfileId = invoice.getPaymentRouteProfileId();
        if (frozenProfileId != null) {
            return paymentProfileService.findById(frozenProfileId).map(paymentProfileService::provider).orElse(null);
        }
        String routeType = normalize(invoice.getPaymentRouteType()).toUpperCase(Locale.ROOT);
        if (TbankRuntimeSettingsService.PAYMENT_SOURCE_TOCHKA_LINK.equals(routeType)) {
            return PROVIDER_TOCHKA;
        }
        if (TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK.equals(routeType) || !normalize(invoice.getTbankTerminalKey()).isBlank() || !normalize(invoice.getTbankOrderId()).isBlank() || !normalize(invoice.getTbankPaymentId()).isBlank()) {
            return PROVIDER_TBANK;
        }
        return null;
    }

    CommonBillingCompanyResponse toCompanyResponse(CommonBillingAccountCompany link) {
        return new CommonBillingCompanyResponse(link.getCompany().getId(), link.getCompany().getTitle(), link.isEnabled());
    }

    ContractorPaymentRequisitesSnapshot requiredPreparedCommonContractorRequisites(CommonInvoice invoice) {
        return contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(invoice, remainingKopecks(invoice)).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Реквизиты получателя общего счета не удалось безопасно получить; сообщение не отправлено"));
    }

    boolean isManagerTextCommonRoute(CommonInvoice invoice) {
        return TbankRuntimeSettingsService.PAYMENT_SOURCE_MANAGER_TEXT.equals(normalize(invoice == null ? null : invoice.getPaymentRouteType()).toUpperCase(Locale.ROOT));
    }

    boolean isManualMobileBankCommonRoute(CommonInvoice invoice) {
        if (invoice == null || invoice.getPaymentRouteManualType() != ManualPaymentType.MOBILE_BANK) {
            return false;
        }
        return PaymentMethod.MANUAL_MOBILE_BANK.name().equals(normalize(invoice.getPaymentRouteType()).toUpperCase(Locale.ROOT)) || isManagerTextCommonRoute(invoice);
    }

    boolean isTbankCommonRoute(CommonInvoice invoice) {
        String routeType = normalize(invoice == null ? null : invoice.getPaymentRouteType()).toUpperCase(Locale.ROOT);
        return TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK.equals(routeType) || TbankRuntimeSettingsService.PAYMENT_SOURCE_BANK_LINK.equals(routeType) || "TOCHKA_LINK".equals(routeType);
    }

    BigDecimal amountRubles(long kopecks) {
        return settlementService.amountRubles(kopecks);
    }

    long remainingKopecks(CommonInvoice invoice) {
        return settlementService.remainingKopecks(invoice);
    }

    String publicInvoiceUrl(CommonInvoice invoice) {
        return trimTrailingSlash(properties.getPublicBaseUrl()) + "/pay/group/" + invoice.getToken();
    }

    String trimTrailingSlash(String value) {
        String result = normalize(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }
}
