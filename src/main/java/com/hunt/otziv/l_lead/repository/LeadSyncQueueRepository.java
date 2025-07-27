package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.LeadSyncQueue;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LeadSyncQueueRepository extends CrudRepository<LeadSyncQueue, Long> {
}
