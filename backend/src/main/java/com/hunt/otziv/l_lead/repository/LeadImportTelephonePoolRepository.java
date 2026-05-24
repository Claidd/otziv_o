package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.LeadImportTelephonePool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface LeadImportTelephonePoolRepository extends JpaRepository<LeadImportTelephonePool, Long> {

    @Query("""
            SELECT p
            FROM LeadImportTelephonePool p
            JOIN FETCH p.manager m
            JOIN FETCH p.telephone t
            WHERE p.active = true
              AND m.id IN :managerIds
            ORDER BY m.id ASC, p.priorityOrder ASC, t.id ASC
            """)
    List<LeadImportTelephonePool> findActiveByManagerIds(@Param("managerIds") Collection<Long> managerIds);
}
