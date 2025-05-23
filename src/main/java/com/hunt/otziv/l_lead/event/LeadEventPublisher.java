package com.hunt.otziv.l_lead.event;

import com.hunt.otziv.l_lead.dto.LeadUpdatedEvent;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.u_users.model.Manager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LeadEventPublisher {
    private final ApplicationEventPublisher eventPublisher;

    public void publishUpdate(Lead lead) {
        eventPublisher.publishEvent(new LeadUpdatedEvent(lead.getId()));
    }
}
