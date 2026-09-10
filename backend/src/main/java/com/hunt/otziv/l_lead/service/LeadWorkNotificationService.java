package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.model.Lead;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeadWorkNotificationService {
    private final com.hunt.otziv.whatsapp.api.WhatsAppQueuedMessages queue;
    public LeadWorkNotificationService(com.hunt.otziv.whatsapp.api.WhatsAppQueuedMessages queue) {
        this.queue = queue;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void prepare(Lead lead) {
        // Generation zero predates delivery evidence. A repeated legacy action cannot authorize a send.
        if(lead.getWhatsappWorkGeneration()<=0||lead.getManager()==null||lead.getManager().getId()==null)return;
        String group=lead.getManager().getId()==3L?"120363399937937645@g.us":null;
        String client=lead.getManager().getClientId();
        if(group==null||client==null||client.isBlank())return;
        String operation="lead-work:"+lead.getId()+":"+lead.getWhatsappWorkGeneration();
        String text=String.format("📨 Новая фирма:\n📞 %s\n🌆 %s\n💬 %s",lead.getTelephoneLead(),lead.getCityLead(),lead.getCommentsLead());
        queue.enqueue(operation,client,group,text);
    }
}
