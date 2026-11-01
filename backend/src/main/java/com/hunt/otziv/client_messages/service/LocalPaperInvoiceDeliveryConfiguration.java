package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

/** Local Docker only. No real provider is injected, even into inherited legacy methods. */
@Configuration @Profile("paper-invoice-fixture")
public class LocalPaperInvoiceDeliveryConfiguration {
    @Bean @Primary
    ClientChatMessageSender localPaperInvoiceMessageSender(Environment environment) {
        String token=environment.getProperty("otziv.payments.tochka.jwt-token","");
        if(!environment.getProperty("paper-invoices.fixture-url","").equals("http://paper-invoice-fixture:8099")
                || (!token.isBlank() && !token.equals("local-fixture-only")))
            throw new IllegalStateException("Local message fixture requires an isolated bank without a real token");
        var rest=new RestTemplate();
        return new ClientChatMessageSender(null,null,null,null,null,null) {
            private ClientMessageSendResult sendLocal(Target target,String group,String message,String operation) {
                if(target==null || target.title()==null || !target.title().startsWith("Local Paper") || group==null || operation==null)
                    return ClientMessageSendResult.failed("not_ready","Only isolated Local Paper companies are allowed");
                var response=rest.postForObject("http://paper-invoice-fixture:8099/delivery",
                        Map.of("operationId",operation,"groupId",group,"caption",message),Map.class);
                return response!=null && response.get("messageId") instanceof String id
                        ?ClientMessageSendResult.sent("Fixture",id):ClientMessageSendResult.failed("operation_unknown","Fixture receipt missing");
            }
            @Override public ClientMessageSendResult deliverWithOperationId(Target target,String client,String group,String message,TelegramTransferCopyButton transfer,String operation) {
                return sendLocal(target,group,message,operation);
            }
            @Override public ClientMessageSendResult deliverToPlatformWithOperationId(String platform,Target target,String client,String group,String message,String operation) {
                return sendLocal(target,group,message,operation);
            }
            @Override public ClientMessageSendResult deliverPublicationProgressWithOperationId(Target target,String client,String group,String message,boolean controls,String operation) {
                return sendLocal(target,group,message,operation);
            }
            @Override public ClientMessageSendResult recordedOutcome(String operation) {
                var response=rest.postForObject("http://paper-invoice-fixture:8099/delivery-receipt",Map.of("operationId",operation),Map.class);
                return response!=null && response.get("messageId") instanceof String id
                        ?ClientMessageSendResult.sent("Fixture",id):ClientMessageSendResult.failed("operation_unknown","Fixture receipt missing");
            }
            @Override public ClientMessageSendResult recoverRecordedOutcomeFromChat(String operation,java.time.LocalDateTime preparedAt) {return recordedOutcome(operation);}
            @Override public boolean hasSettledUncertainOperation(String operation) {return false;}
            @Override public boolean publicationProgressEnabled(Long company) {return false;}
        };
    }
}
