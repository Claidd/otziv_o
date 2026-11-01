package com.hunt.otziv.paper_billing;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service @RequiredArgsConstructor
public class PaperBillingService {
    private final PaperBillingStore store;
    private final PaperBillingTargets targets;
    private final PaperBillingSettings settings;
    private final PaperBillingBankClient bank;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;
    public Context context(Target target,Authentication auth) {
        long company=targets.company(target,auth);
        return new Context(settings.isEnabled(),bank.configured()&&settings.isBankOperationsEnabled(),bank.fixture()||!settings.getDadataKey().isBlank(),
                settings.getAccountLast4(),company,(String)targets.companyInfo(company).get("company_title"),targets.previewAmount(target),
                payer(store.payer(company)),store.latest(target).map(store::document).orElse(null));
    }
    Payer payer(String source) {
        if (source==null) return null;
        try { return json.readValue(source,Payer.class); }
        catch(Exception e) { throw problem("Сохранённые реквизиты повреждены; обратитесь к владельцу"); }
    }
    String encode(Payer payer) {
        try { return json.writeValueAsString(payer); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public void savePayer(Target target,Payer payer,Authentication auth) {
        payer.validate(); store.savePayer(targets.company(target,auth),encode(payer),auth.getName());
    }
    public Document issue(Target target,IssueRequest request,Authentication auth) {
        if (!bank.configured() || !settings.isBankOperationsEnabled()) throw problem("Выпуск документов Точки ещё не включён владельцем");
        if (request==null || request.payer()==null || !request.confirmedUnpaid()) throw problem("Подтвердите, что прежний счёт не оплачен");
        request.payer().validate();
        long company=targets.company(target,auth);
        var existing=store.target(target);
        if (existing.isPresent()) return store.document(existing.get());
        if(store.hasUnresolvedReceipt(target,null)) throw problem("По прежнему счёту есть несверенное поступление. Новый комплект пока не выпускается");
        requireCancellationNotice(target);
        // Persist buyer independently: a bank/delivery failure must not discard completed form data.
        store.savePayer(company,encode(request.payer()),auth.getName());
        String account=bank.supplierAccount();
        String serviceName=bank.serviceName();
        String vatKind=bank.vatKind();
        targets.configureCommon(target,auth);
        var transaction=new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            var prepared=targets.prepare(target,auth); // canonical Order locks precede the document row
            if(store.hasUnresolvedReceipt(target,null)) throw problem("Обнаружено несверенное поступление по прежнему счёту. Нужна ручная сверка");
            requireCancellationNotice(target);
            if (prepared.companyId()!=company) throw problem("Компания-плательщик изменилась. Обновите реквизиты перед выпуском документов");
            var current=store.target(target);
            if (current.isPresent()) return store.document(current.get());
            if (prepared.amountKopecks()<=0) throw problem("Сумма счёта должна быть положительной");
            var info=targets.companyInfo(company);
            String group=clean((String)info.get("company_group_id"));
            String client=targets.deliveryClient(target,(String)info.get("client_id"));
            String url=clean((String)info.get("company_url_chat"));
            if (url.contains("t.me/")) group="TELEGRAM:"+info.get("company_telegram_group_chat_id");
            else if (url.contains("max.ru/")) group="MAX:"+info.get("company_max_group_chat_id");
            else group="WHATSAPP:"+group;
            if (group.endsWith(":") || group.endsWith(":null") || (group.startsWith("WHATSAPP:") && client.isBlank()))
                throw problem("Сначала привяжите группу заказчика и отправителя");
            String id=UUID.randomUUID().toString();
            store.insert(id,target,company,prepared.linkId(),prepared.amountKopecks(),encode(request.payer()),account,bank.customer(),client,group,auth.getName());
            targets.holdAutomation(target,false);
            store.jdbc().update("UPDATE paper_invoice_document SET service_name=?,vat_kind=? WHERE id=?",serviceName,vatKind,id);
            return store.document(store.row(id));
        });
    }
    public Map<String,Object> authorized(String id,Authentication auth) {
        Map<String,Object> r=store.row(id);
        if(r.get("cancelled_at")!=null) targets.authorizeHistorical(((Number)r.get("company_id")).longValue(),auth);
        else targets.company(new Target((String)r.get("target_kind"),((Number)r.get("target_id")).longValue()),auth);
        return r;
    }
    void holdForCancellationReview(Map<String,Object> row) {
        targets.holdForPaymentReview(new Target((String)row.get("target_kind"),((Number)row.get("target_id")).longValue()));
    }
    private void requireCancellationNotice(Target target) {
        store.latest(target).filter(r->r.get("cancelled_at")!=null).ifPresent(row->{
            if("PENDING".equals(row.get("cancel_replacement_state")))
                throw problem("После отмены уже запрошен обычный счёт. Дождитесь завершения перехода");
            if(!Set.of("SENT","SKIPPED").contains(Objects.toString(row.get("cancel_message_delivery"),"")))
                throw problem("Дождитесь подтверждения уведомления заказчика об отмене прежнего счёта. При неизвестном результате доставки нужна сверка");
        });
    }
    public void completeDelivery(String id) {
        var before=store.row(id);
        var target=new Target((String)before.get("target_kind"),((Number)before.get("target_id")).longValue());
        var transaction=new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(tx -> {
            targets.lockDelivery(target); // Order / common-invoice locks always precede the document lock.
            var row=store.jdbc().queryForMap("SELECT * FROM paper_invoice_document WHERE id=? FOR UPDATE",id);
            if(row.get("cancel_requested_at")!=null) return;
            if(row.get("delivery_completed_at")!=null) return;
            if(!"SENT".equals(row.get("message_delivery")) || !"SENT".equals(row.get("invoice_delivery")) || !"SENT".equals(row.get("act_delivery")))
                throw problem("Доставка сообщения и обоих PDF ещё не подтверждена");
            if(!Set.of("FILES_READY","WAITING_PAYMENT","PAID").contains(row.get("stage"))) throw problem("Документы требуют сверки");
            targets.delivered(target,id);
            if(row.get("payment_link_id")!=null) {
                int updated=store.jdbc().update("""
                    UPDATE payment_links SET paper_invoice_issued_at=COALESCE(paper_invoice_issued_at,NOW(6)),
                    initiated_at=COALESCE(initiated_at,NOW(6)),status=IF(status='CREATED','WAITING_MANUAL_PAYMENT',status)
                    WHERE id=? AND payment_method='OWNER_PAPER_INVOICE' AND paper_bank_document_id=?
                    """,row.get("payment_link_id"),id);
                if(updated!=1) throw problem("Платёжная запись банковского счёта изменилась");
            }
            store.jdbc().update("UPDATE paper_invoice_document SET delivery_completed_at=NOW(6),stage=IF(paid_at IS NULL,'WAITING_PAYMENT','PAID'),error_text=NULL,updated_at=NOW(6) WHERE id=?",id);
        });
    }
}
