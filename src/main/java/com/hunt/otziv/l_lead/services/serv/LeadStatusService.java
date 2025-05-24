package com.hunt.otziv.l_lead.services.serv;

import com.hunt.otziv.l_lead.model.Lead;

public interface LeadStatusService {
    void prepareLeadForSending(Lead lead, String status);
}
