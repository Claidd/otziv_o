package com.hunt.otziv.l_lead.event;

import com.hunt.otziv.l_lead.dto.LeadUpdatedEvent;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.u_users.model.Manager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadEventPublisher {
    private final ApplicationEventPublisher eventPublisher;

    public void publishUpdate(Lead lead) {
        log.info("📢 Публикуем событие обновления лида {}", lead.getId());
        eventPublisher.publishEvent(new LeadUpdatedEvent(lead.getId()));
    }
}
