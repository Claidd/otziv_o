package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.dto.LeadCommandIdentity;
import com.hunt.otziv.l_lead.dto.LeadCommandReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Receipt -> source entity -> target telephone -> Lead. Every row joins the mutation TX. */
@Repository
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class LeadInboundCommandRepository {
    private final JdbcTemplate jdbc;

    public LeadCommandReceipt lockReceipt(LeadCommandIdentity id, String hash) {
        jdbc.update("""
                INSERT INTO lead_inbound_receipts(source_id,operation_id,entity_id,entity_version,command_kind,payload_hash,outcome)
                VALUES(?,?,?,?,?,?,'PENDING') ON DUPLICATE KEY UPDATE operation_id=operation_id
                """,id.sourceId(),id.operationId(),id.entityId(),id.entityVersion(),id.kind(),hash);
        var found=jdbc.query("""
                SELECT entity_id,entity_version,command_kind,payload_hash,outcome,applied_version
                FROM lead_inbound_receipts WHERE source_id=? AND operation_id=? FOR UPDATE
                """,(rs,n)->new LeadCommandReceipt(1,id.sourceId(),rs.getLong(1),rs.getLong(2),id.operationId(),
                rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6)),id.sourceId(),id.operationId());
        if(found.isEmpty())throw new Conflict("LEAD_ENTITY_VERSION_CONFLICT");
        var receipt=found.getFirst();
        if(!receipt.equals(LeadCommandReceipt.of(id,hash,receipt.outcome(),receipt.appliedVersion()))) {
            throw new Conflict("LEAD_OPERATION_PAYLOAD_CONFLICT");
        }
        return receipt;
    }

    public Entity lockEntity(LeadCommandIdentity id, String phone) {
        jdbc.update("""
                INSERT INTO lead_inbound_entities(source_id,entity_id,telephone_key) VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE entity_id=entity_id
                """,id.sourceId(),id.entityId(),phone);
        return jdbc.queryForObject("""
                SELECT last_version,telephone_key,target_lead_id FROM lead_inbound_entities
                WHERE source_id=? AND entity_id=? FOR UPDATE
                """,(rs,n)->new Entity(rs.getLong(1),rs.getString(2),rs.getObject(3,Long.class)),id.sourceId(),id.entityId());
    }

    public void lockTarget(String phone, LeadCommandIdentity identity) {
        jdbc.update("INSERT INTO lead_inbound_targets(telephone_key) VALUES(?) ON DUPLICATE KEY UPDATE telephone_key=telephone_key",phone);
        var target=jdbc.queryForObject("SELECT source_id,entity_id FROM lead_inbound_targets WHERE telephone_key=? FOR UPDATE",
                (rs,n)->new Target(rs.getString(1),rs.getObject(2,Long.class)),phone);
        if(target.source()!=null && (identity==null || !target.source().equals(identity.sourceId())
                || target.entity()!=identity.entityId())) throw new Conflict("LEAD_TARGET_REQUIRES_BOUND_SOURCE");
        if(identity!=null && target.source()==null)jdbc.update("UPDATE lead_inbound_targets SET source_id=?,entity_id=? WHERE telephone_key=?",
                identity.sourceId(),identity.entityId(),phone);
    }

    public void applied(LeadCommandIdentity id, String phone, long targetId) {
        int changed=jdbc.update("""
                UPDATE lead_inbound_entities SET last_version=?,telephone_key=?,target_lead_id=?
                WHERE source_id=? AND entity_id=? AND last_version<?
                """,id.entityVersion(),phone,targetId,id.sourceId(),id.entityId(),id.entityVersion());
        if(changed!=1)throw new Conflict("LEAD_ENTITY_VERSION_CONFLICT");
    }

    public LeadCommandReceipt finish(LeadCommandIdentity id,String hash,String outcome,long appliedVersion) {
        int changed=jdbc.update("""
                UPDATE lead_inbound_receipts SET outcome=?,applied_version=?
                WHERE source_id=? AND operation_id=? AND outcome='PENDING' AND payload_hash=?
                """,outcome,appliedVersion,id.sourceId(),id.operationId(),hash);
        if(changed!=1)throw new IllegalStateException("LEAD_RECEIPT_COMPLETION_CONFLICT");
        return LeadCommandReceipt.of(id,hash,outcome,appliedVersion);
    }

    public record Entity(long version,String phone,Long targetId) {}
    private record Target(String source,Long entity) {}
    public static final class Conflict extends RuntimeException {
        public Conflict(String code){super(code);}
    }
}
