package com.hunt.otziv.manager_control.repository;

import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ManagerDailyControlEventRepository extends CrudRepository<ManagerDailyControlEvent, Long> {

    List<ManagerDailyControlEvent> findByControlOrderByCreatedAtDesc(ManagerDailyControl control);
}
