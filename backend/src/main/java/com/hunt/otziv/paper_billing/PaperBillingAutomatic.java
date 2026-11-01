package com.hunt.otziv.paper_billing;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import com.hunt.otziv.p_products.api.PaperInvoiceOrderData;
import com.hunt.otziv.common_billing.api.BankPaperInvoiceParts;
import com.hunt.otziv.c_companies.api.PaperInvoiceCompanyData;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Internal-only worker. Its fixed admin identity can issue only persisted opt-in rules.
 * No endpoint accepts this identity, target discovery is server-owned and every issue
 * revalidates canonical locks, membership, readiness and document idempotency. */
@Service @RequiredArgsConstructor
public class PaperBillingAutomatic {
    private final PaperBillingSettings settings;
    private final PaperBillingBankClient bank;
    private final PaperBillingService service;
    private final PaperBillingStore store;
    private final BillingPayerService payers;
    private final PaperInvoiceOrderData orders;
    private final BankPaperInvoiceParts parts;
    private final PaperInvoiceCompanyData companies;
    private long cursor;
    @Scheduled(fixedDelay=30000,initialDelay=45000)
    public synchronized void tick() {
        if(!settings.isEnabled() || !settings.isBankOperationsEnabled() || !bank.configured()) return;
        var previous=SecurityContextHolder.getContext();var context=SecurityContextHolder.createEmptyContext();
        var actor=new UsernamePasswordAuthenticationToken("paper-invoice-automation",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        context.setAuthentication(actor);SecurityContextHolder.setContext(context);
        try {
            var candidates=orders.automaticCandidates(cursor,100);if(candidates.isEmpty()){cursor=0;return;}
            var done=new HashSet<Long>();
            for(var candidate:candidates) {
                cursor=candidate.orderId();Long parent=parts.parentForOrder(candidate.orderId());
                Target target=parent==null?new Target("ORDER",candidate.orderId()):new Target("COMMON_INVOICE",parent);
                if(parent!=null && !done.add(parent)) continue;
                if(store.jdbc().queryForObject("SELECT COUNT(*) FROM paper_invoice_automatic_error WHERE target_kind=? AND target_id=? AND next_attempt_at>NOW(6)",Long.class,target.kind(),target.id())>0)continue;
                try {
                    if(parent==null) {
                        if(store.latest(target).isPresent()) continue; // cancellation/unknown outcomes always require an explicit new issue
                        var payer=active(candidate);
                        service.automaticIssue(target,new IssueRequest(payer.payer(),true),actor);
                    } else {
                        var groups=new LinkedHashMap<String,List<Long>>();var profiles=new HashMap<String,Payer>();
                        String invalid=null;
                        for(var position:parts.positions(parent)) {
                            if(!position.ready() || position.paid() || position.documentId()!=null) continue;
                            var rule=orders.automaticOrder(position.orderId());if(rule==null)continue;
                            if(!enabled(companies.read(rule.companyId())))continue;
                            try {
                                var payer=active(rule);groups.computeIfAbsent(payer.profileId(),unused->new ArrayList<>()).add(rule.orderId());profiles.put(payer.profileId(),payer.payer());
                            } catch(org.springframework.web.server.ResponseStatusException failure) {invalid=failure.getReason();}
                        }
                        for(var group:groups.entrySet()) {
                            var ids=group.getValue().stream().sorted().toList();
                            String key="auto-"+UUID.nameUUIDFromBytes(ids.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            service.automaticIssue(target,new IssueRequest(profiles.get(group.getKey()),true,ids,key),actor);
                        }
                        if(invalid!=null)throw problem(invalid);
                    }
                    store.jdbc().update("DELETE FROM paper_invoice_automatic_error WHERE target_kind=? AND target_id=?",target.kind(),target.id());
                } catch(Exception failure) {
                    String reason=failure instanceof org.springframework.web.server.ResponseStatusException e?e.getReason():"Автоматический выпуск ожидает проверки состояния заказов";
                    store.jdbc().update("INSERT INTO paper_invoice_automatic_error(target_kind,target_id,error_text,next_attempt_at) VALUES(?,?,?,DATE_ADD(NOW(6),INTERVAL 1 MINUTE)) ON DUPLICATE KEY UPDATE error_text=VALUES(error_text),next_attempt_at=VALUES(next_attempt_at),updated_at=NOW(6)",target.kind(),target.id(),reason);
                }
            }
        } finally {SecurityContextHolder.setContext(previous);}
    }
    private BillingPayerService.Choice active(PaperInvoiceOrderData.AutomaticOrder rule) {
        var info=companies.read(rule.companyId());
        if(!enabled(info)) throw problem("Автоматическое выставление у компании отключено");
        var choices=payers.choices(List.of(rule.companyId()));
        var original=choices.stream().filter(p->p.id().equals(rule.choiceId())).findFirst();
        if(original.isPresent())return original.get();
        return choices.stream().filter(p->p.id().equals(info.get("paper_billing_payer_choice_id"))).findFirst()
                .orElseThrow(()->problem("Сохранённые реквизиты удалены или не выбраны. Выберите плательщика в редакторе компании"));
    }
    private boolean enabled(Map<String,Object> info) {return Boolean.TRUE.equals(info.get("paper_billing_automatic")) || "1".equals(Objects.toString(info.get("paper_billing_automatic")));}
}
