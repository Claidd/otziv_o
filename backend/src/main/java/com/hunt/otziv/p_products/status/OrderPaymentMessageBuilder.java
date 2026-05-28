package com.hunt.otziv.p_products.status;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.PaymentLinkService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class OrderPaymentMessageBuilder {

    private static final String PAYMENT_SOURCE_TBANK_LINK = "TBANK_LINK";
    private static final String PAYMENT_SOURCE_MANAGER_TEXT = "MANAGER_TEXT";

    private final AppSettingService appSettingService;
    private final ObjectProvider<PaymentLinkService> paymentLinkServiceProvider;
    private final BadReviewTaskService badReviewTaskService;

    public String publishedOrderPaymentMessage(Order order) {
        if (usesTbankPaymentInstructionSource()) {
            return paymentLinkCopyText(order);
        }
        String heading = orderHeading(order);
        String paymentText = paymentInstruction(order) + " К оплате: " + money(payableSum(order)) + " руб.";
        return heading.isBlank() ? paymentText : heading + "\n\n" + paymentText;
    }

    private String paymentInstruction(Order order) {
        return managerPayText(order);
    }

    private String paymentLinkCopyText(Order order) {
        try {
            ManagerPaymentLinkResponse link = paymentLinkServiceProvider.getObject().createForOrder(order.getId());
            return link.copyText();
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не удалось подготовить ссылку T-Bank для заказа #" + (order == null ? "-" : order.getId()) + ": " + readableException(e),
                    e
            );
        }
    }

    private boolean usesTbankPaymentInstructionSource() {
        String source = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                PAYMENT_SOURCE_MANAGER_TEXT
        );
        return PAYMENT_SOURCE_TBANK_LINK.equals((source == null ? "" : source.trim()).toUpperCase(Locale.ROOT));
    }

    private String managerPayText(Order order) {
        String payText = order != null && order.getManager() != null ? order.getManager().getPayText() : null;
        return payText == null || payText.trim().isEmpty()
                ? "Здравствуйте, ваш заказ выполнен, просьба оплатить. Пришлите чек, пожалуйста, как оплатите."
                : payText.trim();
    }

    private String orderHeading(Order order) {
        if (order == null) {
            return "";
        }
        String company = order.getCompany() == null || order.getCompany().getTitle() == null
                ? "Компания"
                : order.getCompany().getTitle().trim();
        String filial = order.getFilial() == null || order.getFilial().getTitle() == null
                ? ""
                : order.getFilial().getTitle().trim();
        return filial.isBlank() ? company : company + ". " + filial;
    }

    private String readableException(Exception e) {
        if (e instanceof ResponseStatusException responseStatusException
                && responseStatusException.getReason() != null
                && !responseStatusException.getReason().isBlank()) {
            return responseStatusException.getReason();
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
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

    private String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }
}
