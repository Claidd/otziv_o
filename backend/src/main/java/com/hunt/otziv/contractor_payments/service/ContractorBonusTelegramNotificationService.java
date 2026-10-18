package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustment;
import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustmentKind;
import com.hunt.otziv.contractor_payments.repository.ContractorBonusTelegramNotificationOutboxRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorBonusTelegramNotificationOutboxRepository.Bonus;
import com.hunt.otziv.contractor_payments.repository.ContractorBonusTelegramNotificationOutboxRepository.Delivery;
import com.hunt.otziv.t_telegrambot.api.TelegramNotifications;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Private employee notice for a committed bonus; Telegram failure never changes the accrual. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContractorBonusTelegramNotificationService {

    private static final int BATCH_SIZE = 50;
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru-RU");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("LLLL yyyy", RUSSIAN);

    private final ContractorBonusTelegramNotificationOutboxRepository repository;
    private final ContractorBonusTelegramNotificationClaimService claimService;
    private final TelegramNotifications telegramNotifications;

    /** Called within the new adjustment transaction. The unique key prevents replay sends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(ContractorAccrualAdjustment saved) {
        if (saved == null || saved.getId() == null || saved.getId() <= 0) {
            throw new IllegalArgumentException("Persisted bonus adjustment is required");
        }
        if (saved.getKind() != ContractorAccrualAdjustmentKind.BONUS) {
            return;
        }
        if (!repository.enqueue(saved.getId())) {
            throw new IllegalStateException("Bonus Telegram notification intent was not stored");
        }
    }

    @Scheduled(
            fixedDelayString = "${otziv.contractor-payments.bonus-telegram.fixed-delay:PT15S}",
            initialDelayString = "${otziv.contractor-payments.bonus-telegram.initial-delay:PT30S}"
    )
    public void retryPendingNotifications() {
        int sent = retryBatch();
        if (sent > 0) {
            log.info("Contractor bonus Telegram notifications delivered: {}", sent);
        }
    }

    int retryBatch() {
        List<Long> ids = repository.findDueAdjustmentIds(BATCH_SIZE);
        int sent = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            Delivery delivery = null;
            try {
                delivery = claimService.tryClaim(id).orElse(null);
                if (delivery != null && deliver(delivery)) {
                    sent++;
                }
            } catch (RuntimeException exception) {
                log.warn("Bonus Telegram notification attempt failed: adjustmentId={}, errorType={}",
                        id, exception.getClass().getSimpleName());
                if (delivery != null) {
                    markFailed(delivery, exception.getClass().getSimpleName());
                }
            }
        }
        return sent;
    }

    private boolean deliver(Delivery delivery) {
        Bonus bonus = repository.findBonus(delivery.adjustmentId()).orElse(null);
        if (bonus == null || !"BONUS".equals(bonus.kind()) || bonus.amountKopecks() <= 0) {
            markSkipped(delivery, "bonus_adjustment_missing_or_invalid");
            return false;
        }
        if (bonus.reversed()) {
            markSkipped(delivery, "bonus_adjustment_reversed");
            return false;
        }
        if (!bonus.active()) {
            markSkipped(delivery, "employee_inactive");
            return false;
        }
        Long chatId = bonus.telegramChatId();
        if (chatId == null || chatId <= 0) {
            markSkipped(delivery, "employee_private_telegram_chat_missing");
            return false;
        }

        // Bot API provides no idempotency key. One provider call per queue claim
        // avoids compounding an ambiguous timeout with Telegram transport's normal
        // internal retries. A later queue retry may still duplicate an accepted
        // message whose acknowledgement was lost.
        if (telegramNotifications.sendTextOnce(chatId, message(bonus))) {
            boolean finalized = claimService.markSent(delivery);
            if (!finalized) {
                log.warn("Bonus Telegram notification success was fenced: adjustmentId={}",
                        delivery.adjustmentId());
            }
            return finalized;
        }
        markFailed(delivery, "telegram_send_not_confirmed");
        return false;
    }

    private void markSkipped(Delivery delivery, String reason) {
        if (!claimService.markSkipped(delivery, reason)) {
            log.warn("Bonus Telegram notification skip was fenced: adjustmentId={}",
                    delivery.adjustmentId());
        }
    }

    private void markFailed(Delivery delivery, String reason) {
        try {
            if (!claimService.markFailed(delivery, reason)) {
                log.warn("Bonus Telegram notification failure was fenced: adjustmentId={}",
                        delivery.adjustmentId());
            }
        } catch (RuntimeException exception) {
            log.warn("Bonus Telegram notification retry could not be stored: adjustmentId={}, errorType={}",
                    delivery.adjustmentId(), exception.getClass().getSimpleName());
        }
    }

    static String message(Bonus bonus) {
        String month = YearMonth.from(bonus.effectiveOn()).format(MONTH_FORMAT);
        String referenceMonth = YearMonth.from(bonus.referenceMonth()).format(MONTH_FORMAT);
        StringBuilder text = new StringBuilder()
                .append("🎁 Начислена премия\n")
                .append("Сумма: ")
                .append(String.format(RUSSIAN, "%,.2f ₽", BigDecimal.valueOf(bonus.amountKopecks(), 2)))
                .append("\nМесяц начисления: ").append(month);
        if (!bonus.effectiveOn().withDayOfMonth(1).equals(bonus.referenceMonth())) {
            text.append("\nМесяц основания: ").append(referenceMonth);
        }
        text.append("\nПричина: ").append(bonus.reason())
                .append("\n\nПремия добавлена к начислениям. Выплата учитывается отдельно.");
        return text.toString();
    }
}
