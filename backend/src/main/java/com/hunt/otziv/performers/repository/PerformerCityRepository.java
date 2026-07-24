package com.hunt.otziv.performers.repository;

import com.hunt.otziv.performers.model.PerformerCity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PerformerCityRepository extends CrudRepository<PerformerCity, Long> {

    @Query("""
        SELECT pc.performer.id AS performerId, pc.city.id AS cityId
        FROM PerformerCity pc
        WHERE pc.active = true
          AND pc.performer.id IN :performerIds
    """)
    List<PerformerCityIdRow> findActiveCityIdsByPerformerIds(@Param("performerIds") Collection<Long> performerIds);

    interface PerformerCityIdRow {
        Long getPerformerId();

        Long getCityId();
    }
}
