package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.service.BankPaymentInstructionSource;
import com.hunt.otziv.payments.service.PaymentLinkService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OrderPaymentMessageBuilder {

    private static final String RECOVERY_PRODUCT_TITLE = "Восстановление";

    private final AppSettingService appSettingService;
    private final ObjectProvider<PaymentLinkService> paymentLinkServiceProvider;
    private final BadReviewTaskService badReviewTaskService;

    public String publishedOrderPaymentMessage(Order order) {
        return publishedOrderPaymentMessageWithTransfer(order).message();
    }

    public PreparedPaymentMessage publishedOrderPaymentMessageWithTransfer(Order order) {
        if (usesBankPaymentInstructionSource()) {
            ManagerPaymentLinkResponse link = paymentLink(order);
            return new PreparedPaymentMessage(link.copyText(), link.telegramCopyTransferNumber());
        }
        String heading = orderHeading(order);
        String paymentText = paymentInstruction(order) + " К оплате: " + money(payableSum(order)) + " руб.";
        return new PreparedPaymentMessage(
                heading.isBlank() ? paymentText : heading + "\n\n" + paymentText,
                null
        );
    }

    /** Read-only preview that shares the factual amount and recipient renderer. */
    public String publishedOrderPaymentMessagePreview(Order order) {
        if (!usesBankPaymentInstructionSource()) {
            return publishedOrderPaymentMessage(order);
        }
        String heading = orderHeading(order);
        String paymentText = "К оплате: " + money(payableSum(order))
                + " руб.\n\n[банковская ссылка будет создана при отправке]";
        return heading.isBlank() ? paymentText : heading + "\n\n" + paymentText;
    }

    public boolean shouldSkipPublishedPayment(Order order) {
        return hasRecoveryProduct(order) && payableSum(order).compareTo(BigDecimal.ZERO) <= 0;
    }

    private String paymentInstruction(Order order) {
        return managerPayText(order);
    }

    private String paymentLinkCopyText(Order order) {
        return paymentLink(order).copyText();
    }

    private ManagerPaymentLinkResponse paymentLink(Order order) {
        try {
            return paymentLinkServiceProvider.getObject().createForOrderInNewTransaction(order.getId());
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не удалось подготовить банковскую ссылку для заказа #" + (order == null ? "-" : order.getId()) + ": " + readableException(e),
                    e
            );
        }
    }

    private boolean usesBankPaymentInstructionSource() {
        String source = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                BankPaymentInstructionSource.MANAGER_TEXT
        );
        return BankPaymentInstructionSource.isBankLink(source);
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
        if (order == null || order.getId() == null || order.getSum() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не удалось достоверно определить сумму заказа для платежного сообщения"
            );
        }
        try {
            BigDecimal payable = badReviewTaskService.getPayableSum(order);
            if (payable == null || payable.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalStateException("Некорректная итоговая сумма заказа");
            }
            return payable;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не удалось учесть выполненные дополнительные задачи в сумме заказа #" + order.getId(),
                    e
            );
        }
    }

    public record PreparedPaymentMessage(String message, String telegramCopyTransferNumber) {
    }

    private boolean hasRecoveryProduct(Order order) {
        return order != null
                && order.getDetails() != null
                && order.getDetails().stream()
                .map(OrderDetails::getProduct)
                .anyMatch(product -> product != null && isRecoveryProductTitle(product.getTitle()));
    }

    private boolean isRecoveryProductTitle(String title) {
        return title != null && RECOVERY_PRODUCT_TITLE.equalsIgnoreCase(title.trim());
    }

    private String money(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalStateException("Сумма платежного сообщения не определена");
        }
        BigDecimal value = amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }
}
