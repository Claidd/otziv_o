package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.model.Lead;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VpsSyncService {
    private final LeadCommandService commands;

    /** Compatibility name: persistence is synchronous, delivery is asynchronous. */
    @Transactional
    public void sendLeadAsync(Lead lead) {
        if (lead == null || lead.getId() == null) throw new IllegalArgumentException("Persisted lead is required");
        commands.enqueueSync(lead.getId());
    }
}
