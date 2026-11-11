package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

/**
 * Local Docker only. No real provider is injected, even into inherited legacy methods.
 * With the {@code payment-sandbox} profile every client message of every company is kept by the fixture
 * (http://localhost:8099 shows them); without it only the isolated "Local Paper" companies are served.
 */
@Configuration @Profile("paper-invoice-fixture")
public class LocalPaperInvoiceDeliveryConfiguration {
    @Bean @Primary
    ClientChatMessageSender localPaperInvoiceMessageSender(Environment environment) {
        String token=environment.getProperty("otziv.payments.tochka.jwt-token","");
        if(!environment.getProperty("paper-invoices.fixture-url","").equals("http://paper-invoice-fixture:8099")
                || (!token.isBlank() && !token.equals("local-fixture-only")))
            throw new IllegalStateException("Local message fixture requires an isolated bank without a real token");
        var rest=new RestTemplate();
        boolean sandbox=environment.acceptsProfiles(org.springframework.core.env.Profiles.of("payment-sandbox"));
        return new ClientChatMessageSender(null,null,null,null,null,null,null) {
            private ClientMessageSendResult sendLocal(Target target,String group,String message,String operation) {
                boolean localPaper=target!=null && target.title()!=null && target.title().startsWith("Local Paper") && group!=null;
                if(target==null || operation==null || (!sandbox && !localPaper))
                    return ClientMessageSendResult.failed("not_ready","Only isolated Local Paper companies are allowed");
                if(message==null || message.isBlank()) return ClientMessageSendResult.failed("message_empty","Текст сообщения пустой");
                var payload=new java.util.HashMap<String,Object>();
                payload.put("operationId",operation);
                payload.put("groupId",group!=null?group:"company-"+target.companyId());
                payload.put("caption",message);
                payload.put("title",target.title()==null?"":target.title());
                var response=rest.postForObject("http://paper-invoice-fixture:8099/delivery",payload,Map.class);
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
