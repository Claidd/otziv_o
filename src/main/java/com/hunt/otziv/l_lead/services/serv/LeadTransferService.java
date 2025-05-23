package com.hunt.otziv.l_lead.services.serv;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.u_users.model.Manager;

public interface LeadTransferService {

    void sendLeadToServer(Long leadId);

    void sendLeadUpdate(Lead lead);
}
