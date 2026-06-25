package com.hunt.otziv.manager_control.repository;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ManagerDailyControlItemRepository extends CrudRepository<ManagerDailyControlItem, Long> {

    List<ManagerDailyControlItem> findByControl(ManagerDailyControl control);

    List<ManagerDailyControlItem> findByControlIn(Collection<ManagerDailyControl> controls);
}
