package com.hunt.otziv.common_billing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.common_billing.api.CommonOwnerReceiptConfirmation;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.t_telegrambot.api.SpecialistPaymentReviewNotice;
import com.hunt.otziv.t_telegrambot.api.TelegramNotifications;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommonOwnerReceiptNoticeDelivery {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CommonOwnerReceiptChats chats;
    private final TelegramNotifications telegram;
    private final SpecialistPaymentReviewNotice sender;

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT30S")
    public void deliverPending() {
        if (!telegram.canSendTexts()) return;
        // Pending reports are durable, including those created before buttons were introduced.
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM common_owner_receipt_approval WHERE pending_invoice_id IS NOT NULL ORDER BY id
                """, Long.class);
        for (long id : ids) {
            try {
                deliver(id);
            } catch (RuntimeException failure) {
                log.warn("Could not prepare common receipt notice reportId={}", id, failure);
            }
        }
    }

    private record Report(long invoiceId, String title, String reporter, String requestJson) {}

    private void deliver(long reportId) {
        var report = jdbc.query("""
                SELECT i.invoice_id, i.title, r.reported_by, r.request_json
                FROM common_owner_receipt_approval r JOIN common_invoices i ON i.invoice_id=r.pending_invoice_id
                WHERE r.id=?
                """, (rs, n) -> new Report(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                reportId).stream().findFirst().orElse(null);
        if (report == null) return;
        var route = chats.forInvoice(report.invoiceId()).orElse(null);
        if (route == null) return;
        String text = message(report);
        for (long chatId : route.destinations()) {
            String token = claim(reportId, route.ownerId(), chatId);
            if (token == null) continue;
            String state;
            try {
                boolean sent = sender.sendPaymentReviewNoticeOnce(chatId, text,
                        "✅ Подтвердить поступление владельцу", CommonOwnerReceiptConfirmation.PREFIX + token);
                state = sent ? "SENT" : "RETRY";
            } catch (RuntimeException uncertain) {
                // Telegram may have shown the button. The claim remains redeemable even after a lost reply.
                state = "UNCERTAIN";
                log.warn("Uncertain common receipt notice delivery reportId={}, destination={}",
                        reportId, chatId < 0 ? "group" : "private", uncertain);
            }
            jdbc.update("UPDATE common_owner_receipt_notice SET state=? WHERE callback_token=? AND state='SENDING'", state, token);
        }
    }

    private String claim(long reportId, long ownerId, long chatId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        // A completed or uncertain send is never replayed, including a crash after calling Telegram.
        if (jdbc.update("""
                UPDATE common_owner_receipt_notice n JOIN common_owner_receipt_approval r ON r.id=n.report_id
                SET n.state='SENDING', n.attempted_at=NOW(6), n.callback_token=?, n.owner_id=?
                WHERE n.report_id=? AND n.chat_id=? AND n.state='RETRY'
                AND n.attempted_at <= DATE_SUB(NOW(), INTERVAL 5 MINUTE) AND r.pending_invoice_id IS NOT NULL
                """, token, ownerId, reportId, chatId) == 1) return token;
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM common_owner_receipt_notice WHERE report_id=? AND chat_id=?)
                """, Boolean.class, reportId, chatId))) return null;
        try {
            return jdbc.update("""
                    INSERT INTO common_owner_receipt_notice(report_id, owner_id, chat_id, callback_token, state)
                    SELECT id, ?, ?, ?, 'SENDING' FROM common_owner_receipt_approval
                    WHERE id=? AND pending_invoice_id IS NOT NULL
                    """, ownerId, chatId, token, reportId) == 1 ? token : null;
        } catch (DuplicateKeyException anotherWorkerClaimed) {
            return null;
        }
    }

    private String message(Report report) {
        CommonManualPaymentAttributionRequest request;
        try {
            request = json.readValue(report.requestJson(), CommonManualPaymentAttributionRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Некорректный запрос подтверждения общего счёта", e);
        }
        long owner = request.attributions().stream().filter(row -> row.recipientType() == ContractorRecipientType.OWNER)
                .mapToLong(row -> row.amountKopecks()).sum();
        long total = request.attributions().stream().mapToLong(row -> row.amountKopecks()).sum();
        String amount = rubles(owner) + (total == owner ? "" : " из " + rubles(total) + " по счёту");
        return "Общий счёт №" + report.invoiceId() + " · " + report.title()
                + "\nМенеджер " + report.reporter() + " отметил перевод владельцу. Владельцу: " + amount
                + "\nКомментарий: " + request.reason()
                + "\n\nСверьте поступление с выпиской. Кнопка подтвердит перевод и закроет общий счёт. "
                + "Если денег нет, откройте счёт и в окне оплаты выберите «Денег нет — отклонить».";
    }

    private String rubles(long kopecks) {
        return BigDecimal.valueOf(kopecks, 2).stripTrailingZeros().toPlainString() + " ₽";
    }
}
