package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualPaymentRecipientTelegramNotificationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ManagerRepository managerRepository;

    @Mock
    private TelegramService telegramService;

    @Mock
    private PaymentLinkRepository paymentLinkRepository;

    @InjectMocks
    private ManualPaymentRecipientTelegramNotificationService service;

    @Test
    void sendsSpecialistGroupNotificationForConfirmedManualRecipientPayment() {
        PaymentLink link = confirmedManualLink();
        link.setManualActualRecipientType(ContractorRecipientType.SPECIALIST);
        link.setManualActualRecipientUserId(77L);

        User specialist = new User();
        specialist.setId(77L);
        specialist.setFio("Елена Ч.");
        specialist.setWorkerTelegramGroupChatId(-10077L);
        when(userRepository.findById(77L)).thenReturn(Optional.of(specialist));

        User confirmer = new User();
        confirmer.setUsername("manager@example.ru");
        confirmer.setFio("Виктория Ц.");
        when(userRepository.findByUsername("manager@example.ru")).thenReturn(Optional.of(confirmer));

        when(telegramService.sendMessage(eq(-10077L), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        service.notifyAfterCommit(link);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(eq(-10077L), message.capture());
        assertTrue(message.getValue().contains("Оплата по реквизитам подтверждена"));
        assertTrue(message.getValue().contains("Получатель: Елена Ч."));
        assertTrue(message.getValue().contains("Сумма: 1 400 ₽"));
        assertTrue(message.getValue().contains("Заказ №24684"));
        assertTrue(message.getValue().contains("Компания: Rost — Улица Калинина, 127/1"));
        assertTrue(message.getValue().contains("Подтвердил: Виктория Ц."));
    }

    @Test
    void loadsPaymentLinkByIdBeforeSendingNotification() {
        PaymentLink link = confirmedManualLink();
        link.setManualActualRecipientType(ContractorRecipientType.SPECIALIST);
        link.setManualActualRecipientUserId(78L);

        User specialist = new User();
        specialist.setId(78L);
        specialist.setFio("Юлия К.");
        specialist.setTelegramChatId(7800L);
        when(paymentLinkRepository.findByIdWithOrder(6803L)).thenReturn(Optional.of(link));
        when(userRepository.findById(78L)).thenReturn(Optional.of(specialist));
        when(telegramService.sendMessage(eq(7800L), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        service.notifyAfterCommit(6803L);

        verify(paymentLinkRepository).findByIdWithOrder(6803L);
        verify(telegramService).sendMessage(eq(7800L), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void skipsUnconfirmedOrUnattributedPaymentLinks() {
        PaymentLink link = confirmedManualLink();
        link.setStatus(PaymentLinkStatus.AMOUNT_MISMATCH);
        link.setManualActualRecipientType(ContractorRecipientType.SPECIALIST);
        link.setManualActualRecipientUserId(77L);

        service.notifyAfterCommit(link);

        verifyNoInteractions(userRepository, managerRepository, telegramService);
    }

    private PaymentLink confirmedManualLink() {
        Company company = new Company();
        company.setTitle("Rost");
        Filial filial = new Filial();
        filial.setTitle("Улица Калинина, 127/1");
        Order order = new Order();
        order.setId(24684L);
        order.setCompany(company);
        order.setFilial(filial);

        PaymentLink link = new PaymentLink();
        link.setId(6803L);
        link.setOrder(order);
        link.setAmountKopecks(140_000L);
        link.setConfirmedAmountKopecks(140_000L);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualConfirmedBy("manager@example.ru");
        link.setManualConfirmedAt(LocalDateTime.of(2026, 8, 21, 16, 35));
        return link;
    }
}
