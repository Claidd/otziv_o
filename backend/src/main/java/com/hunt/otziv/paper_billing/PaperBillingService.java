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
    @org.springframework.beans.factory.annotation.Autowired private BillingPayerService payers;
    @org.springframework.beans.factory.annotation.Autowired private com.hunt.otziv.common_billing.api.BankPaperInvoiceParts parts;
    public Context context(Target target,Authentication auth) {
        long company=targets.company(target,auth);
        var companies=targets.payerCompanies(target,company);
        var choices=payers==null?new ArrayList<BillingPayerService.Choice>():new ArrayList<>(payers.choices(companies));
        if(payers!=null) addHistoryChoices(target,company,companies,choices);
        var positions=target.kind().equals("COMMON_INVOICE")?targets.positions(target):List.<com.hunt.otziv.common_billing.api.BankPaperInvoiceParts.Position>of();
        var docs=documents(target);
        Long parentId=null;
        if(parts!=null) {
            if(target.kind().equals("COMMON_PART"))parentId=parts.parent(target.id());
            else if(target.kind().equals("ORDER"))parentId=parts.parentForOrder(target.id());
        }
        return new Context(settings.isEnabled(),bank.configured()&&settings.isBankOperationsEnabled(),bank.fixture()||!settings.getDadataKey().isBlank(),
                settings.getAccountLast4(),company,(String)targets.companyInfo(company).get("company_title"),targets.previewAmount(target),
                payer(store.payer(company)),store.latest(target).map(store::document).orElse(null),choices,positions,docs,
                parentId,(String)targets.companyInfo(company).get("company_url_chat"),
                payers==null?null:store.jdbc().queryForList("SELECT error_text FROM paper_invoice_automatic_error WHERE target_kind=? AND target_id=?",String.class,target.kind(),target.id()).stream().findFirst().orElse(null));
    }
    private List<Document> documents(Target target) {
        return store.summaries(target).stream().map(store::document).toList();
    }
    public List<Document> documents(Target target,Authentication actor) {targets.company(target,actor);return documents(target);}
    private void addHistoryChoices(Target target,long company,List<Long> companies,List<BillingPayerService.Choice> choices) {
        var docs=new ArrayList<Map<String,Object>>();
        if(target.kind().equals("COMMON_INVOICE")) {
            var ids=targets.historyIds(target.id());
            if(!ids.isEmpty()) {
                String marks=String.join(",",Collections.nCopies(ids.size(),"?"));var params=new ArrayList<Object>(ids);params.addAll(ids);
                docs.addAll(store.jdbc().queryForList("SELECT id,payer_json,created_at FROM paper_invoice_document WHERE bank_invoice_id IS NOT NULL AND ((target_kind='COMMON_INVOICE' AND target_id IN ("+marks+")) OR parent_invoice_id IN ("+marks+")) ORDER BY created_at DESC LIMIT 100",params.toArray()));
            }
        } else if(target.kind().equals("ORDER")) {
            docs.addAll(store.jdbc().queryForList("SELECT id,payer_json,created_at FROM paper_invoice_document WHERE company_id=? AND bank_invoice_id IS NOT NULL ORDER BY created_at DESC LIMIT 100",company));
        }
        for(var row:docs) {
            var candidate=payer((String)row.get("payer_json"));
            if(choices.stream().anyMatch(c->c.payer().inn().equals(candidate.inn()) && c.payer().kpp().equals(candidate.kpp())) || !payers.historyAllowed(companies,candidate)) continue;
            choices.add(new BillingPayerService.Choice("history:"+row.get("id"),company,"",candidate,false,0,Objects.toString(row.get("created_at"),null)));
            if(choices.size()>=100) break;
        }
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
        payer.validate();long company=targets.company(target,auth);
        if(payers!=null) payers.remember(company,payer,auth.getName());else store.savePayer(company,encode(payer),auth.getName());
    }
    public Document issue(Target target,IssueRequest request,Authentication auth) {
        return issue(target,request,auth,true);
    }
    Document automaticIssue(Target target,IssueRequest request,Authentication auth) {return issue(target,request,auth,false);}
    private Document issue(Target target,IssueRequest request,Authentication auth,boolean rememberSettings) {
        return issue(target,request,auth,rememberSettings,null);
    }
    private record BankSetup(String account,String serviceName,String vatKind,String customer) {}
    private BankSetup bankSetup() {return new BankSetup(bank.supplierAccount(),bank.serviceName(),bank.vatKind(),bank.customer());}
    private Document issue(Target target,IssueRequest request,Authentication auth,boolean rememberSettings,BankSetup setup) {
        if (!bank.configured() || !settings.isBankOperationsEnabled()) throw problem("Выпуск документов Точки ещё не включён владельцем");
        if (request==null || request.payer()==null || !request.confirmedUnpaid()) throw problem("Подтвердите, что прежний счёт не оплачен");
        request.payer().validate();
        if(target.kind().equals("COMMON_INVOICE") && request.orderIds()!=null && !request.orderIds().isEmpty())
            return issuePart(target,request,auth,rememberSettings);
        long company=targets.company(target,auth);
        var existing=store.target(target);
        if (existing.isPresent()) {
            if(target.kind().equals("COMMON_PART") && !payer((String)existing.get().get("payer_json")).equals(request.payer())) throw problem("Этот запрос уже выпустил счёт с другими реквизитами. Обновите форму");
            return store.document(existing.get());
        }
        requireNoPartsForWholeTarget(target);
        if(store.hasUnresolvedReceipt(target,null)) throw problem("По прежнему счёту есть несверенное поступление. Новый комплект пока не выпускается");
        requireCancellationNotice(target);
        // Persist buyer independently: a bank/delivery failure must not discard completed form data.
        if(rememberSettings && !target.kind().equals("COMMON_PART") && request.payerChoiceId()==null) {
            if(payers!=null) payers.remember(company,request.payer(),auth.getName());else store.savePayer(company,encode(request.payer()),auth.getName());
        }
        var bankSettings=setup==null?bankSetup():setup;
        targets.configureCommon(target,auth);
        var transaction=new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            var prepared=targets.prepare(target,auth); // canonical Order locks precede the document row
            requireNoPartsForWholeTarget(target); // Fences an older full-invoice request racing a subset reservation.
            if(payers!=null) {
                var scope=targets.payerCompanies(target,company);payers.lockCompanies(scope);
                payers.validateSelection(scope,request.payerChoiceId(),request.payerChoiceVersion(),request.payer());
                if(!rememberSettings) {
                    var ids=target.kind().equals("ORDER")?List.of(target.id()):targets.positions(target).stream().map(com.hunt.otziv.common_billing.api.BankPaperInvoiceParts.Position::orderId).toList();
                    for(long order:ids) {var rule=targets.rule(order);if(rule==null)throw problem("Автоматический режим заказа изменился");payers.requireAutomatic(rule.companyId(),rule.choiceId(),request.payer());}
                }
            }
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
            store.insert(id,target,company,prepared.linkId(),prepared.amountKopecks(),encode(request.payer()),bankSettings.account(),bankSettings.customer(),client,group,auth.getName());
            targets.holdAutomation(target,false);
            if(rememberSettings) rememberRules(target,request.payer(),auth.getName());
            String description=bankSettings.serviceName();
            if(target.kind().equals("COMMON_PART")) {
                String orderNumbers=targets.positions(target).stream().map(p->Long.toString(p.orderId())).collect(java.util.stream.Collectors.joining(", "));
                description=bankSettings.serviceName()+". Заказы № "+orderNumbers;
                if(description.length()>500)description=description.substring(0,497)+"…";
            }
            store.jdbc().update("UPDATE paper_invoice_document SET service_name=?,vat_kind=? WHERE id=?",description,bankSettings.vatKind(),id);
            return store.document(store.row(id));
        });
    }
    private void requireNoPartsForWholeTarget(Target target) {
        if(parts!=null && target.kind().equals("COMMON_INVOICE") && !parts.partIds(target.id()).isEmpty())
            throw problem("Для этого общего счёта уже создавались отдельные комплекты. Выберите свободные заказы для нового счёта; единый счёт на всю сумму недоступен");
    }
    private Document issuePart(Target parent,IssueRequest request,Authentication actor,boolean rememberSettings) {
        String key=clean(request.requestKey());
        if(!key.matches("[A-Za-z0-9-]{8,64}")) throw problem("Обновите форму перед созданием счёта");
        targets.company(parent,actor);
        requireCancellationNotice(parent);
        targets.configureParts(parent,actor);
        var bankSettings=bankSetup(); // Read bank settings before taking canonical source locks.
        var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status->{
            var part=targets.createPart(parent,request.orderIds(),key,actor);
            requireCancellationNotice(parent);
            if(store.jdbc().queryForObject("SELECT COUNT(*) FROM paper_invoice_document WHERE parent_invoice_id=? AND cancelled_at IS NOT NULL AND cancel_message_delivery NOT IN ('SENT','SKIPPED')",Long.class,parent.id())>0)
                throw problem("Дождитесь подтверждённого уведомления об отмене прежнего комплекта");
            // The document path keeps the same canonical locks, snapshots and cancellation fences.
            return issue(part,new IssueRequest(request.payer(),request.confirmedUnpaid(),List.of(),null,request.payerChoiceId(),request.payerChoiceVersion()),actor,rememberSettings,bankSettings);
        });
    }
    private void rememberRules(Target target,Payer payer,String actor) {
        if(payers==null) return;
        if(target.kind().equals("ORDER")) {long company=targets.companyInfoId(target.id());var choice=payers.remember(company,payer,actor);targets.rememberRule(target.id(),choice.id());targets.automaticCompany(company,choice.id());return;}
        var remembered=new HashMap<Long,BillingPayerService.Choice>();
        if(target.kind().equals("COMMON_PART") || target.kind().equals("COMMON_INVOICE")) for(var position:targets.positions(target).stream().sorted(java.util.Comparator.comparingLong(com.hunt.otziv.common_billing.api.BankPaperInvoiceParts.Position::companyId)).toList()) {
            var choice=remembered.computeIfAbsent(position.companyId(),company->payers.remember(company,payer,actor));targets.rememberRule(position.orderId(),choice.id());targets.automaticCompany(position.companyId(),choice.id());
        }
    }
    public Map<String,Object> authorized(String id,Authentication auth) {
        Map<String,Object> r=store.row(id);
        if("COMMON_PART".equals(r.get("target_kind"))) targets.company(new Target("COMMON_PART",((Number)r.get("target_id")).longValue()),auth);
        else if(r.get("cancelled_at")!=null) targets.authorizeHistorical(((Number)r.get("company_id")).longValue(),auth);
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
            markDelivered(target,id,row);
        });
    }
    /**
     * A manager sent the message, invoice and act to the client by hand (for example after an
     * unconfirmed automatic send). The system stops sending them and completes delivery exactly as
     * for a confirmed automatic send: payment link, waiting for payment and reminders.
     */
    public Document confirmManualDelivery(String id,Authentication auth) {
        var before=authorized(id,auth);
        var target=new Target((String)before.get("target_kind"),((Number)before.get("target_id")).longValue());
        String actor=auth==null || auth.getName()==null ? "unknown" : auth.getName();
        var transaction=new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(tx -> {
            targets.lockDelivery(target);
            var row=store.jdbc().queryForMap("SELECT * FROM paper_invoice_document WHERE id=? FOR UPDATE",id);
            if(row.get("cancel_requested_at")!=null) throw problem("Счёт отменяется или отменён; подтверждать отправку не нужно");
            if(row.get("delivery_completed_at")!=null) return;
            if(!Set.of("FILES_READY","WAITING_PAYMENT","PAID").contains(row.get("stage")))
                throw problem("Счёт и акт ещё не сформированы в банке. Подтвердить отправку можно после формирования документов");
            if(List.of("message_delivery","invoice_delivery","act_delivery").stream().anyMatch(column->"SENDING".equals(row.get(column))))
                throw problem("Система прямо сейчас отправляет документы. Дождитесь результата и обновите окно");
            store.jdbc().update("""
                UPDATE paper_invoice_document SET message_delivery='SENT',invoice_delivery='SENT',act_delivery='SENT',
                work_failures=0,updated_at=NOW(6) WHERE id=?
                """,id);
            markDelivered(target,id,store.jdbc().queryForMap("SELECT * FROM paper_invoice_document WHERE id=?",id));
            store.event(id,"manual-delivery","Бумажный счёт № "+row.get("number")+": "+actor
                    +" подтвердил(а), что сообщение, счёт и акт отправлены клиенту вручную. Напоминания об оплате запущены.");
        });
        return store.document(store.row(id));
    }
    private void markDelivered(Target target,String id,Map<String,Object> row) {
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
        if(target.kind().equals("COMMON_PART") && row.get("paid_at")==null)
            store.jdbc().update("UPDATE paper_invoice_document SET paper_next_reminder_at=COALESCE(paper_next_reminder_at,?) WHERE id=?",parts.nextReminder(target.id()),id);
    }
}
