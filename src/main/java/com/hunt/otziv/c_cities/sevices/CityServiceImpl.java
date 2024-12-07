package com.hunt.otziv.c_cities.sevices;

import com.hunt.otziv.c_cities.dto.CityDTO;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CityServiceImpl implements CityService{

    private final CityRepository cityRepository;

    @Override
    public List<CityDTO> getAllCities() {
        return cityRepository.findAll().stream().map(this::toCityDTO).sorted(Comparator.comparing(CityDTO::getCityTitle)).collect(Collectors.toList());
    }

    @Override
    public boolean saveNewCity(CityDTO cityDTO) {
        City city = new City();
        city.setTitle(cityDTO.getCityTitle());
        cityRepository.save(city);
         return true;
    }

    @Override
    public CityDTO getCityById(Long cityId) {
        return toCityDTO(cityRepository.findById(cityId));
    }

    @Override
    public boolean updateCity(CityDTO cityDTO){ // Обновление продукта
        log.info("2. Вошли в обновление города");
        City saveCity = cityRepository.findById(cityDTO.getId());
        log.info("Достали Продукт");
        boolean isChanged = false;
        /*Временная проверка сравнений*/
        System.out.println("City title: " + !Objects.equals(cityDTO.getCityTitle(), saveCity.getTitle()));
        if (!Objects.equals(cityDTO.getCityTitle(), saveCity.getTitle())){ /*Проверка смены названия*/
            log.info("Обновляем Город");
            saveCity.setTitle(cityDTO.getCityTitle());
            isChanged = true;
        }

        if  (isChanged){
            log.info("3. Начали сохранять обновленный Город в БД");
            cityRepository.save(saveCity);
            log.info("4. Сохранили обновленный Город в БД");
            return true;
        }
        else {
            log.info("3. Изменений не было, сущность в БД не изменена");
            return false;
        }
    } // Обновление продукта

    @Override
    public void deleteCity(Long cityId) {
        City deleteCity = cityRepository.findById(cityId);
        cityRepository.delete(deleteCity);
    }


    private CityDTO toCityDTO(City city) {
        return CityDTO.builder()
                .id(city.getId())
                .cityTitle(city.getTitle())
                .build();
    }
}
