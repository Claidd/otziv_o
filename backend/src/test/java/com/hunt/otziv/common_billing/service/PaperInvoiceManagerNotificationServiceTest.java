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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperInvoiceManagerNotificationServiceTest {

    @Mock
    private CommonInvoiceRepository invoiceRepository;
    @Mock
    private CommonInvoiceOrderRepository invoiceOrderRepository;
    @Mock
    private PersonalReminderService personalReminderService;
    @Mock
    private TelegramService telegramService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private PaperInvoiceManagerNotificationService service;

    @BeforeEach
    void setUp() {
        service = new PaperInvoiceManagerNotificationService(
                invoiceRepository,
                invoiceOrderRepository,
                personalReminderService,
                telegramService,
                transactionManager
        );
    }

    @Test
    void createsPersonalTaskAndTelegramForResponsibleManager() {
        Manager manager = manager(51L, 501L, -100501L, 5001L);
        CommonInvoice invoice = pendingInvoice(manager);
        Order order = new Order();
        order.setId(24808L);
        order.setManager(manager);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setOrder(order);
        when(invoiceRepository.findByIdWithAccount(901L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(901L)).thenReturn(List.of(item));
        when(personalReminderService.hasOpenSystemReminder(
                manager.getUser(),
                PaperInvoiceManagerNotificationService.REMINDER_SOURCE,
                901L
        )).thenReturn(false);

        service.notifyNow(901L);

        verify(personalReminderService).createSystemReminderDueNow(
                eq(manager.getUser()),
                eq("Отправить бумажный счёт №901"),
                contains("Счёт отправлен клиенту"),
                eq(PaperInvoiceManagerNotificationService.REMINDER_SOURCE),
                eq(901L),
                eq(24808L)
        );
        verify(telegramService).sendMessage(
                eq(-100501L),
                contains("НУЖНО ОТПРАВИТЬ БУМАЖНЫЙ СЧЁТ")
        );
    }

    @Test
    void doesNotRepeatTelegramWhilePersonalTaskIsOpen() {
        Manager manager = manager(51L, 501L, -100501L, 5001L);
        CommonInvoice invoice = pendingInvoice(manager);
        when(invoiceRepository.findByIdWithAccount(901L)).thenReturn(Optional.of(invoice));
        when(invoiceOrderRepository.findByInvoiceIdWithOrders(901L)).thenReturn(List.of());
        when(personalReminderService.hasOpenSystemReminder(
                manager.getUser(),
                PaperInvoiceManagerNotificationService.REMINDER_SOURCE,
                901L
        )).thenReturn(true);

        service.notifyNow(901L);

        verify(personalReminderService, never()).createSystemReminderDueNow(
                eq(manager.getUser()),
                eq("Отправить бумажный счёт №901"),
                contains("бумажный"),
                eq(PaperInvoiceManagerNotificationService.REMINDER_SOURCE),
                eq(901L),
                eq(null)
        );
        verify(telegramService, never()).sendMessage(eq(-100501L), contains("бумажный"));
    }

    @Test
    void closesObsoleteTaskWhenPaperInvoiceWasAlreadyIssued() {
        Manager manager = manager(51L, 501L, -100501L, 5001L);
        CommonInvoice invoice = pendingInvoice(manager);
        invoice.setPaperInvoiceIssuedAt(LocalDateTime.now());
        when(invoiceRepository.findByIdWithAccount(901L)).thenReturn(Optional.of(invoice));

        service.notifyNow(901L);

        verify(personalReminderService).deleteSystemRemindersBySource(
                PaperInvoiceManagerNotificationService.REMINDER_SOURCE,
                901L
        );
        verify(telegramService, never()).sendMessage(eq(-100501L), contains("бумажный"));
    }

    private CommonInvoice pendingInvoice(Manager manager) {
        CommonBillingAccount account = new CommonBillingAccount();
        account.setId(71L);
        account.setName("Caffetteria Piu");
        account.setManager(manager);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(901L);
        invoice.setAccount(account);
        invoice.setTitle("Caffetteria Piu — общий счёт");
        invoice.setAmountKopecks(2_400_000L);
        invoice.setInvoicePaymentMode(InvoicePaymentMode.OWNER_PAPER_INVOICE);
        invoice.setStatus(CommonInvoiceStatus.INVOICED);
        invoice.setSentAt(LocalDateTime.now());
        return invoice;
    }

    private Manager manager(Long managerId, Long userId, Long groupChatId, Long privateChatId) {
        User user = new User();
        user.setId(userId);
        user.setActive(true);
        user.setTelegramChatId(privateChatId);
        Manager manager = new Manager();
        manager.setId(managerId);
        manager.setUser(user);
        manager.setAuditTelegramGroupChatId(groupChatId);
        return manager;
    }
}
