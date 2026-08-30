package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaperInvoiceManagerNotificationService {

    public static final String REMINDER_SOURCE = "COMMON_PAPER_INVOICE_DELIVERY";
    private static final Set<CommonInvoiceStatus> DELIVERY_PENDING_STATUSES = Set.of(
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );

    private final CommonInvoiceRepository invoiceRepository;
    private final CommonInvoiceOrderRepository invoiceOrderRepository;
    private final PersonalReminderService personalReminderService;
    private final TelegramService telegramService;
    private final PlatformTransactionManager transactionManager;

    public void notifyAfterCommit(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        runAfterCommit(() -> notifyNow(invoiceId));
    }

    public void closeAfterCommit(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        runAfterCommit(() -> closeNow(invoiceId));
    }

    public int notifyPending(int limit) {
        List<Long> invoiceIds = invoiceRepository.findPaperInvoiceDeliveryNotificationCandidates(
                InvoicePaymentMode.OWNER_PAPER_INVOICE,
                DELIVERY_PENDING_STATUSES,
                PageRequest.of(0, Math.max(1, limit))
        );
        int processed = 0;
        for (Long invoiceId : invoiceIds) {
            if (invoiceId == null) {
                continue;
            }
            runInNewTransaction(() -> notifyNow(invoiceId));
            processed++;
        }
        return processed;
    }

    synchronized void notifyNow(Long invoiceId) {
        CommonInvoice invoice = invoiceRepository.findByIdWithAccount(invoiceId).orElse(null);
        if (!awaitsPaperInvoiceDelivery(invoice)) {
            closeNow(invoiceId);
            return;
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        Map<Long, Recipient> recipients = recipients(invoice, items);
        if (recipients.isEmpty()) {
            log.warn("Не найден ответственный менеджер для отправки бумажного счета invoiceId={}", invoiceId);
            return;
        }
        Long sourceOrderId = items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(order -> order != null && order.getId() != null)
                .map(Order::getId)
                .findFirst()
                .orElse(null);
        String title = "Отправить бумажный счёт №" + invoiceId;
        String text = notificationText(invoice);
        Set<Long> notifiedChats = new LinkedHashSet<>();
        for (Recipient recipient : recipients.values()) {
            User user = recipient.user();
            boolean alreadyOpen;
            try {
                alreadyOpen = personalReminderService.hasOpenSystemReminder(
                        user,
                        REMINDER_SOURCE,
                        invoiceId
                );
            } catch (RuntimeException exception) {
                alreadyOpen = false;
                log.warn(
                        "Не удалось проверить задачу отправки бумажного счета invoiceId={}, userId={}",
                        invoiceId,
                        user.getId(),
                        exception
                );
            }
            if (alreadyOpen) {
                continue;
            }
            try {
                personalReminderService.createSystemReminderDueNow(
                        user,
                        title,
                        limit(text, 1000),
                        REMINDER_SOURCE,
                        invoiceId,
                        sourceOrderId
                );
            } catch (RuntimeException exception) {
                log.warn(
                        "Не удалось создать задачу отправки бумажного счета invoiceId={}, userId={}",
                        invoiceId,
                        user.getId(),
                        exception
                );
            }
            Long chatId = recipient.chatId();
            if (chatId == null || !notifiedChats.add(chatId)) {
                continue;
            }
            try {
                telegramService.sendMessage(chatId, text);
            } catch (RuntimeException exception) {
                log.warn(
                        "Не удалось отправить Telegram о бумажном счете invoiceId={}, chatId={}",
                        invoiceId,
                        chatId,
                        exception
                );
            }
        }
    }

    void closeNow(Long invoiceId) {
        personalReminderService.deleteSystemRemindersBySource(REMINDER_SOURCE, invoiceId);
    }

    private boolean awaitsPaperInvoiceDelivery(CommonInvoice invoice) {
        return invoice != null
                && invoice.getInvoicePaymentMode() == InvoicePaymentMode.OWNER_PAPER_INVOICE
                && invoice.getSentAt() != null
                && invoice.getPaperInvoiceIssuedAt() == null
                && DELIVERY_PENDING_STATUSES.contains(invoice.getStatus());
    }

    private Map<Long, Recipient> recipients(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        Map<Long, Recipient> recipients = new LinkedHashMap<>();
        CommonBillingAccount account = invoice.getAccount();
        if (account != null) {
            addRecipient(recipients, account.getManager());
            if (account.getInvoiceCompany() != null) {
                addRecipient(recipients, account.getInvoiceCompany().getManager());
            }
        }
        for (CommonInvoiceOrder item : items) {
            Order order = item == null ? null : item.getOrder();
            addRecipient(recipients, order == null ? null : order.getManager());
        }
        return recipients;
    }

    private void addRecipient(Map<Long, Recipient> recipients, Manager manager) {
        User user = manager == null ? null : manager.getUser();
        if (user == null || user.getId() == null || !user.isActive()) {
            return;
        }
        Long chatId = manager.getAuditTelegramGroupChatId() == null
                ? user.getTelegramChatId()
                : manager.getAuditTelegramGroupChatId();
        recipients.putIfAbsent(user.getId(), new Recipient(user, chatId));
    }

    private String notificationText(CommonInvoice invoice) {
        String accountName = invoice.getAccount() == null ? "" : clean(invoice.getAccount().getName());
        String invoiceTitle = clean(invoice.getTitle());
        String displayTitle = invoiceTitle.isBlank() ? accountName : invoiceTitle;
        return "📄 НУЖНО ОТПРАВИТЬ БУМАЖНЫЙ СЧЁТ"
                + "\n\nОбщий счёт: №" + invoice.getId()
                + (displayTitle.isBlank() ? "" : " · " + displayTitle)
                + "\nСумма: " + rubles(invoice.getAmountKopecks()) + " ₽"
                + "\n\nКлиент уже уведомлён, что работы завершены."
                + "\n1. Отправьте клиенту сам документ счёта."
                + "\n2. В карточке счёта нажмите «Счёт отправлен клиенту»."
                + "\n\nДо подтверждения отправки клиентские напоминания и отметка оплаты заблокированы."
                + "\nОткрыть счёт: https://o-ogo.ru/admin/common-billing?invoiceId=" + invoice.getId();
    }

    private void runAfterCommit(Runnable notification) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        runInNewTransaction(notification);
                    }
                }
            });
            return;
        }
        runInNewTransaction(notification);
    }

    private void runInNewTransaction(Runnable action) {
        try {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> action.run());
        } catch (RuntimeException exception) {
            log.warn("Не удалось обработать уведомление об отправке бумажного счета", exception);
        }
    }

    private String rubles(long kopecks) {
        return BigDecimal.valueOf(kopecks, 2).stripTrailingZeros().toPlainString();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record Recipient(User user, Long chatId) {
    }
}
