package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.service.ClientMessageOperationFence.Snapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Only short independent transactions; no provider callback is accepted by this store. */
@Service
class ClientMessageOperationStore {
    private final JdbcTemplate jdbc;
    public ClientMessageOperationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,timeout=5)
    public Optional<Snapshot> lookup(String id){return read(id,false);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Begin begin(String id,String platform,String destinationHash,String envelopeHash){
        String token=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO client_message_operations(operation_id,platform,destination_hash,envelope_hash,state,claim_token) VALUES(?,?,?,?,'PREPARED',?) ON DUPLICATE KEY UPDATE operation_id=operation_id",id,platform,destinationHash,envelopeHash,token);
        Snapshot saved=read(id,true).orElseThrow();
        if(token.equals(saved.claimToken()))return new Begin(saved,true);
        if(!"FAILED_KNOWN".equals(saved.state()))return new Begin(saved,false);
        if(!platform.equals(saved.platform()) || !destinationHash.equals(saved.destinationHash()) || !envelopeHash.equals(saved.envelopeHash()))
            return new Begin(new Snapshot(id,saved.platform(),"CONFLICT",saved.destinationHash(),saved.envelopeHash(),saved.claimToken(),null),false);
        jdbc.update("UPDATE client_message_operations SET state='PREPARED',claim_token=?,attempts=attempts+1,error_code=NULL,updated_at=UTC_TIMESTAMP(6) WHERE operation_id=?",token,id);
        return new Begin(read(id,false).orElseThrow(),true);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Snapshot finish(Snapshot claim,ClientMessageSendResult outcome){
        Snapshot current=read(claim.operationId(),true).orElseThrow();
        if(!current.claimToken().equals(claim.claimToken()) || !"PREPARED".equals(current.state()))return current;
        String expectedChannel="TELEGRAM".equals(current.platform())?"Telegram":"MAX";
        boolean confirmed=outcome!=null && outcome.sent() && expectedChannel.equals(outcome.channel())
            && validReceipt(current.platform(),outcome.messageId());
        boolean known=!confirmed && ClientChatMessageSender.isKnownUnsent(outcome);
        String state=confirmed?"SUCCEEDED":known?"FAILED_KNOWN":"UNKNOWN";
        String code=known?outcome.errorCode().trim().toLowerCase(java.util.Locale.ROOT):confirmed?null:"operation_unknown";
        jdbc.update("UPDATE client_message_operations SET state=?,result_channel=?,provider_message_id=?,error_code=?,updated_at=UTC_TIMESTAMP(6) WHERE operation_id=? AND claim_token=?",state,
            confirmed?expectedChannel:null,confirmed?outcome.messageId():null,code,claim.operationId(),claim.claimToken());
        return read(claim.operationId(),false).orElseThrow();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Snapshot confirmDelivered(String id,String token,String envelope,String actor,String receipt,String reason){
        Snapshot saved=read(id,true).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Операция не найдена"));
        if(!validReceipt(saved.platform(),receipt))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Идентификатор сообщения не соответствует каналу");
        if(!token.equals(saved.claimToken()) || !envelope.equals(saved.envelopeHash()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Операция изменилась; обновите данные сверки");
        if("SUCCEEDED".equals(saved.state())){
            if(!receipt.equals(saved.result().messageId()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Подтверждение доставки не совпадает");
            return saved;
        }
        if(!java.util.Set.of("UNKNOWN","PREPARED").contains(saved.state()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Операция не ожидает сверки");
        jdbc.update("INSERT INTO client_message_operation_resolutions(operation_id,claim_token,envelope_hash,actor,provider_message_id,reason) VALUES(?,?,?,?,?,?)",id,token,envelope,actor,receipt,reason);
        jdbc.update("UPDATE client_message_operations SET state='SUCCEEDED',result_channel=?,provider_message_id=?,error_code=NULL,updated_at=UTC_TIMESTAMP(6) WHERE operation_id=? AND claim_token=?",
            "TELEGRAM".equals(saved.platform())?"Telegram":"MAX",receipt,id,token);
        return read(id,false).orElseThrow();
    }
    private Optional<Snapshot> read(String id,boolean lock){return jdbc.query("SELECT operation_id,platform,state,destination_hash,envelope_hash,claim_token,result_channel,provider_message_id,error_code FROM client_message_operations WHERE operation_id=?"+(lock?" FOR UPDATE":""),this::map,id).stream().findFirst();}
    private boolean validReceipt(String platform,String receipt){
        if(receipt==null || receipt.length()>512)return false;
        return "TELEGRAM".equals(platform)?receipt.matches("[1-9][0-9]{0,18}")
            :receipt.matches("(?:mid\\.)?[a-zA-Z0-9_\\-]+") && !receipt.matches("-?[0]+|-[0-9]+");
    }
    private Snapshot map(ResultSet row,int index)throws SQLException{
        String state=row.getString("state");ClientMessageSendResult result="SUCCEEDED".equals(state)?ClientMessageSendResult.sent(row.getString("result_channel"),row.getString("provider_message_id"))
            :"FAILED_KNOWN".equals(state)?ClientMessageSendResult.failed(row.getString("error_code"),"Отправка не началась; повтор разрешён только с прежними параметрами"):null;
        return new Snapshot(row.getString("operation_id"),row.getString("platform"),state,row.getString("destination_hash"),row.getString("envelope_hash"),row.getString("claim_token"),result);
    }
    public record Begin(Snapshot snapshot,boolean acquired){}
}
