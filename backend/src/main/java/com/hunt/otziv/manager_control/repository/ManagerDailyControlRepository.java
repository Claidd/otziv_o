package com.hunt.otziv.manager_control.repository;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

@Repository
public interface ManagerDailyControlRepository extends CrudRepository<ManagerDailyControl, Long> {

    Optional<ManagerDailyControl> findByControlDateAndManager(LocalDate controlDate, Manager manager);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT control FROM ManagerDailyControl control WHERE control.id = :id")
    Optional<ManagerDailyControl> findByIdForUpdate(@Param("id") Long id);

    List<ManagerDailyControl> findByControlDate(LocalDate controlDate);

    List<ManagerDailyControl> findByControlDateBetween(LocalDate from, LocalDate to);
}
