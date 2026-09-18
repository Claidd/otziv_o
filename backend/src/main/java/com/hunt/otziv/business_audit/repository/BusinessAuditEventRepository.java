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
        SELECT COUNT(e) > 0 FROM BusinessAuditEvent e
        WHERE lower(e.actor) = lower(:actor)
          AND e.action = 'CREDENTIAL_REVEAL'
          AND e.entityType = :entityType AND e.entityId = :entityId
          AND e.details LIKE concat(:prefix, '%')
          AND e.createdAt > :since AND e.createdAt <= :until
        """)
    boolean hasCredentialReveal(@Param("actor") String actor,
            @Param("entityType") String entityType, @Param("entityId") String entityId,
            @Param("prefix") String prefix, @Param("since") LocalDateTime since,
            @Param("until") LocalDateTime until);

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
