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
    com.hunt.otziv.client_messages.api.NotificationFollowup followups=mock(com.hunt.otziv.client_messages.api.NotificationFollowup.class);
    PaymentSuccessClientNotifier notifier=new PaymentSuccessClientNotifier(sender,new TbankPaymentProperties(),settings,
            followups);
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

    @Test void aMissingPaymentChatIsRepairedBeforeDispatchAndUsesTheOriginalPaymentOperation(){
        when(sender.recoverMissingWhatsAppGroup(any(), eq(""), eq(""), eq("payment-success:7")))
                .thenReturn("123456789@g.us");
        var proof=ClientMessageSendResult.sent("WhatsApp","payment-receipt");
        when(sender.sendWithOperationId(any(),eq(""),eq("123456789@g.us"),anyString(),isNull(),eq("payment-success:7")))
                .thenReturn(proof);
        assertThat(notifier.notifySuccess(link)).isEqualTo(proof);
        var ordered=inOrder(sender);
        ordered.verify(sender).recoverMissingWhatsAppGroup(any(),eq(""),eq(""),eq("payment-success:7"));
        ordered.verify(sender).sendWithOperationId(any(),eq(""),eq("123456789@g.us"),anyString(),isNull(),eq("payment-success:7"));
        verify(sender,never()).recoverRecordedOutcomeFromChat(anyString(),any());
        assertThat(link.getOrder().getId()).isEqualTo(77L);
        assertThat(link.getAmountKopecks()).isEqualTo(10000L);
    }

    @Test void followupConfirmsPreviouslyReceivedMoneyAndCannotAskForAnotherTransfer(){
        link.setStatus(com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED);
        link.getOrder().getCompany().setTitle("Local Company");
        link.getOrder().getCompany().setUrlChat("https://chat.whatsapp.com/LocalFixtureInvite");
        link.getOrder().setManager(com.hunt.otziv.u_users.model.Manager.builder().id(3L).clientId("client").build());
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED,true)).thenReturn(true);
        when(followups.recover(any(),any())).thenAnswer(inv->{
            var request=inv.<com.hunt.otziv.client_messages.api.NotificationFollowup.Request>getArgument(0);
            assertThat(request.originalOperationId()).isEqualTo("payment-success:7");
            assertThat(request.sourceVersion()).matches("[a-f0-9]{64}");
            assertThat(request.message()).contains("№77","100 ₽","получена ранее","Повторно оплачивать этот заказ не нужно");
            assertThat(request.sourceSnapshot()).contains("\"payment\":\"7\"");
            assertThat(inv.<java.util.function.BooleanSupplier>getArgument(1).getAsBoolean()).isTrue();
            return new com.hunt.otziv.client_messages.api.NotificationFollowup.Outcome("payment-success:7","followup:fixture",true,
                    ClientMessageSendResult.sent("WhatsApp","receipt"));
        });
        assertThat(notifier.recoverWithFollowup(link,()->true).delivery().sent()).isTrue();
    }

    @Test void refundedPaymentsAndDisabledMessagingCannotPrepareAFollowup(){
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED,true)).thenReturn(true);
        link.setStatus(com.hunt.otziv.payments.model.PaymentLinkStatus.REFUNDED);
        assertThat(notifier.recoverWithFollowup(link,()->true)).isNull();
        link.setStatus(com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED);
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED,true)).thenReturn(false);
        assertThat(notifier.recoverWithFollowup(link,()->true)).isNull();
        verifyNoInteractions(followups);
    }
}
