package com.hunt.otziv.business_audit.service;

import com.hunt.otziv.business_audit.api.CredentialRevealEvidence;
import com.hunt.otziv.business_audit.repository.BusinessAuditEventRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CredentialRevealEvidenceReader implements CredentialRevealEvidence {
    private final BusinessAuditEventRepository repository;

    @Override
    @Transactional(readOnly = true)
    public boolean hasBothCredentials(String actor, String entityType, Long entityId, Long botId,
            LocalDateTime since, LocalDateTime until) {
        if (actor == null || actor.isBlank() || entityType == null || entityId == null
                || botId == null || botId <= 0 || since == null || until == null) return false;
        return repository.hasCredentialReveal(actor, entityType, entityId.toString(),
                "field=login;botId=" + botId + ";", since, until)
                && repository.hasCredentialReveal(actor, entityType, entityId.toString(),
                "field=password;botId=" + botId + ";", since, until);
    }
}
