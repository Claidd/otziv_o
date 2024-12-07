package com.hunt.otziv.c_cities.sevices;

import com.hunt.otziv.c_cities.dto.CityDTO;

import java.util.List;

public interface CityService {
    List<CityDTO> getAllCities();

    boolean saveNewCity(CityDTO cityDTO);

    CityDTO getCityById(Long id);

    boolean updateCity(CityDTO cityDTO);

    void deleteCity(Long cityId);
}
