package com.hunt.otziv.paper_billing;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import com.hunt.otziv.config.settings.api.PublicationProgressSettings;
import com.hunt.otziv.client_messages.api.ClientGroupDocumentDelivery;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class PaperBillingDelivery {
    private final PaperBillingSettings settings;
    private final PaperBillingBankClient bank;
    private final ClientGroupDocumentDelivery documents;
    private final OutboundMessagePolicy outbound;
    private final PublicationProgressSettings immediate;
    public ClientMessageSendResult send(Map<String,Object> r,boolean act) {
        if (!settings.isDeliveryEnabled()) return ClientMessageSendResult.failed("not_ready","Отправка документов выключена");
        String operation="paper:"+r.get("id")+":"+(act?"act":"invoice");
        String caption=(act?"Акт к счёту ":"Счёт на оплату ")+r.get("number");
        String filename=(act?"Act-":"Invoice-")+r.get("number")+".pdf";
        byte[] bytes=(byte[])r.get(act?"act_pdf":"invoice_pdf");
        if (bank.fixture()) {
            var response=bank.fixtureCall("/delivery",Map.of("operationId",operation,"filename",filename,
                    "groupId",r.get("group_id"),"data",java.util.Base64.getEncoder().encodeToString(bytes)));
            return response.path("messageId").asText().isBlank()
                    ?ClientMessageSendResult.failed("operation_unknown","Тестовый сервис не подтвердил доставку")
                    :ClientMessageSendResult.sent("Fixture",response.path("messageId").asText());
        }
        if (!outbound.clientMessagesEnabled() || !immediate.immediatePublicationMessagesEnabled())
            return ClientMessageSendResult.failed("not_ready","Клиентские сообщения выключены");
        String destination=(String)r.get("group_id");
        return documents.send((String)r.get("client_id"),destination,caption,bytes,filename,operation);
    }
}
