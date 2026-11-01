package com.hunt.otziv.paper_billing;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hunt.otziv.manager.api.PaymentReviewOrderAccess;

@Service @RequiredArgsConstructor
public class BillingPayerService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PaymentReviewOrderAccess access;
    private final com.hunt.otziv.c_companies.api.PaperInvoiceCompanyData companies;
    public record Choice(String id,long companyId,String profileId,Payer payer,boolean primary,long version,String lastUsedAt) {}
    public record Save(Payer payer,Long version,boolean primary) {}
    public record Preference(boolean automatic,String choiceId) {}
    public Preference preference(long company,Authentication actor) {
        access.requireCompanyAccess(company,actor);var r=companies.read(company);
        return new Preference(Boolean.TRUE.equals(r.get("paper_billing_automatic")) || "1".equals(Objects.toString(r.get("paper_billing_automatic"))),
                (String)r.get("paper_billing_payer_choice_id"));
    }
    @Transactional
    public void preference(long company,Preference preference,Authentication actor) {
        access.requireCompanyAccess(company,actor);
        if(preference==null) throw problem("Выберите режим выставления счетов");
        jdbc.queryForList("SELECT company_id FROM companies WHERE company_id=? FOR UPDATE",company);
        if(preference.automatic() && choices(List.of(company)).stream().noneMatch(c->c.id().equals(preference.choiceId())))
            throw problem("Для автоматического выпуска выберите сохранённого плательщика");
        companies.automatic(company,preference.automatic(),preference.choiceId());
        audit(preference.choiceId()==null?"none":preference.choiceId(),company,"AUTOMATIC",null,null,actor.getName());
    }

    public List<Choice> list(long company,Authentication actor) {
        access.requireCompanyAccess(company,actor);
        return choices(List.of(company));
    }
    List<Choice> choices(Collection<Long> companies) {
        if(companies==null || companies.isEmpty()) return List.of();
        var ids=companies.stream().distinct().sorted().toList();
        return jdbc.queryForList("SELECT c.*,p.payer_json FROM company_billing_payer_choice c JOIN billing_payer_profile p ON p.id=c.profile_id WHERE c.deleted_at IS NULL AND c.company_id IN ("
                +String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY c.is_default DESC,c.last_used_at DESC,c.updated_at DESC,c.id",ids.toArray())
                .stream().map(this::choice).toList();
    }
    boolean historyAllowed(Collection<Long> companies,Payer payer) {
        for(long company:companies) if(jdbc.queryForObject("SELECT COUNT(*) FROM company_billing_payer_choice WHERE company_id=? AND inn=? AND kpp=? AND deleted_at IS NOT NULL",Long.class,company,payer.inn(),payer.kpp())>0) return false;
        return true;
    }
    void lockCompanies(Collection<Long> companies) {for(long company:companies.stream().distinct().sorted().toList())jdbc.queryForList("SELECT company_id FROM companies WHERE company_id=? FOR UPDATE",company);}
    void validateSelection(Collection<Long> scope,String id,Long version,Payer payer) {
        if(id==null)return;
        var found=choices(scope).stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->problem("Выбранные реквизиты удалены. Обновите список плательщиков"));
        if(version==null || found.version()!=version || !found.payer().equals(payer))throw problem("Выбранные реквизиты изменились. Обновите список плательщиков");
    }
    void requireAutomatic(long company,String choiceId,Payer payer) {
        var info=companies.read(company);
        if(!Boolean.TRUE.equals(info.get("paper_billing_automatic")) && !"1".equals(Objects.toString(info.get("paper_billing_automatic"))))throw problem("Автоматическое выставление отключено");
        var choices=choices(List.of(company));
        var current=choices.stream().filter(c->c.id().equals(choiceId)).findFirst().orElseGet(()->choices.stream().filter(c->c.id().equals(info.get("paper_billing_payer_choice_id"))).findFirst().orElse(null));
        if(current==null || !current.payer().equals(payer))throw problem("Реквизиты автоматического счёта изменились или удалены. Требуется проверка");
    }
    private Choice choice(Map<String,Object> row) {
        try { return new Choice((String)row.get("id"),((Number)row.get("company_id")).longValue(),(String)row.get("profile_id"),
                json.readValue((String)row.get("payer_json"),Payer.class),Boolean.TRUE.equals(row.get("is_default")) || "1".equals(Objects.toString(row.get("is_default"))),
                ((Number)row.get("version")).longValue(),Objects.toString(row.get("last_used_at"),null)); }
        catch(Exception failure) {throw problem("Не удалось прочитать сохранённого плательщика");}
    }
    @Transactional
    public Choice save(long company,String choiceId,Save request,Authentication actor) {
        access.requireCompanyAccess(company,actor);
        if(request==null || request.payer()==null) throw problem("Заполните реквизиты плательщика");
        return saveScoped(company,choiceId,request,actor.getName(),false);
    }
    /** Caller has already authorized every selected order in its billing scope. */
    @Transactional
    Choice remember(long company,Payer payer,String actor) {
        return saveScoped(company,null,new Save(payer,null,false),actor,true);
    }
    private Choice saveScoped(long company,String choiceId,Save request,String actor,boolean used) {
        request.payer().validate();
        jdbc.queryForList("SELECT company_id FROM companies WHERE company_id=? FOR UPDATE",company);
        Map<String,Object> before=null;
        if(choiceId!=null) {
            var rows=jdbc.queryForList("SELECT * FROM company_billing_payer_choice WHERE id=? AND company_id=? AND deleted_at IS NULL FOR UPDATE",choiceId,company);
            if(rows.isEmpty()) throw problem("Реквизиты уже удалены. Обновите список");
            before=rows.getFirst();
            if(request.version()==null || request.version().longValue()!=((Number)before.get("version")).longValue())
                throw problem("Реквизиты изменены другим сотрудником. Обновите список");
        }
        var payer=request.payer(); String text;
        try {text=json.writeValueAsString(payer);} catch(Exception e) {throw new IllegalStateException(e);}
        String fingerprint;
        try {fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e) {throw new IllegalStateException(e);}
        jdbc.update("INSERT IGNORE INTO billing_payer_profile(id,fingerprint,inn,kpp,payer_json,created_by) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),fingerprint,payer.inn(),payer.kpp(),text,actor);
        String profile=jdbc.queryForObject("SELECT id FROM billing_payer_profile WHERE fingerprint=?",String.class,fingerprint);
        var same=jdbc.queryForList("SELECT * FROM company_billing_payer_choice WHERE company_id=? AND inn=? AND kpp=? FOR UPDATE",company,payer.inn(),payer.kpp());
        if(before!=null && !same.isEmpty() && !same.getFirst().get("id").equals(choiceId)) throw problem("Этот ИНН и КПП уже сохранены у компании. Выберите существующего плательщика");
        String id=before!=null?choiceId:!same.isEmpty()?(String)same.getFirst().get("id"):UUID.randomUUID().toString();
        var prior=before!=null?before:same.isEmpty()?null:same.getFirst();
        boolean primary=request.primary() || prior!=null && (Boolean.TRUE.equals(prior.get("is_default")) || "1".equals(Objects.toString(prior.get("is_default"))))
                || jdbc.queryForObject("SELECT COUNT(*) FROM company_billing_payer_choice WHERE company_id=? AND deleted_at IS NULL AND is_default=1",Long.class,company)==0;
        if(primary) jdbc.update("UPDATE company_billing_payer_choice SET is_default=0,version=version+1 WHERE company_id=? AND id<>? AND is_default=1",company,id);
        // Explicitly saving the same identity updates this company only; other links keep their immutable profile.
        jdbc.update("""
                INSERT INTO company_billing_payer_choice(id,company_id,profile_id,inn,kpp,is_default,last_used_at)
                VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE profile_id=VALUES(profile_id),inn=VALUES(inn),kpp=VALUES(kpp),
                is_default=VALUES(is_default),deleted_at=NULL,version=version+1,updated_at=NOW(6),last_used_at=IFNULL(VALUES(last_used_at),last_used_at)
                """,id,company,profile,payer.inn(),payer.kpp(),primary,used?LocalDateTime.now():null);
        audit(id,company,before==null?"SAVE":"EDIT",before==null?null:(String)before.get("profile_id"),profile,actor);
        synchronizeLegacy(company,actor);
        return choices(List.of(company)).stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();
    }
    @Transactional
    public void delete(long company,String id,long version,Authentication actor) {
        access.requireCompanyAccess(company,actor);
        jdbc.queryForList("SELECT company_id FROM companies WHERE company_id=? FOR UPDATE",company);
        var rows=jdbc.queryForList("SELECT * FROM company_billing_payer_choice WHERE id=? AND company_id=? AND deleted_at IS NULL FOR UPDATE",id,company);
        if(rows.isEmpty()) return;
        var row=rows.getFirst();
        if(((Number)row.get("version")).longValue()!=version) throw problem("Реквизиты изменены. Обновите список перед удалением");
        jdbc.update("UPDATE company_billing_payer_choice SET deleted_at=NOW(6),is_default=0,version=version+1,updated_at=NOW(6) WHERE id=?",id);
        audit(id,company,"DELETE",(String)row.get("profile_id"),null,actor.getName());
        var remaining=choices(List.of(company));
        if(remaining.stream().noneMatch(Choice::primary) && !remaining.isEmpty()) jdbc.update("UPDATE company_billing_payer_choice SET is_default=1,version=version+1 WHERE id=?",remaining.getFirst().id());
        synchronizeLegacy(company,actor.getName());
    }
    private void synchronizeLegacy(long company,String actor) {
        var list=choices(List.of(company));
        var primary=list.stream().filter(Choice::primary).findFirst();
        if(primary.isEmpty()) {jdbc.update("DELETE FROM company_billing_payer WHERE company_id=?",company);return;}
        try {String payload=json.writeValueAsString(primary.get().payer());
            jdbc.update("INSERT INTO company_billing_payer(company_id,payer_json,updated_by,updated_at) VALUES(?,?,?,NOW(6)) ON DUPLICATE KEY UPDATE payer_json=VALUES(payer_json),updated_by=VALUES(updated_by),updated_at=NOW(6)",company,payload,actor);
        } catch(Exception e) {throw new IllegalStateException(e);}
    }
    private void audit(String id,long company,String action,String before,String after,String actor) {
        jdbc.update("INSERT INTO billing_payer_choice_audit(choice_id,company_id,action,before_profile_id,after_profile_id,actor) VALUES(?,?,?,?,?,?)",id,company,action,before,after,actor);
    }
}
