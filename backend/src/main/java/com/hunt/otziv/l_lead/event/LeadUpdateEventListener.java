package com.hunt.otziv.l_lead.event;

import com.hunt.otziv.l_lead.dto.LeadUpdatedEvent;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.service.LeadService;
import com.hunt.otziv.l_lead.service.LeadTransferService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
/** Stores the frozen outbound command in the same transaction as the lead change. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "lead.sync.outbound.enabled", havingValue = "true", matchIfMissing = false)
public class LeadUpdateEventListener {

    private final LeadService leadService;
    private final LeadTransferService leadTransferService;

    @PostConstruct
    public void init() {
        log.info("✅ LeadUpdateEventListener инициализирован");
    }

    // Здесь нет HTTP: ошибка записи намерения откатывает бизнес-изменение.
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onLeadUpdated(LeadUpdatedEvent event) {
        log.info("📡 Получено событие LeadUpdatedEvent для лида {}", event.leadId());
        Lead lead = leadService.findByIdOptional(event.leadId()).orElse(null);
        if (lead == null) {
            log.warn("⚠️ Лид {} не найден в базе", event.leadId());
            return;
        }
        leadTransferService.sendLeadUpdate(lead);
    }
}
