package com.hunt.otziv.paper_billing;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class PaperBillingStore {
    private final JdbcTemplate jdbc;
    private final com.hunt.otziv.common_billing.api.BankPaperInvoices common;
    public Map<String,Object> row(String id) {
        var rows=jdbc.queryForList("SELECT * FROM paper_invoice_document WHERE id=?",id);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Документ не найден");
        return rows.getFirst();
    }
    public Optional<Map<String,Object>> target(Target target) {
        return jdbc.queryForList("SELECT * FROM paper_invoice_document WHERE target_kind=? AND target_id=?",target.kind(),target.id()).stream().findFirst();
    }
    public Document document(Map<String,Object> r) {
        return new Document((String)r.get("id"),(String)r.get("number"),((Number)r.get("amount_kopecks")).longValue(),
                (String)r.get("stage"),(String)r.get("invoice_delivery"),(String)r.get("act_delivery"),(String)r.get("error_text"),
                r.get("paid_at") instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : r.get("paid_at") instanceof LocalDateTime time ? time : null);
    }
    public String composition(Target target) {
        if(target.kind().equals("COMMON_INVOICE")) return common.composition(target.id());
        Object rows=List.of(target.id());
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public String payer(long company) {
        return jdbc.queryForList("SELECT payer_json FROM company_billing_payer WHERE company_id=?",String.class,company).stream().findFirst().orElse(null);
    }
    @org.springframework.transaction.annotation.Transactional
    public void savePayer(long company,String json,String actor) {
        jdbc.update("""
            INSERT INTO company_billing_payer(company_id,payer_json,updated_by,updated_at) VALUES(?,?,?,NOW(6))
            ON DUPLICATE KEY UPDATE payer_json=VALUES(payer_json),updated_by=VALUES(updated_by),updated_at=NOW(6)
            """,company,json,actor);
        jdbc.update("INSERT INTO company_billing_payer_audit(company_id,payer_json,changed_by,changed_at) VALUES(?,?,?,NOW(6))",company,json,actor);
    }
    public void insert(String id,Target t,long company,Long link,long amount,String payer,String account,String customer,
            String client,String group,String actor) {
        jdbc.update("""
            INSERT INTO paper_invoice_document(id,target_kind,target_id,company_id,payment_link_id,number,amount_kopecks,
            payer_json,supplier_account,customer_code,composition_hash,client_id,group_id,created_by,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(6),NOW(6))
            """,id,t.kind(),t.id(),company,link,"PB-"+t.id()+"-"+id.substring(0,8).toUpperCase(Locale.ROOT),amount,payer,account,customer,composition(t),client,group,actor);
        if (link!=null) jdbc.update("UPDATE payment_links SET paper_bank_document_id=? WHERE id=?",id,link);
        if (t.kind().equals("COMMON_INVOICE")) common.bindDocument(t.id(),id);
    }
    public boolean move(String id,String from,String to) {
        return jdbc.update("UPDATE paper_invoice_document SET stage=?,error_text=NULL,updated_at=NOW(6) WHERE id=? AND stage=?",to,id,from)==1;
    }
    public void bankId(String id,boolean act,String bankId,String stage) {
        jdbc.update("UPDATE paper_invoice_document SET "+(act?"bank_act_id":"bank_invoice_id")+"=?,error_text=IF(stage='NEEDS_REVIEW',error_text,NULL),stage=IF(stage='NEEDS_REVIEW',stage,?),updated_at=NOW(6) WHERE id=?",
                bankId,stage,id);
    }
    public void pdf(String id,boolean act,PaperBillingPdfSignature.Pdf pdf) {
        String prefix=act?"act":"invoice";
        int changed=jdbc.update("UPDATE paper_invoice_document SET "+prefix+"_pdf=?,"+prefix+"_original_pdf=?,signature_sha256=?,updated_at=NOW(6) WHERE id=? AND (signature_sha256 IS NULL OR signature_sha256 <=> ?)",
                pdf.delivered(),pdf.original(),pdf.signatureSha256(),id,pdf.signatureSha256());
        if (changed!=1) throw new IllegalStateException("Подпись комплекта изменилась; нужна сверка");
    }
    public void error(String id,String state,String message) {
        String text=message==null?"Операция не завершена":message.substring(0,Math.min(700,message.length()));
        jdbc.update("UPDATE paper_invoice_document SET stage=?,error_text=?,updated_at=NOW(6) WHERE id=? AND (stage<>'NEEDS_REVIEW' OR ?='NEEDS_REVIEW') AND (?<>'NEEDS_REVIEW' OR paid_at IS NULL)",state,text,id,state,state);
    }
    public boolean claimDelivery(String id,boolean act) {
        return jdbc.update("UPDATE paper_invoice_document SET "+(act?"act_delivery":"invoice_delivery")+"='SENDING',updated_at=NOW(6) WHERE id=? AND "+(act?"act_delivery":"invoice_delivery")+"='PENDING'",id)==1;
    }
    public void delivery(String id,boolean act,String state,String messageId) {
        jdbc.update("UPDATE paper_invoice_document SET "+(act?"act_delivery":"invoice_delivery")+"=?,"+(act?"act_message_id":"invoice_message_id")+"=?,updated_at=NOW(6) WHERE id=?",state,messageId,id);
    }
    public List<String> pending() {
        for (String column:List.of("invoice_delivery","act_delivery")) {
            jdbc.update("UPDATE paper_invoice_document SET "+column+"='UNKNOWN' WHERE "+column+"='SENDING' AND updated_at < DATE_SUB(NOW(),INTERVAL 5 MINUTE)");
        }
        // A process crash after a POST is ambiguous. Never send a second create automatically.
        jdbc.update("""
            UPDATE paper_invoice_document SET stage=CONCAT(stage,'_UNKNOWN'),error_text='Результат создания в банке неизвестен; нужна сверка',updated_at=NOW(6)
            WHERE stage IN ('INVOICE_CREATING','ACT_CREATING') AND updated_at < DATE_SUB(NOW(),INTERVAL 5 MINUTE)
            """);
        return jdbc.queryForList("""
            SELECT id FROM paper_invoice_document
            WHERE stage IN ('PENDING','INVOICE_CREATED','ACT_CREATED')
            OR (stage='FILES_READY' AND (invoice_delivery='PENDING' OR act_delivery='PENDING'
                OR (invoice_delivery='SENT' AND act_delivery='SENT')))
            ORDER BY created_at LIMIT 20
            """,String.class);
    }
    public JdbcTemplate jdbc() { return jdbc; }
}
