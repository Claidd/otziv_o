package com.hunt.otziv.manager_control.repository;

import com.hunt.otziv.manager_control.model.ManagerQueueStateEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ManagerQueueStateEventRepository extends CrudRepository<ManagerQueueStateEvent, Long> {
    Optional<ManagerQueueStateEvent> findTopByManager_IdOrderByObservedAtDescIdDesc(Long managerId);
    List<ManagerQueueStateEvent> findByManager_IdAndObservedAtBetweenOrderByObservedAtAscIdAsc(Long managerId, LocalDateTime from, LocalDateTime to);
    long deleteByCreatedAtBefore(LocalDateTime threshold);
}
