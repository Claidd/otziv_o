package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.model.PaymentLink;
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

    private final ClientChatMessageSender messageSender;
    private final TbankPaymentProperties properties;
    private final AppSettingService appSettingService;

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
        return messageSender.send(company, clientId, groupId, buildMessage(link, order, company));
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

        return renderTemplate(paymentSuccessTemplate(), variables);
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

    private String paymentSuccessTemplate() {
        return appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_SUCCESS_TEXT,
                DEFAULT_PAYMENT_SUCCESS_TEXT
        );
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
