package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadUpdateDto;
import com.hunt.otziv.l_lead.repository.LeadInboundCommandRepository;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LeadCommandReceiver {
    private final LeadInboundCommandRepository receipts;
    private final LeadInboundMutationService mutations;
    private final LeadCommandCodec codec;
    private final JwtService signatures;
    @Value("${lead.commands.receiver.enabled:false}") private boolean enabled;
    @Value("${lead.commands.receiver.allowed-source-ids:}") private String allowedSourceIds;
    @Value("${lead.commands.receiver.legacy-unversioned-enabled:true}") private boolean legacyEnabled;

    @PostConstruct
    void validateActivation() {
        if(enabled && sources().isEmpty())throw new IllegalStateException("Versioned lead receiver requires explicit allowed source IDs");
    }

    @Transactional
    public LeadInboundMutationService.Result receive(String kind,Object payload,String modernToken,String requestKey) {
        codec.encode(payload); // same required-field/size/identity validation as the producer
        var identity=LeadCommandProtocol.identity(payload);
        String phone=LeadCommandProtocol.phoneKey(LeadCommandProtocol.phone(payload));
        if(identity==null) {
            if(!legacyEnabled)throw reject(409,"LEAD_VERSIONED_COMMAND_REQUIRED");
            try {receipts.lockTarget(phone,null);} catch(LeadInboundCommandRepository.Conflict e){throw reject(409,e.getMessage());}
            return switch(kind) {
                case "SYNC" -> mutations.sync((LeadDtoTransfer)payload);
                case "IMPORT" -> mutations.legacyImport((LeadDtoTransfer)payload);
                case "UPDATE" -> mutations.update((LeadUpdateDto)payload);
                default -> throw reject(400,"LEAD_COMMAND_UNSUPPORTED");
            };
        }
        if(!enabled)throw reject(409,"LEAD_RECEIVER_NOT_ACTIVATED");
        if(identity.protocolVersion()!=1 || !kind.equals(identity.kind()))throw reject(400,"LEAD_PROTOCOL_MISMATCH");
        if(!sources().contains(identity.sourceId()))throw reject(403,"LEAD_SOURCE_NOT_ALLOWED");
        if(!identity.operationId().equals(requestKey))throw reject(400,"LEAD_OPERATION_HEADER_MISMATCH");
        String hash=signatures.generateChecksum(payload);
        // Versioned commands never inherit the old unsigned-body bearer exception.
        // Verify again at the application boundary so a direct invocation cannot bypass it.
        try {
            var claims=signatures.parseAndValidate(modernToken,"IMPORT".equals(kind)?JwtService.LEAD_TRANSFER_SUBJECT:JwtService.LEAD_SYNC_SUBJECT,
                    "POST:/api/leads/"+kind.toLowerCase(java.util.Locale.ROOT));
            String signed=claims.get("checksum",String.class);
            if(signed==null || !MessageDigest.isEqual(signed.getBytes(StandardCharsets.US_ASCII),hash.getBytes(StandardCharsets.US_ASCII)))
                throw reject(403,"LEAD_COMMAND_SIGNATURE_MISMATCH");
        } catch(Rejected error) {throw error;} catch(RuntimeException error) {throw reject(403,"LEAD_COMMAND_SIGNATURE_INVALID");}
        try {
            var receipt=receipts.lockReceipt(identity,hash);
            if(!"PENDING".equals(receipt.outcome()))return new LeadInboundMutationService.Result(200,receipt,null);
            var entity=receipts.lockEntity(identity,phone);
            if(identity.entityVersion()<entity.version()) {
                return new LeadInboundMutationService.Result(200,receipts.finish(identity,hash,"STALE",entity.version()),entity.targetId());
            }
            if(identity.entityVersion()==entity.version())throw reject(409,"LEAD_ENTITY_VERSION_CONFLICT");
            receipts.lockTarget(phone,identity);
            var mutation=mutations.applyVersioned(kind,payload,entity.targetId());
            if(mutation.status()>=400)throw new Rejected(mutation.status(),mutation.body());
            receipts.applied(identity,phone,mutation.targetLeadId());
            return new LeadInboundMutationService.Result(200,receipts.finish(identity,hash,"APPLIED",identity.entityVersion()),mutation.targetLeadId());
        } catch(LeadInboundCommandRepository.Conflict error) {throw reject(409,error.getMessage());}
    }

    private Set<String> sources(){return Arrays.stream(allowedSourceIds.split(",")).map(String::trim).filter(s->!s.isEmpty()).collect(Collectors.toUnmodifiableSet());}
    private static Rejected reject(int status,String code){return new Rejected(status,Map.of("error",code));}
    public static final class Rejected extends RuntimeException {
        public final int status; public final Object body;
        public Rejected(int status,Object body){super("Lead command rejected ("+status+")");this.status=status;this.body=body;}
    }
}
