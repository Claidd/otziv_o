package com.hunt.otziv.b_bots.repository;

import com.hunt.otziv.b_bots.model.ReviewAccountPoolAlertState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewAccountPoolAlertStateRepository
        extends JpaRepository<ReviewAccountPoolAlertState, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ReviewAccountPoolAlertState s WHERE s.id = :id")
    Optional<ReviewAccountPoolAlertState> findByIdForUpdate(@Param("id") Integer id);
}
