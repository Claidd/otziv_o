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
public class PaperBillingStore implements com.hunt.otziv.client_messages.api.DeliveryQueueHealth, com.hunt.otziv.payments.api.AlternatePaperReceipt {
    private static final String WITH_FILES = "SELECT d.*,f.invoice_pdf,f.act_pdf,f.invoice_original_pdf,f.act_original_pdf "
            + "FROM paper_invoice_document d LEFT JOIN paper_invoice_files f ON f.document_id=d.id ";
    private final JdbcTemplate jdbc;
    private final com.hunt.otziv.common_billing.api.BankPaperInvoices common;
    @org.springframework.beans.factory.annotation.Autowired private com.hunt.otziv.common_billing.api.BankPaperInvoiceParts parts;
    public Map<String,Object> row(String id) {
        var rows=jdbc.queryForList(WITH_FILES+"WHERE d.id=?",id);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Документ не найден");
        return rows.getFirst();
    }
    public Optional<Map<String,Object>> target(Target target) {
        return jdbc.queryForList(WITH_FILES+"WHERE d.target_kind=? AND d.target_id=? AND d.cancelled_at IS NULL",target.kind(),target.id()).stream().findFirst();
    }
    public Optional<Map<String,Object>> latest(Target target) {
        return jdbc.queryForList(WITH_FILES+"WHERE d.target_kind=? AND d.target_id=? ORDER BY d.created_at DESC,d.id DESC LIMIT 1",target.kind(),target.id()).stream().findFirst();
    }
    public List<Map<String,Object>> summaries(Target target) {
        String columns="id,number,amount_kopecks,stage,invoice_delivery,act_delivery,message_delivery,delivery_completed_at,error_text,paid_at,cancel_message_delivery,cancel_reason,cancel_replacement_state,cancel_replacement_error,target_kind,target_id,payer_json,created_at";
        if(target.kind().equals("COMMON_INVOICE"))return jdbc.queryForList("SELECT "+columns+" FROM paper_invoice_document WHERE (target_kind=? AND target_id=?) OR parent_invoice_id=? ORDER BY created_at DESC,id DESC LIMIT 200",target.kind(),target.id(),target.id());
        return jdbc.queryForList("SELECT "+columns+" FROM paper_invoice_document WHERE target_kind=? AND target_id=? ORDER BY created_at DESC,id DESC LIMIT 100",target.kind(),target.id());
    }
    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean closeUnpaidDocument(String kind, long targetId, String documentId, long amountKopecks) {
        record Document(String id, long amount, boolean clear, boolean part) {}
        String scope = "COMMON_INVOICE".equals(kind)
                ? "((target_kind='COMMON_INVOICE' AND target_id=?) OR (target_kind='COMMON_PART' AND parent_invoice_id=?))"
                : "(target_kind='ORDER' AND target_id=? AND id=?)";
        Object[] args = "COMMON_INVOICE".equals(kind) ? new Object[]{targetId, targetId} : new Object[]{targetId, documentId};
        var documents = jdbc.query("""
                SELECT id,amount_kopecks,(paid_at IS NULL AND cancel_requested_at IS NULL
                  AND stage IN ('PENDING','INVOICE_CREATED','ACT_CREATED','FILES_READY','WAITING_PAYMENT')
                  AND 'SENDING' NOT IN (message_delivery,invoice_delivery,act_delivery)) AS clear_for_receipt, target_kind='COMMON_PART'
                FROM paper_invoice_document WHERE cancelled_at IS NULL AND """ + " " + scope + " ORDER BY id FOR UPDATE",
                (rs, n) -> new Document(rs.getString(1), rs.getLong(2), rs.getBoolean(3), rs.getBoolean(4)), args);
        if (documents.stream().noneMatch(d -> d.id().equals(documentId))
                || documents.stream().anyMatch(d -> !d.clear() || d.amount() <= 0 || !d.part() && d.amount() != amountKopecks)
                || documents.stream().mapToLong(Document::amount).reduce(0L, Math::addExact) > amountKopecks) return false;
        for (var doc : documents) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM paper_invoice_bank_receipt WHERE document_id=?)", Boolean.class, doc.id()))) return false;
        }
        String reason = "Долг закрыт оплатой по прежней банковской ссылке. Выданный бумажный документ требует сверки.";
        for (var doc : documents) {
            jdbc.update("UPDATE paper_invoice_document SET stage='NEEDS_REVIEW',paper_next_reminder_at=NULL,error_text=?,updated_at=NOW(6) WHERE id=?",
                    reason, doc.id());
            event(doc.id(), "PAID_BY_PREVIOUS_LINK", reason);
        }
        return true;
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
                (String)r.get("cancel_replacement_state"),(String)r.get("cancel_replacement_error"),kind,target,payer,orderIds,
                (String)r.get("message_delivery"),r.get("delivery_completed_at")!=null,
                jdbc.queryForObject("SELECT COALESCE(SUM(amount_kopecks),0) FROM paper_invoice_bank_receipt WHERE document_id=? AND state IN ('PARTIAL','APPLIED')",
                        Long.class, r.get("id")));
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
        String reference="PB-"+t.id()+"-"+id.substring(0,8).toUpperCase(Locale.ROOT);
        jdbc.update("""
            INSERT INTO paper_invoice_document(id,target_kind,target_id,company_id,payment_link_id,number,payment_reference,amount_kopecks,
            payer_json,supplier_account,customer_code,composition_hash,client_id,group_id,created_by,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(6),NOW(6))
            """,id,t.kind(),t.id(),company,link,reference,reference,amount,payer,account,customer,composition(t),client,group,actor);
        if (link!=null) jdbc.update("UPDATE payment_links SET paper_bank_document_id=? WHERE id=?",id,link);
        if (t.kind().equals("COMMON_INVOICE")) common.bindDocument(t.id(),id);
        if (t.kind().equals("COMMON_PART")) {parts.bind(t.id(),id);jdbc.update("UPDATE paper_invoice_document SET parent_invoice_id=? WHERE id=?",parts.parent(t.id()),id);}
    }
    public boolean move(String id,String from,String to) {
        return jdbc.update("UPDATE paper_invoice_document SET stage=?,error_text=NULL,work_failures=0,work_next_attempt_at=NOW(6),updated_at=NOW(6) WHERE id=? AND stage=? AND cancel_requested_at IS NULL",to,id,from)==1;
    }
    public void bankId(String id,boolean act,String bankId,String stage) {
        jdbc.update("UPDATE paper_invoice_document SET "+(act?"bank_act_id":"bank_invoice_id")+"=?,error_text=IF(cancel_requested_at IS NOT NULL OR stage IN ('NEEDS_REVIEW','PAID'),error_text,NULL),stage=IF(cancel_requested_at IS NOT NULL OR stage IN ('NEEDS_REVIEW','PAID'),stage,?),updated_at=NOW(6) WHERE id=?",
                bankId,stage,id);
    }
    @org.springframework.transaction.annotation.Transactional
    public void pdf(String id,boolean act,PaperBillingPdfSignature.Pdf pdf) {
        String prefix=act?"act":"invoice";
        int changed=jdbc.update("UPDATE paper_invoice_document SET signature_sha256=?,updated_at=NOW(6) WHERE id=? AND (signature_sha256 IS NULL OR signature_sha256 <=> ?)",
                pdf.signatureSha256(),id,pdf.signatureSha256());
        if (changed!=1) throw new IllegalStateException("Подпись комплекта изменилась; нужна сверка");
        jdbc.update("INSERT INTO paper_invoice_files(document_id,"+prefix+"_pdf,"+prefix+"_original_pdf) VALUES(?,?,?) "
                +"ON DUPLICATE KEY UPDATE "+prefix+"_pdf=VALUES("+prefix+"_pdf),"+prefix+"_original_pdf=VALUES("+prefix+"_original_pdf)",id,pdf.delivered(),pdf.original());
    }
    public void error(String id,String state,String message) {
        String text=message==null?"Операция не завершена":message.substring(0,Math.min(700,message.length()));
        jdbc.update("UPDATE paper_invoice_document SET stage=?,error_text=?,work_next_attempt_at=DATE_ADD(NOW(6),INTERVAL LEAST(1800,60*POW(2,LEAST(work_failures,5))) SECOND),work_failures=LEAST(work_failures+1,30),updated_at=NOW(6) WHERE id=? AND cancel_requested_at IS NULL AND (stage<>'NEEDS_REVIEW' OR ?='NEEDS_REVIEW') AND (?<>'NEEDS_REVIEW' OR paid_at IS NULL) AND (stage<>'PAID' OR ?='PAID')",state,text,id,state,state,state);
    }
    public boolean claimDelivery(String id,boolean act) {
        return claim(id,act?"act_delivery":"invoice_delivery");
    }
    public boolean claimMessage(String id) {
        return claim(id,"message_delivery");
    }
    private boolean claim(String id,String column) {
        return jdbc.update("UPDATE paper_invoice_document SET "+column+"='SENDING',delivery_attempted_at=NOW(6),updated_at=NOW(6) "
                +"WHERE id=? AND cancel_requested_at IS NULL AND stage IN ('FILES_READY','WAITING_PAYMENT','PAID') AND "+column+"='PENDING' "
                +"AND work_next_attempt_at<=NOW(6) AND message_delivery<>'SENDING' AND invoice_delivery<>'SENDING' AND act_delivery<>'SENDING'",id)==1;
    }
    public void messageDelivery(String id,String state,String messageId) {
        finishDelivery(id,"message_delivery","message_id",state,messageId);
    }
    public void delivery(String id,boolean act,String state,String messageId) {
        finishDelivery(id,act?"act_delivery":"invoice_delivery",act?"act_message_id":"invoice_message_id",state,messageId);
    }
    private void finishDelivery(String id,String column,String receipt,String state,String messageId) {
        jdbc.update("UPDATE paper_invoice_document SET "+column+"=?,"+receipt+"=?,"
                +"work_next_attempt_at=IF(?='PENDING',DATE_ADD(NOW(6),INTERVAL LEAST(1800,60*POW(2,LEAST(work_failures,5))) SECOND),NOW(6)),"
                +"work_failures=IF(?='SENT',0,LEAST(work_failures+1,30)),updated_at=NOW(6) WHERE id=? AND "+column+"='SENDING'",
                state,messageId,state,state,id);
    }
    public void deliveryFailure(String id,String label,String code,String detail) {
        String text=label+": "+Objects.toString(detail,"Доставка не подтверждена")
                +" ("+Objects.toString(code,"operation_unknown")+").";
        jdbc.update("UPDATE paper_invoice_document SET error_text=?,updated_at=NOW(6) WHERE id=? AND cancelled_at IS NULL AND NOT(error_text <=> ?)",
                text.substring(0,Math.min(700,text.length())),id,text.substring(0,Math.min(700,text.length())));
        if(Set.of("not_ready","whatsapp_client_missing","whatsapp_group_missing","telegram_group_missing","max_group_missing",
                "max_not_configured","chat_platform_missing","chat_platform_unknown","unauthorized","invalid_request","payload_too_large").contains(Objects.toString(code,"")))
            jdbc.update("UPDATE paper_invoice_document SET work_next_attempt_at=DATE_ADD(NOW(6),INTERVAL 30 MINUTE) WHERE id=? AND (message_delivery='PENDING' OR invoice_delivery='PENDING' OR act_delivery='PENDING')",id);
        jdbc.update("""
            INSERT IGNORE INTO paper_invoice_event(document_id,event_key,body)
            SELECT id,'delivery-attention',CONCAT('Бумажный счёт № ',number,': доставка документов требует проверки. ',COALESCE(error_text,''))
            FROM paper_invoice_document WHERE id=? AND work_failures>=6 AND cancelled_at IS NULL
            """,id);
    }
    public List<String> pending() {
        for (String column:List.of("message_delivery","invoice_delivery","act_delivery")) {
            jdbc.update("UPDATE paper_invoice_document SET "+column+"='UNKNOWN' WHERE "+column+"='SENDING' AND delivery_attempted_at < DATE_SUB(NOW(),INTERVAL 5 MINUTE)");
        }
        // A process crash after a POST is ambiguous. Never send a second create automatically.
        jdbc.update("""
            UPDATE paper_invoice_document SET stage=CONCAT(stage,'_UNKNOWN'),error_text='Результат создания в банке неизвестен; нужна сверка',updated_at=NOW(6)
            WHERE stage IN ('INVOICE_CREATING','ACT_CREATING') AND updated_at < DATE_SUB(NOW(),INTERVAL 5 MINUTE)
            """);
        return jdbc.queryForList("""
            SELECT id FROM paper_invoice_document
            WHERE cancel_requested_at IS NULL AND work_next_attempt_at<=NOW(6) AND (stage IN ('PENDING','INVOICE_CREATED','ACT_CREATED')
            OR (stage='FILES_READY' AND (message_delivery='PENDING' OR (message_delivery='SENT' AND
                (invoice_delivery='PENDING' OR act_delivery='PENDING' OR (invoice_delivery='SENT' AND act_delivery='SENT')))))
            OR (stage IN ('WAITING_PAYMENT','PAID') AND delivery_completed_at IS NULL
                AND message_delivery='SENT' AND invoice_delivery='SENT' AND act_delivery='SENT'))
            ORDER BY work_next_attempt_at,created_at LIMIT 20
            """,String.class);
    }
    public JdbcTemplate jdbc() { return jdbc; }
    @Override public String queueName() {return "paper_documents";}
    @Override @org.springframework.transaction.annotation.Transactional(readOnly=true,timeout=3)
    public List<Row> deliveryQueueHealth() {
        return jdbc.query("""
            SELECT outcome,COUNT(*),GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),NOW(6))),
                SUM(CASE WHEN outcome='SENDING' AND delivery_attempted_at<DATE_SUB(NOW(6),INTERVAL 5 MINUTE) THEN 1 ELSE 0 END)
            FROM (SELECT created_at,delivery_attempted_at,CASE
                WHEN stage LIKE '%UNKNOWN' OR message_delivery='UNKNOWN' OR invoice_delivery='UNKNOWN' OR act_delivery='UNKNOWN' THEN 'UNKNOWN'
                WHEN message_delivery='SENDING' OR invoice_delivery='SENDING' OR act_delivery='SENDING' THEN 'SENDING'
                WHEN work_failures>0 THEN 'RETRYABLE' ELSE 'QUEUED' END AS outcome
                FROM paper_invoice_document WHERE cancel_requested_at IS NULL
                  AND delivery_completed_at IS NULL AND stage NOT IN ('CANCELLED','NEEDS_REVIEW')) pending
            GROUP BY outcome
            """,(rs,n)->new Row(State.valueOf(rs.getString(1)),rs.getLong(2),rs.getDouble(3),rs.getLong(4)));
    }
}
