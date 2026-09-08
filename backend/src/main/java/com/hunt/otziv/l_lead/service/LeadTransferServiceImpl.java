package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.model.Lead;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Compatibility facade. LeadCommandWorker is the only outbound transport owner. */
@Service
@RequiredArgsConstructor
public class LeadTransferServiceImpl implements LeadTransferService {
    private final LeadCommandService commands;
    @Transactional
    public void sendLeadToServer(Long leadId) { commands.enqueueImport(leadId,null); }
    @Transactional
    public void sendLeadUpdate(Lead lead) { commands.enqueueUpdate(lead.getId()); }
}
