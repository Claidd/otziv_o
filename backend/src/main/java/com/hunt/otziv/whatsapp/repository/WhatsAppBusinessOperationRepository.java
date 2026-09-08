package com.hunt.otziv.whatsapp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WhatsAppBusinessOperationRepository {
    private final JdbcTemplate jdbc;
    public WhatsAppBusinessOperationRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional(propagation=Propagation.MANDATORY)
    public Snapshot freeze(String operationId,String hash,String ciphertext) {
        jdbc.update("""
                INSERT INTO whatsapp_business_send_operations(operation_id,envelope_hash,envelope_ciphertext)
                VALUES(?,?,?) ON DUPLICATE KEY UPDATE operation_id=operation_id
                """,operationId,hash,ciphertext);
        return jdbc.queryForObject("SELECT envelope_hash,envelope_ciphertext FROM whatsapp_business_send_operations WHERE operation_id=? FOR UPDATE",
                (rs,n)->new Snapshot(rs.getString(1),rs.getString(2)),operationId);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void createManual(String operationId,String actorHash) {
        jdbc.update("INSERT INTO whatsapp_manual_send_operations(operation_id,actor_hash) VALUES(?,?)",operationId,actorHash);
    }

    public boolean belongsTo(String operationId,String actorHash) {
        return jdbc.queryForObject("SELECT COUNT(*)>0 FROM whatsapp_manual_send_operations WHERE operation_id=? AND actor_hash=?",Boolean.class,operationId,actorHash);
    }
    public java.util.Optional<Snapshot> find(String operationId) {
        return jdbc.query("SELECT envelope_hash,envelope_ciphertext FROM whatsapp_business_send_operations WHERE operation_id=?",
                (rs,n)->new Snapshot(rs.getString(1),rs.getString(2)),operationId).stream().findFirst();
    }
    public record Snapshot(String hash,String ciphertext) {}
}
