package com.hunt.otziv.paper_billing;

import com.hunt.otziv.u_users.api.PaymentNoticeRecipients.Recipient;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository @RequiredArgsConstructor
public class PaperBillingNoticeStore {
    private final JdbcTemplate jdbc;
    public List<String> unprepared() {
        return jdbc.queryForList("""
            SELECT d.id FROM paper_invoice_document d WHERE d.paid_at IS NOT NULL
            AND d.staff_notices_prepared_at IS NULL AND d.staff_notice_next_attempt_at<=NOW(6)
            AND EXISTS(SELECT 1 FROM paper_invoice_bank_receipt r WHERE r.document_id=d.id AND r.state='APPLIED')
            ORDER BY d.staff_notice_next_attempt_at,d.id LIMIT 20
            """,String.class);
    }
    @Transactional
    public void prepare(String document,List<Recipient> recipients,String body) {
        var rows=jdbc.queryForList("SELECT paid_at,staff_notices_prepared_at FROM paper_invoice_document d WHERE id=? AND EXISTS(SELECT 1 FROM paper_invoice_bank_receipt r WHERE r.document_id=d.id AND r.state='APPLIED') FOR UPDATE",document);
        if(rows.isEmpty() || rows.getFirst().get("paid_at")==null || rows.getFirst().get("staff_notices_prepared_at")!=null) return;
        for(var recipient:recipients) jdbc.update("""
            INSERT IGNORE INTO paper_invoice_staff_notice(document_id,recipient_user_id,manager_id,chat_id,body)
            VALUES(?,?,?,?,?)
            """,document,recipient.userId(),recipient.managerId(),recipient.chatId(),body);
        jdbc.update("UPDATE paper_invoice_document SET staff_notices_prepared_at=NOW(6) WHERE id=?",document);
    }
    public void postponePreparation(String document) {
        jdbc.update("UPDATE paper_invoice_document SET staff_notice_next_attempt_at=DATE_ADD(NOW(6),INTERVAL 1 MINUTE) WHERE id=? AND staff_notices_prepared_at IS NULL",document);
    }
    public List<Long> due() {
        jdbc.update("UPDATE paper_invoice_staff_notice SET state='UNKNOWN',error_text='Результат отправки неизвестен; нужна сверка',updated_at=NOW(6) WHERE state='SENDING' AND updated_at<DATE_SUB(NOW(6),INTERVAL 5 MINUTE)");
        return jdbc.queryForList("SELECT notice_id FROM paper_invoice_staff_notice WHERE state='PENDING' AND next_attempt_at<=NOW(6) ORDER BY next_attempt_at,notice_id LIMIT 50",Long.class);
    }
    public Map<String,Object> row(long id) {return jdbc.queryForMap("SELECT * FROM paper_invoice_staff_notice WHERE notice_id=?",id);}
    public void postpone(long id,String error) {
        jdbc.update("UPDATE paper_invoice_staff_notice SET error_text=?,next_attempt_at=DATE_ADD(NOW(6),INTERVAL 1 MINUTE),updated_at=NOW(6) WHERE notice_id=? AND state='PENDING'",error,id);
    }
    @Transactional
    public boolean resolveChat(long id,long chat) {
        if(jdbc.update("UPDATE IGNORE paper_invoice_staff_notice SET chat_id=? WHERE notice_id=? AND chat_id IS NULL AND state='PENDING'",chat,id)==1) return true;
        var row=row(id);
        if(row.get("chat_id")!=null) return true;
        jdbc.update("UPDATE paper_invoice_staff_notice SET state='SKIPPED',error_text='Этот чат уже получает уведомление о том же счёте',updated_at=NOW(6) WHERE notice_id=? AND state='PENDING'",id);
        return false;
    }
    @Transactional
    public Optional<Map<String,Object>> claim(long id,String token) {
        int updated=jdbc.update("UPDATE paper_invoice_staff_notice SET state='SENDING',attempt_token=?,error_text=NULL,updated_at=NOW(6) WHERE notice_id=? AND state='PENDING' AND chat_id IS NOT NULL AND next_attempt_at<=NOW(6)",token,id);
        return updated==1?Optional.of(row(id)):Optional.empty();
    }
    public void finish(long id,String token,String state,String message,String error) {
        jdbc.update("UPDATE paper_invoice_staff_notice SET state=?,message_id=?,error_text=?,updated_at=NOW(6) WHERE notice_id=? AND state='SENDING' AND attempt_token=?",state,message,error,id,token);
    }
}
