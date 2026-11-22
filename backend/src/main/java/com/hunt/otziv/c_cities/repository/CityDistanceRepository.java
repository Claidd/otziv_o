package com.hunt.otziv.c_cities.repository;

import com.hunt.otziv.c_cities.model.CityDistance;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CityDistanceRepository extends CrudRepository<CityDistance, Long> {

    List<CityDistance> findByFromCityIdOrderByPriorityAscDistanceKmAsc(Long fromCityId);

    long countByFromCityId(Long fromCityId);

    @org.springframework.data.jpa.repository.Query("SELECT d.fromCity.id, COUNT(d) FROM CityDistance d GROUP BY d.fromCity.id")
    List<Object[]> countGroupedByFromCity();

    void deleteByFromCityIdGreaterThanEqualAndToCityIdGreaterThanEqual(Long fromCityId, Long toCityId);

    void deleteByFromCityIdOrToCityId(Long fromCityId, Long toCityId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            DELETE d FROM city_distances d
            JOIN cities c ON c.city_id IN (d.from_city_id, d.to_city_id)
            WHERE c.latitude IS NULL OR c.longitude IS NULL
            """, nativeQuery = true)
    int deleteDistancesOfCitiesWithoutCoordinates();
}
