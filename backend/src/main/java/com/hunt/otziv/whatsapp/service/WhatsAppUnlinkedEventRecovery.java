package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.service.service.GroupReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service @RequiredArgsConstructor @Slf4j
public class WhatsAppUnlinkedEventRecovery {
    private final WhatsAppUnlinkedEventStore store;
    private final GroupReplyService replies;
    private final PlatformTransactionManager transactions;
    private final WhatsAppUnlinkedGroupNotifier notices;

    @Scheduled(fixedDelayString="${whatsapp.webhook.unlinked-recovery-delay-ms:60000}",initialDelay=60000)
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void recover() {
        try {
            int expired=store.expireStale();
            if(expired>0) log.info("Expired {} unattributed WhatsApp inbound events",expired);
        } catch(RuntimeException failure) {
            log.warn("Unlinked WhatsApp event expiry failed: exceptionType={}",failure.getClass().getSimpleName());
        }
        for(String key:store.due()) {
            try {new TransactionTemplate(transactions).executeWithoutResult(status->{
                var reply=store.lockDue(key);
                // Receipt deduplication already succeeded. Replay the saved business effect,
                // atomically with completion; message tracking and reply outbox deduplicate by ID.
                if(reply!=null) replies.processGroupReply(reply);
            });} catch(RuntimeException failure) {
                store.postponeFailed(key);
                log.warn("Unlinked WhatsApp event recovery deferred: exceptionType={}",failure.getClass().getSimpleName());
            }
        }
        // After the replay: a message whose group got linked meanwhile is already completed and never announced.
        try {
            int announced=notices.notifyDue();
            if(announced>0) log.info("Announced {} unlinked WhatsApp groups to the admin chats",announced);
        } catch(RuntimeException failure) {
            log.warn("Unlinked WhatsApp group notices failed: exceptionType={}",failure.getClass().getSimpleName());
        }
    }
}
