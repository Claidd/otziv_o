package com.hunt.otziv.manager_control.repository;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ManagerDailyControlRepository extends CrudRepository<ManagerDailyControl, Long> {

    Optional<ManagerDailyControl> findByControlDateAndManager(LocalDate controlDate, Manager manager);

    List<ManagerDailyControl> findByControlDate(LocalDate controlDate);
}
