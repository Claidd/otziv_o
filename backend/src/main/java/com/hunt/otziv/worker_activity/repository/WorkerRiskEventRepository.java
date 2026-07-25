package com.hunt.otziv.worker_activity.repository;

import com.hunt.otziv.worker_activity.model.WorkerRiskEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerRiskEventRepository extends JpaRepository<WorkerRiskEvent, Long> {

    List<WorkerRiskEvent> findByIncident_IdOrderByCreatedAtAscIdAsc(Long incidentId);
}
