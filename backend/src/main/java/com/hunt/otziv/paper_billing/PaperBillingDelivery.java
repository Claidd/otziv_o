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
    private final ClientMessageDelivery messages;
    public ClientMessageSendResult sendIntro(Map<String,Object> r) {
        return sendText(r,"message",instructions(r));
    }
    public ClientMessageSendResult sendCancellation(Map<String,Object> r) {
        return sendText(r,"cancellation","Счёт № "+r.get("number")+" и акт выполненных работ отменены.\n\n"
                +"Пожалуйста, не оплачивайте этот счёт. Если перевод уже отправлен, сообщите об этом в чате — мы проверим поступление.\n"
                +("PENDING".equals(r.get("cancel_replacement_state"))?"Новые реквизиты для оплаты направим отдельным сообщением. Дождитесь их, пожалуйста.":"При необходимости направим новый счёт отдельным сообщением."));
    }
    private ClientMessageSendResult sendText(Map<String,Object> r,String kind,String text) {
        if (!settings.isDeliveryEnabled()) return ClientMessageSendResult.failed("not_ready","Отправка документов выключена");
        String operation="paper:"+r.get("id")+":"+kind;
        if(bank.fixture()) {
            var response=bank.fixtureCall("/delivery",Map.of("operationId",operation,"groupId",r.get("group_id"),"caption",text));
            return response.path("messageId").asText().isBlank()
                    ?ClientMessageSendResult.failed("operation_unknown","Тестовый сервис не подтвердил сообщение")
                    :ClientMessageSendResult.sent("Fixture",response.path("messageId").asText());
        }
        if (!outbound.clientMessagesEnabled() || !immediate.immediatePublicationMessagesEnabled())
            return ClientMessageSendResult.failed("not_ready","Клиентские сообщения выключены");
        String[] destination=((String)r.get("group_id")).split(":",2);
        var target=new ClientMessageDelivery.Target(((Number)r.get("company_id")).longValue(),"","",
                destination[0].equals("TELEGRAM")?Long.valueOf(destination[1]):null,
                destination[0].equals("MAX")?Long.valueOf(destination[1]):null);
        return messages.deliverToPlatformWithOperationId(destination[0],target,(String)r.get("client_id"),destination[1],text,operation);
    }
    public ClientMessageSendResult send(Map<String,Object> r,boolean act) {
        if (!settings.isDeliveryEnabled()) return ClientMessageSendResult.failed("not_ready","Отправка документов выключена");
        String operation="paper:"+r.get("id")+":"+(act?"act":"invoice");
        String caption="";
        String filename=filename(r,act);
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
    public static String filename(Map<String,Object> document,boolean act) {
        return (act?"Акт выполненных работ":"Счёт на оплату")+" — "+document.get("number")+".pdf";
    }
    public static String amountText(long kopecks) {
        var format=NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
        format.setMinimumFractionDigits(kopecks%100==0?0:2);
        format.setMaximumFractionDigits(2);
        return format.format(BigDecimal.valueOf(kopecks,2)).replace('\u00a0',' ')+" ₽";
    }
    public static String instructions(Map<String,Object> document) {
        String amount=amountText(((Number)document.get("amount_kopecks")).longValue());
        String number=(String)document.get("number");
        return "Здравствуйте! Ваш заказ выполнен.\nК оплате: "+amount+".\n\n"
                +"Ниже — счёт № "+number+" и акт выполненных работ в двух PDF.\n\n"
                +"Пожалуйста, оплатите счёт переводом с расчётного счёта вашей организации или ИП "
                +"по реквизитам из приложенного счёта. Переведите всю сумму одним платежом, "
                +"в назначении платежа укажите номер счёта: "+number+".\n\n"
                +"После поступления средств оплата подтвердится автоматически. Спасибо!";
    }
}
