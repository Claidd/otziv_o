package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;

import java.security.Principal;

public interface LeadService {

    LeadDTO convertFromLead(Lead lead);

    Lead save(LeadDTO leadDTO, String id);
}
