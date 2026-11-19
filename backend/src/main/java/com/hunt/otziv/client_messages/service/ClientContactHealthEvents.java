package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.c_companies.api.CompanyChatLinkChanged;
import com.hunt.otziv.client_messages.api.ClientContactHealth;
import com.hunt.otziv.client_messages.api.ClientMessageAttemptRecorded;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import lombok.extern.slf4j.Slf4j;

/** Prompt diagnostics after a failed attempt/link edit; bounded and deduplicated, with periodic fallback. */
@Component
@Slf4j
class ClientContactHealthEvents {
    private final JdbcTemplate jdbc;
    private final ClientContactHealth health;
    private final ThreadPoolTaskScheduler scheduler;
    private final Set<Long> pending=ConcurrentHashMap.newKeySet();
    ClientContactHealthEvents(JdbcTemplate jdbc,ClientContactHealth health,
            @Qualifier("clientContactHealthScheduler") ThreadPoolTaskScheduler scheduler) {
        this.jdbc=jdbc;this.health=health;this.scheduler=scheduler;
    }
    @TransactionalEventListener(fallbackExecution=true)
    public void changed(CompanyChatLinkChanged event) { enqueue(event.companyId()); }

    @TransactionalEventListener(fallbackExecution=true)
    public void attempted(ClientMessageAttemptRecorded event) {
        // Read only after the attempt commits. Intentional waits/holds must not flood provider probes.
        try { jdbc.query("""
                SELECT COALESCE(s.company_id,o.order_company) FROM scheduled_client_message_state s
                LEFT JOIN orders o ON o.order_id=s.order_id
                LEFT JOIN client_contact_health h ON h.company_id=COALESCE(s.company_id,o.order_company)
                WHERE s.state_id=? AND s.consecutive_failures>0
                  AND (h.checked_at IS NULL OR h.checked_at < UTC_TIMESTAMP(6)-INTERVAL 15 MINUTE)
                  AND COALESCE(s.last_error_code,'') NOT IN ('rate_limited','daily_limit','outside_messaging_window',
                    'messaging_window_closed','inactive_company_chat_unavailable','manual_contact_reserved')
                """,(org.springframework.jdbc.core.RowCallbackHandler)rs->enqueue(rs.getObject(1,Long.class)),event.stateId());
        } catch(RuntimeException unavailable) {
            // Diagnostics must not change the result of the already committed delivery transaction.
            log.warn("Contact check discovery deferred stateId={} reason={}",event.stateId(),unavailable.getClass().getSimpleName());
        }
    }
    private void enqueue(Long id) {
        if(id==null || pending.size()>=100 || !pending.add(id)) return;
        try { scheduler.execute(()->{
            try { health.recheck(id); }
            catch(RuntimeException unavailable) { log.warn("Contact check deferred companyId={} reason={}",id,unavailable.getClass().getSimpleName()); }
            finally { pending.remove(id); }
        }); } catch(RuntimeException unavailable) {pending.remove(id);}
    }
}
