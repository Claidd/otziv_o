package com.hunt.otziv.whatsapp.service.service;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;

public interface LeadProcessorService {
    void processLead(WhatsAppProperties.ClientConfig client);

    void resetState();

    void checkAllClientsFinished();

}
