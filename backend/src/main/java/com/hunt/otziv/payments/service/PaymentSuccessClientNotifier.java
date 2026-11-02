package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.client_messages.service.ScheduledClientMessageService.DEFAULT_PAYMENT_SUCCESS_TEXT;

@Service
@RequiredArgsConstructor
public class PaymentSuccessClientNotifier {

    private static final String DEFAULT_MANUAL_PAYMENT_SUCCESS_TEXT = String.join("\n",
            "Оплата по реквизитам подтверждена.",
            "",
            "Менеджер сверил перевод по реквизитам. Заказ принят в работу.",
            "{orderLine}{companyLine}Сумма: {sum}",
            "",
            "{receiptText}"
    );

    private final ClientChatMessageSender messageSender;
    private final TbankPaymentProperties properties;
    private final AppSettingService appSettingService;
    private final com.hunt.otziv.client_messages.api.NotificationFollowup followups;

    public com.hunt.otziv.client_messages.api.NotificationFollowup.Outcome recoverWithFollowup(PaymentLink link,
            java.util.function.BooleanSupplier stillEligible) {
        if (!appSettingService.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED,true)
                || link == null || link.getId() == null || link.getPaidAt() == null
                || link.getStatus() != com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED
                || link.getOrder() == null || link.getOrder().getCompany() == null) return null;
        var order=link.getOrder(); var company=order.getCompany();
        String source=followupSourceSnapshot(link);
        String text="Здравствуйте! Повторно подтверждаем: оплата по заказу №"+order.getId()+" ("+normalize(company.getTitle())
                +") на сумму "+amountText(link)+" получена ранее. Спасибо!\n\nЭто повторное уведомление о поступившей оплате. Повторно оплачивать этот заказ не нужно.";
        var request=new com.hunt.otziv.client_messages.api.NotificationFollowup.Request(
                com.hunt.otziv.client_messages.api.NotificationFollowup.Kind.PAYMENT_SUCCESS,link.getId(),
                com.hunt.otziv.client_messages.api.NotificationFollowup.fingerprint(source),"payment-success:"+link.getId(),
                link.getPaidAt(),new com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target(company.getId(),company.getTitle(),
                company.getUrlChat(),company.getTelegramGroupChatId(),company.getMaxGroupChatId()),clientId(order,company),text,source);
        return followups.recover(request,()->appSettingService.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED,true)
                && stillEligible.getAsBoolean());
    }

    String followupSourceSnapshot(PaymentLink link) {
        var order=link.getOrder(); var company=order==null?null:order.getCompany();
        Map<String,String> source=new LinkedHashMap<>();
        source.put("payment",String.valueOf(link.getId())); source.put("status",String.valueOf(link.getStatus()));
        source.put("paidAt",String.valueOf(link.getPaidAt())); source.put("amount",amountText(link));
        source.put("order",String.valueOf(order==null?null:order.getId()));
        source.put("manager",String.valueOf(order==null||order.getManager()==null?null:order.getManager().getId()));
        source.put("company",String.valueOf(company==null?null:company.getId()));
        source.put("title",company==null?"":normalize(company.getTitle()));
        source.put("invite",company==null?"":normalize(company.getUrlChat())); source.put("client",clientId(order,company));
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(source); }
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){ throw new IllegalStateException(failure); }
    }

    public ClientMessageSendResult notifySuccess(PaymentLink link) {
        if (link == null) {
            return ClientMessageSendResult.failed("payment_link_missing", "Платежная ссылка не найдена");
        }

        Order order = link.getOrder();
        Company company = order == null ? null : order.getCompany();
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)) {
            return ClientMessageSendResult.failed(
                    "immediate_messages_disabled",
                    "Моментальные клиентские сообщения выключены"
            );
        }
        String clientId = clientId(order, company);
        String groupId = company == null ? "" : normalize(company.getGroupId());
        if (link.getId() == null) {
            return ClientMessageSendResult.failed("payment_link_missing", "Платёжная операция ещё не сохранена");
        }
        if (groupId.isBlank() && company != null) {
            String recovered = messageSender.recoverMissingWhatsAppGroup(
                    new com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target(company.getId(), company.getTitle(),
                            company.getUrlChat(), company.getTelegramGroupChatId(), company.getMaxGroupChatId()),
                    clientId, groupId, "payment-success:" + link.getId());
            if (recovered != null && !recovered.isBlank()) groupId = recovered;
        }
        var result = messageSender.sendWithOperationId(company, clientId, groupId,
                buildMessage(link, order, company), null, "payment-success:" + link.getId());
        if (result != null && result.sent() && result.messageId() != null && !result.messageId().isBlank()) return result;
        if (ClientChatMessageSender.isKnownUnsent(result)) return result;
        // A retry must also search the original frozen operation's history.
        // UNKNOWN never authorizes a replacement ID, route or second message.
        var recovered = messageSender.recoverRecordedOutcomeFromChat("payment-success:" + link.getId(), link.getPaidAt());
        return recovered == null ? ClientMessageSendResult.failed("operation_unknown", "Результат уведомления об оплате пока не подтверждён") : recovered;
    }

    private String buildMessage(PaymentLink link, Order order, Company company) {
        Long orderId = order == null ? null : order.getId();
        String companyTitle = company == null ? "" : normalize(company.getTitle());
        String filialTitle = order == null || order.getFilial() == null ? "" : normalize(order.getFilial().getTitle());
        String payerEmail = normalize(link.getPayerEmail());

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("orderId", orderId == null ? "" : String.valueOf(orderId));
        variables.put("orderLine", orderId == null ? "" : "Заказ №" + orderId + "\n");
        variables.put("company", companyTitle);
        variables.put("companyLine", companyTitle.isBlank() ? "" : "Компания: " + companyTitle + "\n");
        variables.put("filial", filialTitle);
        variables.put("companyAndFilial", companyAndFilial(companyTitle, filialTitle));
        variables.put("sum", amountText(link));
        variables.put("paymentPage", publicPaymentUrl(link));
        variables.put("payerEmail", payerEmail);
        variables.put("receiptText", receiptText(payerEmail));

        return renderTemplate(paymentSuccessTemplate(link), variables);
    }

    private String clientId(Order order, Company company) {
        Manager manager = order != null && order.getManager() != null ? order.getManager() : companyManager(company);
        return manager == null ? "" : normalize(manager.getClientId());
    }

    private Manager companyManager(Company company) {
        return company == null ? null : company.getManager();
    }

    private String amountText(PaymentLink link) {
        long amountKopecks = link.getConfirmedAmountKopecks() == null
                ? link.getAmountKopecks()
                : link.getConfirmedAmountKopecks();
        BigDecimal amount = BigDecimal.valueOf(amountKopecks, 2).stripTrailingZeros();
        return amount.toPlainString().replace('.', ',') + " ₽";
    }

    private String publicPaymentUrl(PaymentLink link) {
        String token = normalize(link.getToken());
        String baseUrl = normalize(properties.getPublicBaseUrl());
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl.isBlank() ? "/pay/" + token : baseUrl + "/pay/" + token;
    }

    private String paymentSuccessTemplate(PaymentLink link) {
        if (link.getPaymentMethod() == PaymentMethod.OWNER_PAPER_INVOICE) {
            return "Оплата по банковскому счёту получена.\n{orderLine}{companyLine}Сумма: {sum}\nСпасибо!";
        }
        String configuredTemplate = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_SUCCESS_TEXT,
                DEFAULT_PAYMENT_SUCCESS_TEXT
        );
        if (isManualPayment(link)
                && (configuredTemplate == null
                || configuredTemplate.isBlank()
                || DEFAULT_PAYMENT_SUCCESS_TEXT.equals(configuredTemplate.trim()))) {
            return DEFAULT_MANUAL_PAYMENT_SUCCESS_TEXT;
        }
        return configuredTemplate;
    }

    private boolean isManualPayment(PaymentLink link) {
        PaymentMethod method = link == null ? null : link.getPaymentMethod();
        return method == PaymentMethod.MANUAL_MOBILE_BANK || method == PaymentMethod.MANUAL_EXTERNAL_LINK;
    }

    private String companyAndFilial(String companyTitle, String filialTitle) {
        if (companyTitle.isBlank()) {
            return filialTitle;
        }
        return filialTitle.isBlank() ? companyTitle : companyTitle + " - " + filialTitle;
    }

    private String receiptText(String payerEmail) {
        return payerEmail.isBlank()
                ? "Чек будет отправлен на e-mail."
                : "Чек будет отправлен на e-mail: " + payerEmail + ".";
    }

    private String renderTemplate(String template, Map<String, String> variables) {
        String result = template == null || template.isBlank() ? DEFAULT_PAYMENT_SUCCESS_TEXT : template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result
                .replace("\r\n", "\n")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
