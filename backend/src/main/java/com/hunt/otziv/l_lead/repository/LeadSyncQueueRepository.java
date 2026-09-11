package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.LeadSyncQueue;
import org.springframework.data.jpa.repository.JpaRepository;
/** Metadata access only. Dispatch must use LeadCommandRepository's fenced claim protocol. */
public interface LeadSyncQueueRepository extends JpaRepository<LeadSyncQueue, Long> {
}
