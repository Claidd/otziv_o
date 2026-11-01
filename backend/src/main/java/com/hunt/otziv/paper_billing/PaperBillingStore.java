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
    @org.springframework.beans.factory.annotation.Autowired private com.hunt.otziv.common_billing.api.BankPaperInvoiceParts parts;
    public Map<String,Object> row(String id) {
        var rows=jdbc.queryForList("SELECT * FROM paper_invoice_document WHERE id=?",id);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Документ не найден");
        return rows.getFirst();
    }
    public Optional<Map<String,Object>> target(Target target) {
        return jdbc.queryForList("SELECT * FROM paper_invoice_document WHERE target_kind=? AND target_id=? AND cancelled_at IS NULL",target.kind(),target.id()).stream().findFirst();
    }
    public Optional<Map<String,Object>> latest(Target target) {
        return jdbc.queryForList("SELECT * FROM paper_invoice_document WHERE target_kind=? AND target_id=? ORDER BY created_at DESC,id DESC LIMIT 1",target.kind(),target.id()).stream().findFirst();
    }
    public List<Map<String,Object>> summaries(Target target) {
        String columns="id,number,amount_kopecks,stage,invoice_delivery,act_delivery,error_text,paid_at,cancel_message_delivery,cancel_reason,cancel_replacement_state,cancel_replacement_error,target_kind,target_id,payer_json,created_at";
        if(target.kind().equals("COMMON_INVOICE"))return jdbc.queryForList("SELECT "+columns+" FROM paper_invoice_document WHERE (target_kind=? AND target_id=?) OR parent_invoice_id=? ORDER BY created_at DESC,id DESC LIMIT 200",target.kind(),target.id(),target.id());
        return jdbc.queryForList("SELECT "+columns+" FROM paper_invoice_document WHERE target_kind=? AND target_id=? ORDER BY created_at DESC,id DESC LIMIT 100",target.kind(),target.id());
    }
    public void event(String id,String key,String body) {
        jdbc.update("INSERT IGNORE INTO paper_invoice_event(document_id,event_key,body) VALUES(?,?,?)",id,key,body);
    }
    public boolean hasUnresolvedReceipt(Target target,String exceptDocument) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM paper_invoice_bank_receipt r JOIN paper_invoice_document d ON d.id=r.document_id
            WHERE d.target_kind=? AND d.target_id=? AND r.state='NEEDS_REVIEW' AND (? IS NULL OR d.id<>?)
            """,Long.class,target.kind(),target.id(),exceptDocument,exceptDocument)>0;
    }
    public Document document(Map<String,Object> r) {
        Payer payer=null;
        try {if(r.get("payer_json")!=null) payer=new com.fasterxml.jackson.databind.ObjectMapper().readValue((String)r.get("payer_json"),Payer.class);} catch(Exception failure) {throw problem("Повреждены реквизиты банковского документа");}
        String kind=(String)r.get("target_kind");Long target=((Number)r.get("target_id")).longValue();
        var orderIds="COMMON_PART".equals(kind)?parts.members(target).stream().map(com.hunt.otziv.common_billing.api.BankPaperInvoiceParts.Position::orderId).toList():"ORDER".equals(kind)?List.of(target):List.<Long>of();
        return new Document((String)r.get("id"),(String)r.get("number"),((Number)r.get("amount_kopecks")).longValue(),
                (String)r.get("stage"),(String)r.get("invoice_delivery"),(String)r.get("act_delivery"),(String)r.get("error_text"),
                r.get("paid_at") instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : r.get("paid_at") instanceof LocalDateTime time ? time : null,
                (String)r.get("cancel_message_delivery"),(String)r.get("cancel_reason"),
                (String)r.get("cancel_replacement_state"),(String)r.get("cancel_replacement_error"),kind,target,payer,orderIds);
    }
    public String composition(Target target) {
        if(target.kind().equals("COMMON_INVOICE")) return common.composition(target.id());
        if(target.kind().equals("COMMON_PART")) return parts.composition(target.id());
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
        if (t.kind().equals("COMMON_PART")) {parts.bind(t.id(),id);jdbc.update("UPDATE paper_invoice_document SET parent_invoice_id=? WHERE id=?",parts.parent(t.id()),id);}
    }
    public boolean move(String id,String from,String to) {
        return jdbc.update("UPDATE paper_invoice_document SET stage=?,error_text=NULL,updated_at=NOW(6) WHERE id=? AND stage=?",to,id,from)==1;
    }
    public void bankId(String id,boolean act,String bankId,String stage) {
        jdbc.update("UPDATE paper_invoice_document SET "+(act?"bank_act_id":"bank_invoice_id")+"=?,error_text=IF(cancel_requested_at IS NOT NULL OR stage IN ('NEEDS_REVIEW','PAID'),error_text,NULL),stage=IF(cancel_requested_at IS NOT NULL OR stage IN ('NEEDS_REVIEW','PAID'),stage,?),updated_at=NOW(6) WHERE id=?",
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
        jdbc.update("UPDATE paper_invoice_document SET stage=?,error_text=?,updated_at=NOW(6) WHERE id=? AND cancel_requested_at IS NULL AND (stage<>'NEEDS_REVIEW' OR ?='NEEDS_REVIEW') AND (?<>'NEEDS_REVIEW' OR paid_at IS NULL) AND (stage<>'PAID' OR ?='PAID')",state,text,id,state,state,state);
    }
    public boolean claimDelivery(String id,boolean act) {
        return jdbc.update("UPDATE paper_invoice_document SET "+(act?"act_delivery":"invoice_delivery")+"='SENDING',updated_at=NOW(6) WHERE id=? AND cancel_requested_at IS NULL AND stage IN ('FILES_READY','WAITING_PAYMENT','PAID') AND "+(act?"act_delivery":"invoice_delivery")+"='PENDING'",id)==1;
    }
    public boolean claimMessage(String id) {
        return jdbc.update("UPDATE paper_invoice_document SET message_delivery='SENDING',updated_at=NOW(6) WHERE id=? AND cancel_requested_at IS NULL AND stage IN ('FILES_READY','WAITING_PAYMENT','PAID') AND message_delivery='PENDING'",id)==1;
    }
    public void messageDelivery(String id,String state,String messageId) {
        jdbc.update("UPDATE paper_invoice_document SET message_delivery=?,message_id=?,updated_at=NOW(6) WHERE id=?",state,messageId,id);
    }
    public void delivery(String id,boolean act,String state,String messageId) {
        jdbc.update("UPDATE paper_invoice_document SET "+(act?"act_delivery":"invoice_delivery")+"=?,"+(act?"act_message_id":"invoice_message_id")+"=?,updated_at=NOW(6) WHERE id=?",state,messageId,id);
    }
    public List<String> pending() {
        for (String column:List.of("message_delivery","invoice_delivery","act_delivery")) {
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
            OR (stage='FILES_READY' AND (message_delivery='PENDING' OR (message_delivery='SENT' AND
                (invoice_delivery='PENDING' OR act_delivery='PENDING' OR (invoice_delivery='SENT' AND act_delivery='SENT')))))
            OR (stage IN ('WAITING_PAYMENT','PAID') AND delivery_completed_at IS NULL
                AND message_delivery='SENT' AND invoice_delivery='SENT' AND act_delivery='SENT')
            ORDER BY created_at LIMIT 20
            """,String.class);
    }
    public JdbcTemplate jdbc() { return jdbc; }
}
