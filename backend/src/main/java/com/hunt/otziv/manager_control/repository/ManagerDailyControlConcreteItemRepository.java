package com.hunt.otziv.manager_control.repository;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ManagerDailyControlConcreteItemRepository extends CrudRepository<ManagerDailyControlConcreteItem, Long> {

    List<ManagerDailyControlConcreteItem> findByParentItem(ManagerDailyControlItem parentItem);

    List<ManagerDailyControlConcreteItem> findByParentItemIn(Collection<ManagerDailyControlItem> parentItems);

    List<ManagerDailyControlConcreteItem> findByControl(ManagerDailyControl control);

    List<ManagerDailyControlConcreteItem> findByControlAndEntityTypeAndFollowUpAtAfter(
            ManagerDailyControl control,
            String entityType,
            LocalDateTime followUpAt
    );

    List<ManagerDailyControlConcreteItem> findByControlAndFollowUpAtAfter(
            ManagerDailyControl control,
            LocalDateTime followUpAt
    );
}
