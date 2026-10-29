package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.model.PaymentLink;
import java.time.LocalDateTime;
import org.junit.jupiter.api.*;

class PaymentSuccessReceiptRecoveryTest {
    ClientChatMessageSender sender=mock(ClientChatMessageSender.class);
    AppSettingService settings=mock(AppSettingService.class);
    PaymentSuccessClientNotifier notifier=new PaymentSuccessClientNotifier(sender,new TbankPaymentProperties(),settings);
    PaymentLink link=new PaymentLink();
    @BeforeEach void setup(){
        link.setId(7L);link.setAmountKopecks(10000);link.setPaidAt(LocalDateTime.now().minusDays(3));
        var company=new Company();company.setId(3L);var order=new Order();order.setId(77L);order.setCompany(company);link.setOrder(order);
        when(settings.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED,true)).thenReturn(true);
    }
    @Test void unknownPaymentNotificationQueriesTheOriginalReceiptWithoutAllocatingAnotherId(){
        when(sender.sendWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),eq("payment-success:7")))
                .thenReturn(ClientMessageSendResult.failed("operation_unknown","unknown"));
        var proof=ClientMessageSendResult.sent("WhatsApp","original-message");
        when(sender.recoverRecordedOutcomeFromChat("payment-success:7",link.getPaidAt())).thenReturn(proof);
        assertThat(notifier.notifySuccess(link)).isEqualTo(proof);
        verify(sender).recoverRecordedOutcomeFromChat("payment-success:7",link.getPaidAt());
        verify(sender,times(1)).sendWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),eq("payment-success:7"));
    }
    @Test void unresolvedHistoryRemainsUnknownAndCannotAssertClientNotification(){
        when(sender.sendWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),anyString()))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp"));
        when(sender.recoverRecordedOutcomeFromChat(anyString(),any())).thenReturn(ClientMessageSendResult.failed("whatsapp_history_checked_no_receipt","no receipt"));
        assertThat(notifier.notifySuccess(link).sent()).isFalse();
        assertThat(link.getPaymentSuccessNotifiedAt()).isNull();
    }
    @Test void knownAdmissionFailureDoesNotNeedAHistorySearch(){
        var failure=ClientMessageSendResult.failed("gateway_not_ready","not ready");
        when(sender.sendWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),anyString())).thenReturn(failure);
        assertThat(notifier.notifySuccess(link)).isEqualTo(failure);
        verify(sender,never()).recoverRecordedOutcomeFromChat(anyString(),any());
    }
}
