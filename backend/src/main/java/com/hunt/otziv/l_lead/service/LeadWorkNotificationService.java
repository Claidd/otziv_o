package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LeadWorkNotificationService {
    private final WhatsAppBusinessOperations operations;
    private final WhatsAppService whatsapp;
    private final PlatformTransactionManager transactions;
    public LeadWorkNotificationService(WhatsAppBusinessOperations operations,WhatsAppService whatsapp,PlatformTransactionManager transactions) {
        this.operations=operations;this.whatsapp=whatsapp;this.transactions=transactions;
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
        var frozen=operations.freeze(operation,client,"send-group",group,text);
        if(!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("Lead notification requires business transaction synchronization");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                TransactionTemplate outside=new TransactionTemplate(transactions);
                outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                outside.executeWithoutResult(status->whatsapp.sendMessageToGroup(frozen.clientId(),frozen.destination(),frozen.message(),frozen.operationId()));
            }
        });
    }
}
