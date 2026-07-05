package com.hunt.otziv.performers.repository;

import com.hunt.otziv.performers.model.PerformerCity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PerformerCityRepository extends CrudRepository<PerformerCity, Long> {
}
