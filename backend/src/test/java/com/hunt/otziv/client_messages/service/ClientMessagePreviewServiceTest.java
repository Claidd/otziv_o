package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessagePreview;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.status.service.OrderPaymentMessageBuilder;
import com.hunt.otziv.p_products.status.service.OrderReviewCheckMessageBuilder;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientMessagePreviewServiceTest {

    @Mock AppSettingService appSettingService;
    @Mock OrderReviewCheckMessageBuilder reviewCheckMessageBuilder;
    @Mock BadReviewTaskService badReviewTaskService;
    @Mock OrderPaymentMessageBuilder orderPaymentMessageBuilder;
    @InjectMocks ClientMessagePreviewService service;

    @Test
    void badReviewPreviewUsesSameFactualPaymentRendererAsDelivery() {
        Order order = new Order();
        order.setId(15L);
        Company company = new Company();
        company.setId(25L);
        order.setCompany(company);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:15")
                .orderId(15L)
                .build();
        when(orderPaymentMessageBuilder.publishedOrderPaymentMessagePreview(order))
                .thenReturn("Компания\n\nК оплате: 1600 руб.");

        ClientMessagePreview preview = service.preview(state, order, company);

        assertEquals("Компания\n\nК оплате: 1600 руб.", preview.messagePreview());
        verify(orderPaymentMessageBuilder).publishedOrderPaymentMessagePreview(order);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TBANK_LINK", "BANK_LINK", "TOCHKA_LINK"})
    void paymentPreviewRecognizesEveryBankLinkAlias(String source) {
        Company company = new Company();
        company.setId(25L);
        company.setTitle("Компания");
        Order order = new Order();
        order.setId(15L);
        order.setCompany(company);
        order.setManager(new Manager());
        order.setSum(BigDecimal.valueOf(1300));
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("payment-reminder:order:15")
                .orderId(15L)
                .build();
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                "MANAGER_TEXT"
        )).thenReturn(source);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_TEXT,
                ScheduledClientMessageService.DEFAULT_PAYMENT_REMINDER_TEXT
        )).thenReturn(ScheduledClientMessageService.DEFAULT_PAYMENT_REMINDER_TEXT);
        when(badReviewTaskService.getPayableSum(order)).thenReturn(BigDecimal.valueOf(1300));

        ClientMessagePreview preview = service.preview(state, order, company);

        assertEquals(source, preview.paymentInstructionSource());
        assertTrue(preview.messagePreview().contains("текст для оплаты будет создан"));
    }
}
