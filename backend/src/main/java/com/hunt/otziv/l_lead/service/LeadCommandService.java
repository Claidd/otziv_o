package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.l_lead.dto.LeadCommandIdentity;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadUpdateDto;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.LeadCommandRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** One synchronous producer. Caller DTOs cannot replace the current locked snapshot. */
@Service
@RequiredArgsConstructor
public class LeadCommandService {
    private final LeadCommandCodec codec;
    private final LeadCommandRepository repository;
    private final LeadMapper mapper;
    private final JwtService signatures;
    private final EntityManager entityManager;

    @Transactional
    public String enqueueSync(long leadId) { return enqueueCurrent(leadId, "SYNC", null); }

    @Transactional
    public String enqueueUpdate(long leadId) { return enqueueCurrent(leadId, "UPDATE", null); }

    @Transactional
    public String enqueueImport(long leadId, String requestId) {
        if (requestId != null && !requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"LEAD_REQUEST_ID_INVALID");
        }
        return enqueueCurrent(leadId,"IMPORT",requestId);
    }

    private String enqueueCurrent(long leadId, String kind, String requestId) {
        if (leadId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"LEAD_ID_INVALID");
        // Business writes acquire Lead before the stream row. Flush first: refresh
        // must not discard the enclosing command's own pending JPA changes.
        entityManager.flush();
        Lead lead = entityManager.find(Lead.class,leadId,LockModeType.PESSIMISTIC_WRITE);
        if (lead == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Lead not found");
        entityManager.refresh(lead,LockModeType.PESSIMISTIC_WRITE);
        var stream = repository.lockStream(leadId);
        if ("IMPORT".equals(kind)) {
            if (requestId != null) {
                try {
                    var existing = repository.manualRequest(leadId,requestId);
                    if (existing.isPresent()) return existing.get();
                } catch (IllegalArgumentException error) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,error.getMessage());
                }
            }
            // Legacy button callers have no key. Unresolved intentions retain the
            // original payload/identity, including when current lead data changed.
            var pending = repository.pendingImport(leadId);
            if (pending.isPresent()) {
                repository.finishManualRequest(requestId,pending.get());
                return pending.get();
            }
        }
        String operationId = UUID.randomUUID().toString();
        var identity = new LeadCommandIdentity(1,stream.sourceId(),leadId,stream.version(),operationId,kind);
        Object payload;
        String phone;
        if ("UPDATE".equals(kind)) {
            LeadUpdateDto dto = mapper.toUpdateDto(lead);
            dto.setCommand(identity);
            payload=dto;
            phone=dto.getTelephoneLead();
        } else {
            LeadDtoTransfer dto = mapper.toDtoTransfer(lead);
            if ("SYNC".equals(kind)) dto.setTelephoneLead(LeadCommandProtocol.phoneKey(dto.getTelephoneLead()));
            dto.setCommand(identity);
            payload=dto;
            phone=dto.getTelephoneLead();
        }
        repository.enqueueVersioned(leadId,phone,kind,codec.encode(payload),stream,operationId,signatures.generateChecksum(payload));
        repository.finishManualRequest(requestId,operationId);
        return operationId;
    }
}
