package com.hunt.otziv.payments.service;

import com.hunt.otziv.common_billing.api.CommonInvoiceRecipientReceipts;
import com.hunt.otziv.common_billing.api.CommonInvoiceRecipientReceipts.Receipt;
import com.hunt.otziv.u_users.api.SpecialistPaymentReviewDirectory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The recipient named in a manually confirmed common-invoice receipt says the money did not arrive.
 * The receipt stays credited: the dispute is recorded once and raises a staff task for the responsible
 * manager, the owners and the administrators, who correct the receipt after checking the statement.
 */
@Service
@RequiredArgsConstructor
public class CommonInvoicePaymentDisputeService {

    public static final String CALLBACK_PREFIX = "common-payment-dispute:";
    public static final String DISPUTE_ISSUE = "COMMON_INVOICE_PAYMENT_DISPUTE";
    static final Duration DISPUTE_WINDOW = Duration.ofHours(24);

    private final CommonInvoiceRecipientReceipts receipts;
    private final SpecialistPaymentReviewDirectory users;
    private final PaymentIssueReminderService issues;

    @Transactional
    public String dispute(Long deliveryId, Long telegramUserId, Long chatId) {
        Receipt receipt = deliveryId == null ? null : receipts.find(deliveryId).orElse(null);
        if (receipt == null || receipt.recipientUserId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Уведомление об оплате не найдено");
        }
        Long recipientTelegramId = users.activePersonalTelegramChatId(receipt.recipientUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Получатель не найден"));
        if (telegramUserId == null || !telegramUserId.equals(recipientTelegramId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Оспорить поступление может только указанный получатель");
        }
        if (!Objects.equals(telegramUserId, chatId)) {
            Long groupId = users.activePaymentReviewGroupChatId(receipt.recipientUserId(), receipt.recipientType())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Рабочая группа получателя не привязана"));
            if (!Objects.equals(groupId, chatId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Оспорить поступление можно в рабочей группе получателя");
            }
        }
        if (receipt.disputedAt() != null) {
            return "Поступление уже оспорено; владельцы и администраторы уведомлены";
        }
        if (receipt.sentAt() == null || !LocalDateTime.now().isBefore(receipt.sentAt().plus(DISPUTE_WINDOW))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Срок спора (24 часа) прошёл. Сообщите менеджеру или администратору");
        }
        if (!receipts.markDisputed(receipt.deliveryId(), telegramUserId)) {
            return "Поступление уже оспорено; владельцы и администраторы уведомлены";
        }
        if (receipt.anchorOrderId() != null) {
            issues.notifyOrderIssueAfterCommit(receipt.anchorOrderId(), DISPUTE_ISSUE, receipt.deliveryId(),
                    "Получатель оспорил оплату общего счёта №" + receipt.invoiceId(),
                    "Получатель сообщил, что не получал перевод по общему счёту №" + receipt.invoiceId()
                            + (hasText(receipt.invoiceTitle()) ? " · " + receipt.invoiceTitle().trim() : "")
                            + ".\nСумма: " + BigDecimal.valueOf(receipt.amountKopecks(), 2).stripTrailingZeros().toPlainString() + " ₽"
                            + (hasText(receipt.confirmedBy()) ? "\nОплату отметил: " + receipt.confirmedBy().trim() : "")
                            + "\nСчёт уже закрыт, сумма зачтена получателю и сама не отменится. Сверьте выписку: "
                            + "если деньги получил другой человек, исправьте получателя в разделе "
                            + "«Платежи, возвраты и чеки».");
        }
        return "Спор сохранён. Менеджер, владелец и администратор получили задачу на сверку";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
