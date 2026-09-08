package com.hunt.otziv.p_products.status.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A business occurrence is allocated once and retained until its receipt is confirmed. */
@Service
public class OrderNotificationOccurrences {
    private final JdbcTemplate jdbc;
    public OrderNotificationOccurrences(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public String reserve(long orderId,String kind,long businessGeneration) {
        return reserveCurrent(orderId, kind, businessGeneration);
    }

    /** Durable publication intent and its identity must commit with the publication itself. */
    @Transactional(propagation=Propagation.MANDATORY)
    public String reserveInCurrentTransaction(long orderId,String kind,long businessGeneration) {
        return reserveCurrent(orderId, kind, businessGeneration);
    }

    private String reserveCurrent(long orderId,String kind,long businessGeneration) {
        if(orderId<=0||kind==null||kind.isBlank()||kind.length()>180||businessGeneration<0)
            throw new IllegalArgumentException("Invalid order notification occurrence");
        if (businessGeneration == 0) {
            throw new IllegalStateException("legacy_operation_unverified");
        }
        jdbc.update("INSERT INTO order_client_message_occurrences(order_id,logical_kind,operation_id,generation,business_generation) VALUES(?,?,?,1,?) ON DUPLICATE KEY UPDATE order_id=order_id",
                orderId,kind,UUID.randomUUID().toString(),businessGeneration);
        var current=jdbc.queryForObject("SELECT operation_id,business_generation,confirmed,generation FROM order_client_message_occurrences WHERE order_id=? AND logical_kind=? FOR UPDATE",
                (rs,n)->new Current(rs.getString(1),rs.getLong(2),rs.getBoolean(3),rs.getLong(4)),orderId,kind);
        if(!current.confirmed()) {
            jdbc.update("UPDATE order_client_message_occurrences SET business_generation=GREATEST(business_generation,?) WHERE order_id=? AND logical_kind=?",businessGeneration,orderId,kind);
            return current.id();
        }
        if(businessGeneration<=current.businessGeneration())return current.id();
        String next=UUID.randomUUID().toString();
        jdbc.update("UPDATE order_client_message_occurrences SET operation_id=?,generation=?,business_generation=?,confirmed=FALSE WHERE order_id=? AND logical_kind=?",
                next,Math.addExact(current.generation(),1),businessGeneration,orderId,kind);
        return next;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void confirm(long orderId,String kind,String operationId) {
        confirmCurrent(orderId, kind, operationId);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void confirmInCurrentTransaction(long orderId,String kind,String operationId) {
        confirmCurrent(orderId, kind, operationId);
    }

    private void confirmCurrent(long orderId,String kind,String operationId) {
        int changed=jdbc.update("UPDATE order_client_message_occurrences SET confirmed=TRUE WHERE order_id=? AND logical_kind=? AND operation_id=?",
                orderId,kind,operationId);
        if(changed!=1)throw new IllegalStateException("Order notification receipt belongs to an obsolete occurrence");
    }
    private record Current(String id,long businessGeneration,boolean confirmed,long generation){}
}
