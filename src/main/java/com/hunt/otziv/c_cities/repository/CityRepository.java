package com.hunt.otziv.c_cities.repository;

import com.hunt.otziv.c_cities.model.City;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CityRepository extends CrudRepository<City, Integer> {

    @Override
    List<City> findAll();

    City findById(Long id);
}
