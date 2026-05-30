package com.hunt.otziv.p_products.status;

import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.client_messages.service.ScheduledClientMessageService.DEFAULT_PUBLICATION_STARTED_TEXT;
import static com.hunt.otziv.client_messages.service.ScheduledClientMessageService.DEFAULT_REVIEW_LINK_BASE_URL;
import static com.hunt.otziv.client_messages.service.ScheduledClientMessageService.DEFAULT_REVIEW_REMINDER_TEXT;

@Service
@RequiredArgsConstructor
public class OrderReviewCheckMessageBuilder {

    private final AppSettingService appSettingService;

    public String reviewCheckMessage(Order order) {
        return renderOrderTemplate(
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_REVIEW_REMINDER_TEXT,
                        DEFAULT_REVIEW_REMINDER_TEXT
                ),
                order,
                Map.of("reviewLink", reviewLink(order))
        );
    }

    public String publicationStartedMessage(Order order) {
        return renderOrderTemplate(
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_PUBLICATION_STARTED_TEXT,
                        DEFAULT_PUBLICATION_STARTED_TEXT
                ),
                order,
                Map.of()
        );
    }

    public String reviewLink(Order order) {
        return firstDetail(order)
                .map(detail -> reviewLinkBaseUrl() + "/" + detail.getId())
                .orElse(reviewLinkBaseUrl());
    }

    private Optional<OrderDetails> firstDetail(Order order) {
        return order == null || order.getDetails() == null || order.getDetails().isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(order.getDetails().getFirst());
    }

    private String reviewLinkBaseUrl() {
        String value = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_REVIEW_LINK_BASE_URL,
                DEFAULT_REVIEW_LINK_BASE_URL
        ).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return hasText(value) ? value : DEFAULT_REVIEW_LINK_BASE_URL;
    }

    private String renderOrderTemplate(String template, Order order, Map<String, String> extraVariables) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("company", companyTitle(order));
        variables.put("filial", filialTitle(order));
        variables.put("companyAndFilial", companyAndFilial(order));
        variables.put("sum", money(order == null ? null : order.getSum()));
        variables.putAll(extraVariables);
        return renderTemplate(template, variables);
    }

    private String renderTemplate(String template, Map<String, String> variables) {
        String result = hasText(template) ? template : "";
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result.trim();
    }

    private String companyAndFilial(Order order) {
        String filial = filialTitle(order);
        return filial.isBlank() ? companyTitle(order) : companyTitle(order) + ". " + filial;
    }

    private String companyTitle(Order order) {
        return order == null || order.getCompany() == null || order.getCompany().getTitle() == null
                ? "Компания"
                : order.getCompany().getTitle();
    }

    private String filialTitle(Order order) {
        return order == null || order.getFilial() == null || order.getFilial().getTitle() == null
                ? ""
                : order.getFilial().getTitle();
    }

    private String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
