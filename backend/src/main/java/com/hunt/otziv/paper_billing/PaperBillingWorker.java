package com.hunt.otziv.paper_billing;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;

@Service @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
public class PaperBillingWorker {
    private final PaperBillingStore store;
    private final PaperBillingService service;
    private final PaperBillingSettings settings;
    private final PaperBillingBankClient bank;
    private final PaperBillingDelivery delivery;
    private final com.hunt.otziv.common_billing.api.BankPaperInvoices commonDelivery;
    private final PaperBillingPdfSignature signature;
    @Scheduled(fixedDelay=15000,initialDelay=20000)
    public void tick() {
        if (!settings.isEnabled() || !settings.isBankOperationsEnabled() || !bank.configured()) return;
        for (String id:store.pending()) {
            try { process(id); }
            catch(Exception e) { log.warn("Paper document {} processing failed ({})",id,e.getClass().getSimpleName()); }
        }
    }
    public void process(String id) {
        Map<String,Object> r=store.row(id);
        String stage=(String)r.get("stage");
        if (stage.equals("PENDING") || stage.equals("INVOICE_CREATED")) {
            boolean act=stage.equals("INVOICE_CREATED");
            String creating=act?"ACT_CREATING":"INVOICE_CREATING";
            if (!store.move(id,stage,creating)) return;
            try {
                String bankId=bank.create(r,service.payer((String)r.get("payer_json")),act);
                store.bankId(id,act,bankId,act?"ACT_CREATED":"INVOICE_CREATED");
            } catch(Exception e) {
                store.error(id,creating+"_UNKNOWN","Результат создания документа в банке неизвестен. Проверьте банк перед повтором.");
            }
            return;
        }
        if (stage.equals("ACT_CREATED")) {
            try {
                if (r.get("invoice_pdf")==null) store.pdf(id,false,signature.apply(bank.pdf(r,false),false));
                if (r.get("act_pdf")==null) store.pdf(id,true,signature.apply(bank.pdf(r,true),true));
                store.move(id,"ACT_CREATED","FILES_READY");
            } catch(Exception e) { store.error(id,"ACT_CREATED","Не удалось получить или подготовить PDF документов; проверьте подключение и настройку подписи. Подготовка будет повторена"); }
            return;
        }
        if (stage.equals("FILES_READY") || stage.equals("WAITING_PAYMENT") || stage.equals("PAID")) {
            if(!settings.isDeliveryEnabled()) return;
            if(!"SENT".equals(r.get("message_delivery"))) {
                if(!"PENDING".equals(r.get("message_delivery")) || !store.claimMessage(id)) return;
                String outcome="UNKNOWN";
                try {
                    var result=delivery.sendIntro(r);
                    outcome=result.sent() && result.messageId()!=null?"SENT":ClientMessageDelivery.isKnownUnsent(result)?"PENDING":"UNKNOWN";
                    store.messageDelivery(id,outcome,result.messageId());
                    if(!outcome.equals("SENT")) store.deliveryFailure(id,"Сообщение",result.errorCode(),result.errorMessage());
                } catch(Exception e) {outcome="UNKNOWN";store.messageDelivery(id,"UNKNOWN",null);}
                if(!outcome.equals("SENT")) return;
            }
            for (boolean act:new boolean[]{false,true}) {
                r=store.row(id);
                String state=(String)r.get(act?"act_delivery":"invoice_delivery");
                if (state.equals("SENT") || state.equals("UNKNOWN") || state.equals("SENDING") || !settings.isDeliveryEnabled()) continue;
                if (!store.claimDelivery(id,act)) continue;
                try {
                    var result=delivery.send(r,act);
                    store.delivery(id,act,result.sent() && result.messageId()!=null?"SENT":ClientMessageDelivery.isKnownUnsent(result)?"PENDING":"UNKNOWN",result.messageId());
                    if(!result.sent() || result.messageId()==null) store.deliveryFailure(id,act?"Акт":"Счёт",result.errorCode(),result.errorMessage());
                } catch(Exception e) { store.delivery(id,act,"UNKNOWN",null); }
            }
            r=store.row(id);
            if (r.get("invoice_delivery").equals("SENT") && r.get("act_delivery").equals("SENT")) {
                service.completeDelivery(id);
            }
        }
    }
}
