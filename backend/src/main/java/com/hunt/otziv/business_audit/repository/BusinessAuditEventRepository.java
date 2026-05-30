package com.hunt.otziv.business_audit.repository;

import com.hunt.otziv.business_audit.model.BusinessAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BusinessAuditEventRepository extends JpaRepository<BusinessAuditEvent, Long> {
}
