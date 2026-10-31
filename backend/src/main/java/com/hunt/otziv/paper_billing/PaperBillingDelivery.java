package com.hunt.otziv.paper_billing;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import com.hunt.otziv.config.settings.api.PublicationProgressSettings;
import com.hunt.otziv.client_messages.api.ClientGroupDocumentDelivery;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
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
        String caption=caption(r,act);
        String filename=(act?"Act-":"Invoice-")+r.get("number")+".pdf";
        byte[] bytes=(byte[])r.get(act?"act_pdf":"invoice_pdf");
        if (bank.fixture()) {
            var response=bank.fixtureCall("/delivery",Map.of("operationId",operation,"filename",filename,
                    "groupId",r.get("group_id"),"caption",caption,"data",java.util.Base64.getEncoder().encodeToString(bytes)));
            return response.path("messageId").asText().isBlank()
                    ?ClientMessageSendResult.failed("operation_unknown","Тестовый сервис не подтвердил доставку")
                    :ClientMessageSendResult.sent("Fixture",response.path("messageId").asText());
        }
        if (!outbound.clientMessagesEnabled() || !immediate.immediatePublicationMessagesEnabled())
            return ClientMessageSendResult.failed("not_ready","Клиентские сообщения выключены");
        String destination=(String)r.get("group_id");
        return documents.send((String)r.get("client_id"),destination,caption,bytes,filename,operation);
    }
    public static String caption(Map<String,Object> document,boolean act) {
        long kopecks=((Number)document.get("amount_kopecks")).longValue();
        var format=NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
        format.setMinimumFractionDigits(kopecks%100==0?0:2);
        format.setMaximumFractionDigits(2);
        String amount=format.format(BigDecimal.valueOf(kopecks,2)).replace('\u00a0',' ')+" ₽";
        String number=(String)document.get("number");
        if (act) return "Акт выполненных работ к счёту № "+number+" на сумму "+amount+".";
        return "Здравствуйте! Ваш заказ выполнен.\nК оплате: "+amount+".\n\n"
                +"Во вложениях — счёт № "+number+" и акт выполненных работ.\n\n"
                +"Пожалуйста, оплатите счёт переводом с расчётного счёта вашей организации или ИП "
                +"по реквизитам из приложенного счёта. Переведите всю сумму одним платежом, "
                +"в назначении платежа укажите номер счёта: "+number+".\n\n"
                +"После поступления средств оплата подтвердится автоматически. Спасибо!";
    }
}
