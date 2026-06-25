package com.hunt.otziv.business_audit.repository;

import com.hunt.otziv.business_audit.model.BusinessAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;

@Repository
public interface BusinessAuditEventRepository extends JpaRepository<BusinessAuditEvent, Long> {

    @Query("""
        SELECT COUNT(e)
        FROM BusinessAuditEvent e
        WHERE e.actor = :actor
          AND e.action IN :actions
          AND e.createdAt >= :since
    """)
    long countByActorAndActionsSince(
            @Param("actor") String actor,
            @Param("actions") Collection<String> actions,
            @Param("since") LocalDateTime since
    );
}
