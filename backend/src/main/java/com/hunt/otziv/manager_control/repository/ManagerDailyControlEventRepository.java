package com.hunt.otziv.manager_control.repository;

import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ManagerDailyControlEventRepository extends CrudRepository<ManagerDailyControlEvent, Long> {

    List<ManagerDailyControlEvent> findByControlOrderByCreatedAtDesc(ManagerDailyControl control);

    long countByControlInAndEventType(
            Collection<ManagerDailyControl> controls,
            ManagerDailyControlEventType eventType
    );

    interface ManagerEventCount { Long getManagerId(); long getTotal(); }

    @Query("""
            SELECT event.control.manager.id AS managerId, COUNT(event) AS total
            FROM ManagerDailyControlEvent event
            WHERE event.control IN :controls AND event.eventType = :eventType
            GROUP BY event.control.manager.id
            """)
    List<ManagerEventCount> countEventsByManager(@Param("controls") Collection<ManagerDailyControl> controls,
                                               @Param("eventType") ManagerDailyControlEventType eventType);

    @Query("""
        SELECT event
        FROM ManagerDailyControlEvent event
        JOIN FETCH event.control control
        LEFT JOIN FETCH event.item
        WHERE control.manager.id = :managerId
          AND event.createdAt >= :from
          AND event.createdAt < :to
        ORDER BY event.createdAt ASC, event.id ASC
    """)
    List<ManagerDailyControlEvent> findForManagerAudit(
            @Param("managerId") Long managerId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );
}
