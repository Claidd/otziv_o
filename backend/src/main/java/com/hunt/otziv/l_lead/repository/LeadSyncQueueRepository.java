package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.LeadSyncQueue;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

public interface LeadSyncQueueRepository extends JpaRepository<LeadSyncQueue, Long> {
    default List<LeadSyncQueue> findTopOrderByLastAttemptAtAsc(int limit) {
        return findAllByOrderByLastAttemptAtAsc(PageRequest.of(0, limit));
    }
    List<LeadSyncQueue> findAllByOrderByLastAttemptAtAsc(Pageable pageable);
}