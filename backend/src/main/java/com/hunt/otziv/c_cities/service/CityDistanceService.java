package com.hunt.otziv.c_cities.service;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.model.CityDistance;
import com.hunt.otziv.c_cities.repository.CityDistanceRepository;
import com.hunt.otziv.c_cities.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CityDistanceService {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final CityRepository cityRepository;
    private final CityDistanceRepository cityDistanceRepository;

    @Transactional
    public RebuildResult rebuildForCityIdsFrom(long minCityId) {
        List<City> cities = citiesWithCoordinates(minCityId);
        cityDistanceRepository.deleteByFromCityIdGreaterThanEqualAndToCityIdGreaterThanEqual(minCityId, minCityId);

        int saved = saveDistances(cities, cities);
        cities.forEach(city -> city.setDistanceMatrixReady(true));
        cities.forEach(cityRepository::save);

        long totalEligible = cityRepository.findAll().stream()
                .filter(city -> city.getId() != null && city.getId() >= minCityId)
                .count();
        long withoutCoordinates = Math.max(0, totalEligible - cities.size());
        return new RebuildResult(cities.size(), withoutCoordinates, saved);
    }

    @Transactional
    public RebuildResult rebuildForCity(Long cityId) {
        City city = cityRepository.findById(cityId);
        if (city == null || !hasCoordinates(city)) {
            return new RebuildResult(0, 1, 0);
        }
        List<City> cities = citiesWithCoordinates(0);
        cityDistanceRepository.deleteByFromCityIdOrToCityId(cityId, cityId);

        int saved = saveDistances(List.of(city), cities)
                + saveDistances(cities.stream().filter(other -> !cityId.equals(other.getId())).toList(), List.of(city));
        city.setDistanceMatrixReady(true);
        cityRepository.save(city);
        return new RebuildResult(1, 0, saved);
    }

    @Transactional(readOnly = true)
    public Map<Long, Integer> distancesFrom(Long cityId) {
        if (cityId == null) {
            return Map.of();
        }
        Map<Long, Integer> result = new LinkedHashMap<>();
        cityDistanceRepository.findByFromCityIdOrderByPriorityAscDistanceKmAsc(cityId)
                .forEach(distance -> {
                    if (distance.getToCity() != null && distance.getToCity().getId() != null) {
                        result.put(distance.getToCity().getId(), distance.getDistanceKm());
                    }
                });
        return result;
    }

    @Transactional(readOnly = true)
    public long distanceCountFrom(Long cityId) {
        if (cityId == null) {
            return 0;
        }
        return cityDistanceRepository.countByFromCityId(cityId);
    }

    private int saveDistances(List<City> fromCities, List<City> toCities) {
        List<CityDistance> distances = fromCities.stream()
                .flatMap(from -> toCities.stream()
                        .filter(to -> !from.getId().equals(to.getId()))
                        .map(to -> CityDistance.builder()
                                .fromCity(from)
                                .toCity(to)
                                .distanceKm(distanceKm(from, to))
                                .build()))
                .sorted(Comparator
                        .comparing((CityDistance distance) -> distance.getFromCity().getId())
                        .thenComparingInt(CityDistance::getDistanceKm))
                .toList();
        cityDistanceRepository.saveAll(distances);
        return distances.size();
    }

    private List<City> citiesWithCoordinates(long minCityId) {
        return cityRepository.findAll().stream()
                .filter(city -> city.getId() != null && city.getId() >= minCityId)
                .filter(this::hasCoordinates)
                .sorted(Comparator.comparing(City::getId))
                .toList();
    }

    private boolean hasCoordinates(City city) {
        return city != null && city.getLatitude() != null && city.getLongitude() != null;
    }

    private int distanceKm(City from, City to) {
        double fromLat = radians(from.getLatitude());
        double toLat = radians(to.getLatitude());
        double deltaLat = toLat - fromLat;
        double deltaLon = radians(to.getLongitude()) - radians(from.getLongitude());
        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(fromLat) * Math.cos(toLat) * Math.pow(Math.sin(deltaLon / 2), 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.max(1, (int) Math.round(EARTH_RADIUS_KM * c));
    }

    private double radians(BigDecimal value) {
        return Math.toRadians(value.doubleValue());
    }

    public record RebuildResult(long citiesWithCoordinates, long citiesWithoutCoordinates, int distancesSaved) {
    }
}
