package com.hunt.otziv.client_messages;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.status.OrderReviewCheckMessageBuilder;
import com.hunt.otziv.u_users.model.Manager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ClientMessagePreviewService {

    private static final String DEFAULT_PAYMENT_INSTRUCTION_SOURCE = "MANAGER_TEXT";
    private static final String DEFAULT_CLIENT_TEXT_REMINDER_TEXT = "{companyAndFilial}\n\nЗдравствуйте! Напоминаем, пожалуйста, пришлите текст или пожелания для отзывов по заказу №{orderId}, чтобы мы могли продолжить работу.";
    private static final String DEFAULT_PAYMENT_REMINDER_TEXT = "{companyAndFilial}\n\n{managerPayText} К оплате: {sum} руб.";
    private static final String DEFAULT_REVIEW_RECOVERY_NOTICE_TEXT = "{companyAndFilial}\n\nОтзыв восстановлен. Продолжаем работу по заказу №{orderId}.";
    private static final String DEFAULT_ARCHIVE_OFFER_TEXT = "{company}\n\nЗдравствуйте! Давно не запускали новый заказ. Можем подготовить новую аккуратную серию отзывов и обновить карточку компании. Если актуально, напишите, пожалуйста, сколько отзывов нужно в этот раз.";
    private static final int PREVIEW_LIMIT = 700;

    private final AppSettingService appSettingService;
    private final OrderReviewCheckMessageBuilder reviewCheckMessageBuilder;
    private final BadReviewTaskService badReviewTaskService;

    public ClientMessagePreview preview(ScheduledClientMessageState state, Order order, Company company) {
        Company resolvedCompany = order != null && order.getCompany() != null ? order.getCompany() : company;
        String expectedChannel = expectedChannel(resolvedCompany);
        String paymentSource = paymentInstructionSource();
        return switch (state.getScenario()) {
            case CLIENT_TEXT_REMINDER -> new ClientMessagePreview(
                    expectedChannel,
                    channelDetails(resolvedCompany, manager(order, resolvedCompany)),
                    paymentSource,
                    limit(clientTextReminderText(order), PREVIEW_LIMIT)
            );
            case REVIEW_CHECK_REMINDER -> new ClientMessagePreview(
                    expectedChannel,
                    channelDetails(resolvedCompany, manager(order, resolvedCompany)),
                    paymentSource,
                    limit(reviewCheckMessageBuilder.reviewCheckMessage(order), PREVIEW_LIMIT)
            );
            case REVIEW_CHECK_DELIVERY_RETRY -> new ClientMessagePreview(
                    expectedChannel,
                    channelDetails(resolvedCompany, manager(order, resolvedCompany)),
                    paymentSource,
                    limit(reviewCheckMessageBuilder.reviewCheckMessage(order), PREVIEW_LIMIT)
            );
            case REVIEW_CHECK_AUTO_ARCHIVE -> new ClientMessagePreview(
                    "system",
                    "Статус будет изменен системой без сообщения клиенту",
                    paymentSource,
                    limit("Кандидат на автоархив проверки: заказ #" + nullSafe(state.getOrderId()), PREVIEW_LIMIT)
            );
            case PAYMENT_INVOICE_RETRY -> new ClientMessagePreview(
                    expectedChannel,
                    channelDetails(resolvedCompany, manager(order, resolvedCompany)),
                    paymentSource,
                    limit(paymentReminderText(order), PREVIEW_LIMIT)
            );
            case PAYMENT_REMINDER -> new ClientMessagePreview(
                    expectedChannel,
                    channelDetails(resolvedCompany, manager(order, resolvedCompany)),
                    paymentSource,
                    limit(paymentReminderText(order), PREVIEW_LIMIT)
            );
            case ARCHIVE_REORDER_OFFER -> new ClientMessagePreview(
                    expectedChannel,
                    channelDetails(resolvedCompany, manager(order, resolvedCompany)),
                    paymentSource,
                    limit(archiveOfferText(resolvedCompany), PREVIEW_LIMIT)
            );
            case PAYMENT_OVERDUE_ESCALATION -> new ClientMessagePreview(
                    "system",
                    "Статус будет изменен системой без сообщения клиенту",
                    paymentSource,
                    limit("Кандидат на перевод в " + paymentOverdueTargetStatus() + ": заказ #" + nullSafe(state.getOrderId()), PREVIEW_LIMIT)
            );
            case BAD_REVIEW_INVOICE -> new ClientMessagePreview(
                    expectedChannel,
                    channelDetails(resolvedCompany, manager(order, resolvedCompany)),
                    paymentSource,
                    limit(paymentReminderText(order), PREVIEW_LIMIT)
            );
            case BAD_REVIEW_AUTO_BAN -> new ClientMessagePreview(
                    "system",
                    "Статус будет изменен системой без сообщения клиенту",
                    paymentSource,
                    limit("Кандидат на Бан после плохих отзывов: заказ #" + nullSafe(state.getOrderId()), PREVIEW_LIMIT)
            );
            case REVIEW_RECOVERY_NOTICE -> new ClientMessagePreview(
                    expectedChannel,
                    channelDetails(resolvedCompany, manager(order, resolvedCompany)),
                    paymentSource,
                    limit(reviewRecoveryNoticeText(order), PREVIEW_LIMIT)
            );
        };
    }

    private String clientTextReminderText(Order order) {
        return renderOrderTemplate(
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_TEXT,
                        DEFAULT_CLIENT_TEXT_REMINDER_TEXT
                ),
                order,
                Map.of()
        );
    }

    private String paymentReminderText(Order order) {
        String template = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_TEXT,
                DEFAULT_PAYMENT_REMINDER_TEXT
        );
        String managerPayText = managerPayText(order);
        String paymentInstruction = managerPayText;
        String paymentLink = "";
        String tbankPaymentCopyText = "";
        if (requiresTbankPaymentLink(template)) {
            paymentLink = "[T-Bank ссылка будет создана при отправке]";
            tbankPaymentCopyText = "[текст для оплаты будет создан при отправке]";
            if (usesTbankPaymentInstructionSource() && isDefaultPaymentReminderTemplate(template)) {
                return renderOrderTemplate(
                        "{companyAndFilial}\n\n{tbankPaymentCopyText}",
                        order,
                        Map.of("tbankPaymentCopyText", tbankPaymentCopyText)
                );
            }
            if (usesTbankPaymentInstructionSource()) {
                paymentInstruction = "Ссылка на оплату: " + paymentLink;
            }
        }

        return renderOrderTemplate(
                template,
                order,
                Map.of(
                        "managerPayText", paymentInstruction,
                        "legacyManagerPayText", managerPayText,
                        "paymentInstruction", paymentInstruction,
                        "paymentLink", paymentLink,
                        "tbankPaymentLink", paymentLink,
                        "tbankPaymentCopyText", tbankPaymentCopyText
                )
        );
    }

    private String reviewRecoveryNoticeText(Order order) {
        return renderOrderTemplate(
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_TEXT,
                        DEFAULT_REVIEW_RECOVERY_NOTICE_TEXT
                ),
                order,
                Map.of()
        );
    }

    private String archiveOfferText(Company company) {
        String title = company == null || company.getTitle() == null || company.getTitle().isBlank()
                ? "вашей компании"
                : company.getTitle();
        return renderTemplate(
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_ARCHIVE_OFFER_TEXT,
                        DEFAULT_ARCHIVE_OFFER_TEXT
                ),
                Map.of("company", title)
        );
    }

    private boolean requiresTbankPaymentLink(String template) {
        return usesTbankPaymentInstructionSource()
                || containsVariable(template, "paymentLink")
                || containsVariable(template, "tbankPaymentLink")
                || containsVariable(template, "tbankPaymentCopyText");
    }

    private boolean isDefaultPaymentReminderTemplate(String template) {
        return DEFAULT_PAYMENT_REMINDER_TEXT.equals(template);
    }

    private boolean usesTbankPaymentInstructionSource() {
        return "TBANK_LINK".equals(paymentInstructionSource());
    }

    private String paymentInstructionSource() {
        String value = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                DEFAULT_PAYMENT_INSTRUCTION_SOURCE
        );
        return "TBANK_LINK".equals((value == null ? "" : value.trim()).toUpperCase(Locale.ROOT))
                ? "TBANK_LINK"
                : DEFAULT_PAYMENT_INSTRUCTION_SOURCE;
    }

    private String paymentOverdueTargetStatus() {
        return appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_TARGET_STATUS,
                ScheduledClientMessageService.DEFAULT_PAYMENT_OVERDUE_TARGET_STATUS
        );
    }

    private String managerPayText(Order order) {
        return order != null && order.getManager() != null && hasText(order.getManager().getPayText())
                ? order.getManager().getPayText().trim()
                : "Здравствуйте, напоминаем об оплате выполненного заказа. Пришлите чек, пожалуйста, как оплатите.";
    }

    private Manager manager(Order order, Company company) {
        if (order != null && order.getManager() != null) {
            return order.getManager();
        }
        return company == null ? null : company.getManager();
    }

    private String expectedChannel(Company company) {
        if (company == null || !hasText(company.getUrlChat())) {
            return "не распознан";
        }
        String normalized = company.getUrlChat().trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("^(?:https?://)?chat\\.whatsapp\\.com/.+")) {
            return "WhatsApp";
        }
        if (normalized.matches("^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/.+")
                || normalized.startsWith("tg://resolve?")) {
            return "Telegram";
        }
        if (normalized.matches("^(?:https?://)?(?:web\\.)?max\\.ru/.+")) {
            return "MAX";
        }
        return "не распознан";
    }

    private String channelDetails(Company company, Manager manager) {
        String channel = expectedChannel(company);
        if ("WhatsApp".equals(channel)) {
            return "WhatsApp clientId=" + nullSafe(manager == null ? null : manager.getClientId())
                    + ", groupId=" + nullSafe(company == null ? null : company.getGroupId());
        }
        if ("Telegram".equals(channel)) {
            return "Telegram chatId=" + nullSafe(company == null ? null : company.getTelegramGroupChatId());
        }
        if ("MAX".equals(channel)) {
            return "MAX chatId=" + nullSafe(company == null ? null : company.getMaxGroupChatId());
        }
        return "Ссылка на чат не указана или не распознана";
    }

    private String renderOrderTemplate(String template, Order order, Map<String, String> extraVariables) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("company", order == null || order.getCompany() == null || order.getCompany().getTitle() == null
                ? "Компания"
                : order.getCompany().getTitle());
        variables.put("filial", order == null || order.getFilial() == null || order.getFilial().getTitle() == null
                ? ""
                : order.getFilial().getTitle());
        variables.put("companyAndFilial", companyAndFilial(order));
        variables.put("orderId", order == null || order.getId() == null ? "" : order.getId().toString());
        variables.put("sum", money(payableSum(order)));
        variables.putAll(extraVariables);
        return renderTemplate(template, variables);
    }

    private BigDecimal payableSum(Order order) {
        if (order == null) {
            return BigDecimal.ZERO;
        }
        try {
            return badReviewTaskService.getPayableSum(order);
        } catch (RuntimeException e) {
            return order.getSum() == null ? BigDecimal.ZERO : order.getSum();
        }
    }

    private String renderTemplate(String template, Map<String, String> variables) {
        String result = hasText(template) ? template : "";
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result.trim();
    }

    private String companyAndFilial(Order order) {
        String company = order == null || order.getCompany() == null || order.getCompany().getTitle() == null
                ? "Компания"
                : order.getCompany().getTitle();
        String filial = order == null || order.getFilial() == null || order.getFilial().getTitle() == null
                ? ""
                : order.getFilial().getTitle();
        return filial.isBlank() ? company : company + ". " + filial;
    }

    private boolean containsVariable(String template, String variable) {
        return template != null && template.contains("{" + variable + "}");
    }

    private String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String nullSafe(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
