package com.hunt.otziv.p_products.status;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.PaymentLinkService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.u_users.model.Manager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentMessageBuilderTest {

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private ObjectProvider<PaymentLinkService> paymentLinkServiceProvider;

    @Mock
    private PaymentLinkService paymentLinkService;

    @Mock
    private BadReviewTaskService badReviewTaskService;

    @Test
    void managerTextSourceUsesManagerPaymentText() {
        Order order = order();
        order.getManager().setPayText("АЛЬФА-БАНК по счету 123");
        when(appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE, "MANAGER_TEXT"))
                .thenReturn("MANAGER_TEXT");
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1800));

        String message = service().publishedOrderPaymentMessage(order);

        assertTrue(message.contains("Компания. Филиал"));
        assertTrue(message.contains("АЛЬФА-БАНК по счету 123"));
        assertTrue(message.contains("К оплате: 1800 руб."));
        verifyNoInteractions(paymentLinkServiceProvider, paymentLinkService);
    }

    @Test
    void tbankSourceCreatesPaymentLink() {
        Order order = order();
        order.getManager().setPayText("старый текст оплаты");
        when(appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE, "MANAGER_TEXT"))
                .thenReturn("TBANK_LINK");
        when(paymentLinkServiceProvider.getObject()).thenReturn(paymentLinkService);
        when(paymentLinkService.createForOrder(10L)).thenReturn(new ManagerPaymentLinkResponse(
                "token",
                "https://o-ogo.ru/pay/token",
                10L,
                BigDecimal.valueOf(1500),
                150000,
                "CREATED",
                "BANK_FORM",
                LocalDateTime.now().plusDays(90),
                "Ссылка на оплату: https://o-ogo.ru/pay/token",
                "Компания. Филиал\n\nЗдравствуйте, ваш заказ выполнен. К оплате: 1500 руб.\n\nСсылка на оплату: https://o-ogo.ru/pay/token"
        ));

        String message = service().publishedOrderPaymentMessage(order);

        assertTrue(message.contains("Ссылка на оплату: https://o-ogo.ru/pay/token"));
        assertTrue(message.contains("К оплате: 1500 руб."));
        assertFalse(message.contains("старый текст оплаты"));
        verify(paymentLinkService).createForOrder(10L);
    }

    private OrderPaymentMessageBuilder service() {
        return new OrderPaymentMessageBuilder(appSettingService, paymentLinkServiceProvider, badReviewTaskService);
    }

    private Order order() {
        Company company = new Company();
        company.setTitle("Компания");

        Filial filial = new Filial();
        filial.setTitle("Филиал");

        Manager manager = new Manager();
        manager.setClientId("client");

        Order order = new Order();
        order.setId(10L);
        order.setCompany(company);
        order.setFilial(filial);
        order.setManager(manager);
        order.setSum(BigDecimal.valueOf(1500));
        return order;
    }
}
