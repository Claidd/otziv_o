package com.hunt.otziv.paper_billing;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.hunt.otziv.common_billing.api.BankPaperInvoiceParts;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;

@Service @RequiredArgsConstructor
public class PaperBillingReminders {
    private final PaperBillingStore store;private final PaperBillingTargets targets;
    private final PaperBillingSettings settings;private final PaperBillingBankClient bank;
    private final PaperBillingDelivery delivery;private final BankPaperInvoiceParts parts;
    private final PlatformTransactionManager transactions;
    @Scheduled(fixedDelay=30000,initialDelay=60000)
    public void tick() {
        if(!settings.isEnabled() || !settings.isDeliveryEnabled() || !settings.isBankOperationsEnabled() || !bank.configured()) return;
        store.jdbc().update("UPDATE paper_invoice_document SET paper_next_reminder_at=NULL,paper_reminder_state='PENDING',paper_reminder_token=NULL WHERE target_kind='COMMON_PART' AND (paid_at IS NOT NULL OR cancel_requested_at IS NOT NULL) AND (paper_next_reminder_at IS NOT NULL OR paper_reminder_state<>'PENDING' OR paper_reminder_token IS NOT NULL)");
        store.jdbc().update("UPDATE paper_invoice_document SET paper_reminder_state='UNKNOWN',error_text='Результат напоминания неизвестен; требуется сверка' WHERE paid_at IS NULL AND cancel_requested_at IS NULL AND stage='WAITING_PAYMENT' AND paper_reminder_state='SENDING' AND paper_reminder_attempt_at<DATE_SUB(NOW(6),INTERVAL 5 MINUTE)");
        for(String id:store.jdbc().queryForList("SELECT id FROM paper_invoice_document WHERE target_kind='COMMON_PART' AND paid_at IS NULL AND cancel_requested_at IS NULL AND stage='WAITING_PAYMENT' AND paper_reminder_state='PENDING' AND paper_next_reminder_at<=NOW(6) ORDER BY paper_next_reminder_at LIMIT 20",String.class)) {
            try {send(id);} catch(RuntimeException failure) { /* Durable SENDING remains receipt-only after timeout. */ }
        }
    }
    public void send(String id) {
        if(!settings.isEnabled() || !settings.isDeliveryEnabled() || !settings.isBankOperationsEnabled() || !bank.configured()) return;
        String token=UUID.randomUUID().toString();
        var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        var row=tx.execute(status->{
            var before=store.row(id);if(!"COMMON_PART".equals(before.get("target_kind")))return null;
            long part=((Number)before.get("target_id")).longValue();targets.lockDelivery(new Target("COMMON_PART",part));
            var current=store.jdbc().queryForMap("SELECT * FROM paper_invoice_document WHERE id=? FOR UPDATE",id);
            if(current.get("paid_at")!=null || current.get("cancel_requested_at")!=null || !"WAITING_PAYMENT".equals(current.get("stage")) || !"PENDING".equals(current.get("paper_reminder_state"))
                    || !parts.remindersAllowed(part) || current.get("paper_next_reminder_at")==null
                    || store.jdbc().queryForObject("SELECT paper_next_reminder_at>NOW(6) FROM paper_invoice_document WHERE id=?",Boolean.class,id))return null;
            store.jdbc().update("UPDATE paper_invoice_document SET paper_reminder_state='SENDING',paper_reminder_token=?,paper_reminder_attempt_at=NOW(6) WHERE id=?",token,id);return current;
        });
        if(row==null)return;
        int count=((Number)row.get("paper_reminder_count")).intValue()+1;
        var latest=store.row(id);
        if(latest.get("paid_at")!=null || latest.get("cancel_requested_at")!=null || !parts.remindersAllowed(((Number)row.get("target_id")).longValue())) return;
        var result=delivery.sendReminder(row,count);
        boolean sent=result.sent() && result.messageId()!=null;
        boolean knownUnsent=ClientMessageDelivery.isKnownUnsent(result);
        var next=sent?parts.nextReminder(((Number)row.get("target_id")).longValue()):java.time.LocalDateTime.now().plusMinutes(1);
        store.jdbc().update("UPDATE paper_invoice_document SET paper_reminder_state=?,paper_reminder_count=paper_reminder_count+?,paper_next_reminder_at=?,error_text=? WHERE id=? AND paper_reminder_token=? AND paid_at IS NULL AND cancel_requested_at IS NULL",
                sent || knownUnsent?"PENDING":"UNKNOWN",sent?1:0,sent && count>=7?null:next,
                sent?(count>=7?"Напоминания по этому счёту завершены. Требуется проверка оплаты менеджером":null):knownUnsent?"Напоминание пока не отправлено":"Результат отправки напоминания неизвестен; требуется сверка",id,token);
    }
}
