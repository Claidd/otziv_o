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
                payer(store.payer(company)),store.target(target).map(store::document).orElse(null));
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
        // Persist buyer independently: a bank/delivery failure must not discard completed form data.
        store.savePayer(company,encode(request.payer()),auth.getName());
        String account=bank.supplierAccount();
        targets.configureCommon(target,auth);
        var transaction=new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            var prepared=targets.prepare(target,auth); // canonical Order locks precede the document row
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
            store.jdbc().update("UPDATE paper_invoice_document SET service_name=?,vat_kind=? WHERE id=?",bank.serviceName(),bank.vatKind(),id);
            return store.document(store.row(id));
        });
    }
    public Map<String,Object> authorized(String id,Authentication auth) {
        Map<String,Object> r=store.row(id);
        targets.company(new Target((String)r.get("target_kind"),((Number)r.get("target_id")).longValue()),auth);
        return r;
    }
}
