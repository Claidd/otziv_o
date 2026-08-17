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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
