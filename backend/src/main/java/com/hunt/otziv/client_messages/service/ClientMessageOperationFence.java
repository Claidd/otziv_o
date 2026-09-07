package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Local at-most-one unresolved dispatch; provider idempotency is not assumed. */
@Service
public class ClientMessageOperationFence {
    private final ClientMessageOperationStore store;
    public ClientMessageOperationFence(ClientMessageOperationStore store){this.store=store;}
    public Optional<Snapshot> lookup(String operationId){validateId(operationId);return store.lookup(operationId);}

    /** canonicalPayload must include every outbound field, including optional copy-button data. */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public ClientMessageSendResult execute(String operationId,String platform,String destination,String canonicalPayload,
            Supplier<ClientMessageSendResult> provider){
        validateId(operationId);
        // Confirmed receipts and unresolved barriers are independent of mutable company routing.
        var previous=store.lookup(operationId);
        if(previous.isPresent() && !"FAILED_KNOWN".equals(previous.get().state()))return result(previous.get());
        if(!java.util.Set.of("TELEGRAM","MAX").contains(platform==null?"":platform)
                || destination==null || destination.isBlank() || destination.length()>128
                || canonicalPayload==null || canonicalPayload.isBlank() || canonicalPayload.length()>32768
                || !validUnicode(destination) || !validUnicode(canonicalPayload) || provider==null)
            return ClientMessageSendResult.failed("invalid_operation_envelope","Параметры операции не прошли проверку");
        String destinationHash=hash("destination-v1",platform,destination.trim());
        String envelopeHash=hash("client-message-v1",platform,destination.trim(),canonicalPayload);
        var begin=store.begin(operationId,platform,destinationHash,envelopeHash);
        if(!begin.acquired())return result(begin.snapshot());
        ClientMessageSendResult outcome;
        try{outcome=provider.get();}catch(RuntimeException ambiguous){outcome=unknown();}
        // A failed commit leaves the committed PREPARED barrier. Never claim delivery succeeded.
        try{return result(store.finish(begin.snapshot(),outcome));}catch(RuntimeException failedCommit){return unknown();}
    }
    public Snapshot confirmDelivered(Authentication actor,String operationId,String expectedClaimToken,String expectedEnvelopeHash,
            String providerMessageId,String reason){
        validateAdministrator(actor);validateId(operationId);
        if(expectedClaimToken==null || !expectedClaimToken.matches("[a-f0-9-]{36}")
                || expectedEnvelopeHash==null || !expectedEnvelopeHash.matches("[a-f0-9]{64}")
                || providerMessageId==null || providerMessageId.isBlank() || providerMessageId.length()>512
                || reason==null || reason.trim().length()<12 || reason.length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Требуются точные данные операции, подтверждение доставки и причина сверки");
        return store.confirmDelivered(operationId,expectedClaimToken,expectedEnvelopeHash,actor.getName(),providerMessageId.trim(),reason.trim());
    }
    public static void validateAdministrator(Authentication actor){
        if(actor==null || actor instanceof AnonymousAuthenticationToken || !actor.isAuthenticated() || actor.getName()==null
                || actor.getName().isBlank() || actor.getName().length()>128 || actor.getAuthorities().stream().noneMatch(a->
                "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_OWNER".equals(a.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Сверка доступна владельцу или администратору");
    }
    static void validateId(String id){if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Некорректный идентификатор операции");}
    private static boolean validUnicode(String value){
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i)))return false;}
            else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
    static String hash(String...fields){
        try{var digest=MessageDigest.getInstance("SHA-256");for(String field:fields){byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}return HexFormat.of().formatHex(digest.digest());}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static ClientMessageSendResult unknown(){return ClientMessageSendResult.failed("operation_unknown","Результат операции не подтверждён; требуется ручная сверка без повторной отправки");}
    static ClientMessageSendResult result(Snapshot snapshot){
        if("SUCCEEDED".equals(snapshot.state()))return snapshot.result();
        if("CONFLICT".equals(snapshot.state()))return ClientMessageSendResult.failed("operation_payload_conflict","Параметры сохранённой операции не совпадают");
        if("FAILED_KNOWN".equals(snapshot.state()))return snapshot.result();
        return unknown();
    }
    public record Snapshot(String operationId,String platform,String state,String destinationHash,String envelopeHash,
            String claimToken,ClientMessageSendResult result){}
}
