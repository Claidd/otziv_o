package com.hunt.otziv.c_cities.repository;

import com.hunt.otziv.c_cities.model.CityDistance;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CityDistanceRepository extends CrudRepository<CityDistance, Long> {

    List<CityDistance> findByFromCityIdOrderByPriorityAscDistanceKmAsc(Long fromCityId);

    long countByFromCityId(Long fromCityId);

    long countByFromCityIdGreaterThanEqualAndToCityIdGreaterThanEqual(Long fromCityId, Long toCityId);

    void deleteByFromCityIdGreaterThanEqualAndToCityIdGreaterThanEqual(Long fromCityId, Long toCityId);

    void deleteByFromCityIdOrToCityId(Long fromCityId, Long toCityId);
}
