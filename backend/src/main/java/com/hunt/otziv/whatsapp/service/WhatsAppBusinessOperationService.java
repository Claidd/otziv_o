package com.hunt.otziv.whatsapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.repository.WhatsAppBusinessOperationRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WhatsAppBusinessOperationService implements WhatsAppBusinessOperations {
    private static final ObjectMapper JSON=new ObjectMapper().configure(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(),true);
    private final WhatsAppBusinessOperationRepository repository;
    private final CredentialCipher cipher;
    public WhatsAppBusinessOperationService(WhatsAppBusinessOperationRepository repository,CredentialCipher cipher) {this.repository=repository;this.cipher=cipher;}

    @Override @Transactional
    public FrozenMessage freeze(String operationId,String clientId,String kind,String destination,String message) {
        var proposed=validate(operationId,clientId,kind,destination,message);
        if(!cipher.isEnabled()) throw new IllegalStateException("Encrypted business operation storage is unavailable");
        try {
            var saved=repository.freeze(operationId,hash(proposed),cipher.encrypt(JSON.writeValueAsString(proposed)));
            return decode(operationId,saved);
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) {throw new IllegalStateException("Business operation codec failed",error);}
    }

    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public FrozenMessage freezeForDispatch(String operationId,String clientId,String kind,String destination,String message) {
        return freeze(operationId,clientId,kind,destination,message);
    }

    @Override @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public void requireMatches(String operationId,String clientId,String kind,String destination,String message) {
        var proposed=validate(operationId,clientId,kind,destination,message);
        var frozen=requireFrozen(operationId);
        if(!hash(proposed).equals(hash(frozen))) throw new IllegalArgumentException("operation_payload_conflict");
    }

    @Override @Transactional(readOnly=true)
    public FrozenMessage requireFrozen(String operationId) {
        return findFrozen(operationId).orElseThrow(()->new IllegalArgumentException("operation_not_prepared"));
    }

    @Override @Transactional(readOnly=true)
    public java.util.Optional<FrozenMessage> findFrozen(String operationId) {
        var saved=repository.find(operationId);
        if(saved.isEmpty())return java.util.Optional.empty();
        try {return java.util.Optional.of(decode(operationId,saved.orElseThrow()));}
        catch(com.fasterxml.jackson.core.JsonProcessingException error) {throw new IllegalStateException("Business operation codec failed",error);}
    }

    private FrozenMessage decode(String operationId,WhatsAppBusinessOperationRepository.Snapshot saved) throws com.fasterxml.jackson.core.JsonProcessingException {
        var frozen=JSON.readValue(cipher.decrypt(saved.ciphertext()),FrozenMessage.class);
        if(!operationId.equals(frozen.operationId())||!saved.hash().equals(hash(frozen)))throw new IllegalStateException("Business operation snapshot is corrupt");
        return frozen;
    }

    @Override @Transactional
    public String createManualOperation(String actor) {
        String id=UUID.randomUUID().toString();repository.createManual(id,actorHash(actor));return id;
    }

    @Override @Transactional(readOnly=true)
    public void requireManualOwner(String operationId,String actor) {
        if(operationId==null||!repository.belongsTo(operationId,actorHash(actor)))throw new IllegalArgumentException("Manual operation is missing or belongs to another actor");
    }

    private static String actorHash(String actor) {
        if(actor==null||actor.isBlank())throw new IllegalArgumentException("Authenticated actor required");
        return WhatsAppOperationKey.of("manual-message-actor-v1",actor).substring(3);
    }
    private static FrozenMessage validate(String id,String client,String kind,String destination,String message) {
        if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw new IllegalArgumentException("invalid_operation_id");
        if(client==null||client.isBlank()||kind==null||!Set.of("send","send-group").contains(kind)||destination==null||destination.isBlank()||message==null||message.isBlank()||message.length()>100_000)
            throw new IllegalArgumentException("invalid_operation_envelope");
        return new FrozenMessage(id,client,kind,com.hunt.otziv.whatsapp.dto.WhatsAppDestination.normalize(kind,destination),message);
    }
    private static String hash(FrozenMessage value) {return WhatsAppOperationKey.of("backend-frozen-envelope-v1",value.clientId(),value.kind(),value.destination(),value.message()).substring(3);}
}
