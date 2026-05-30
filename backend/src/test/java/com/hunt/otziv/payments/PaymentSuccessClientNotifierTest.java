package com.hunt.otziv.payments;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.service.PaymentSuccessClientNotifier;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentSuccessClientNotifierTest {

    @Mock
    private ClientChatMessageSender messageSender;

    @Mock
    private AppSettingService appSettingService;

    @Test
    void notifySuccessSendsPaymentPageAndReceiptNoticeToCompanyMessenger() {
        TbankPaymentProperties properties = new TbankPaymentProperties();
        properties.setPublicBaseUrl("https://o-ogo.ru/");
        PaymentSuccessClientNotifier notifier = new PaymentSuccessClientNotifier(messageSender, properties, appSettingService);

        Manager manager = new Manager();
        manager.setClientId("wa-client");
        Company company = new Company();
        company.setTitle("Компания О!");
        company.setGroupId("wa-group");
        company.setManager(manager);
        Order order = new Order();
        order.setId(22386L);
        order.setCompany(company);

        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("pay-token");
        link.setAmountKopecks(25000L);
        link.setConfirmedAmountKopecks(25000L);
        link.setPayerEmail("client@example.ru");
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(messageSender.send(eq(company), eq("wa-client"), eq("wa-group"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp"));
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)).thenReturn(true);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_SUCCESS_TEXT,
                ScheduledClientMessageService.DEFAULT_PAYMENT_SUCCESS_TEXT
        )).thenReturn(ScheduledClientMessageService.DEFAULT_PAYMENT_SUCCESS_TEXT);

        ClientMessageSendResult result = notifier.notifySuccess(link);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageSender).send(eq(company), eq("wa-client"), eq("wa-group"), messageCaptor.capture());
        String message = messageCaptor.getValue();
        assertTrue(result.sent());
        assertTrue(message.contains("Оплата прошла успешно."));
        assertTrue(message.contains("Новый заказ принят в работу."));
        assertTrue(message.contains("Заказ №22386"));
        assertTrue(message.contains("Сумма: 250 ₽"));
        assertTrue(message.contains("Страница оплаты: https://o-ogo.ru/pay/pay-token"));
        assertTrue(message.contains("Чек будет отправлен на e-mail: client@example.ru."));
    }
}
