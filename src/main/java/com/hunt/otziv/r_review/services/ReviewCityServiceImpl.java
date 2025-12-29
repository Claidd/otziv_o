package com.hunt.otziv.r_review.services;
import com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewCityServiceImpl implements ReviewCityService {

    private final ReviewRepository reviewRepository;

    @Override
    public List<CityWithUnpublishedReviewsDTO> getCitiesWithUnpublishedReviews() {
        log.info("Получение городов с неопубликованными отзывами");
        return reviewRepository.findCitiesWithUnpublishedReviewCount();
    }

    // Метод для получения общей статистики
    public Map<String, Object> getCitiesStatistics() {
        List<CityWithUnpublishedReviewsDTO> cities = getCitiesWithUnpublishedReviews();

        long totalCities = cities.size();
        long totalUnpublished = cities.stream()
                .mapToLong(CityWithUnpublishedReviewsDTO::getUnpublishedCount)
                .sum();

        long totalUnpublishedNotArchive = cities.stream()
                .mapToLong(CityWithUnpublishedReviewsDTO::getUnpublishedNotArchiveCount)
                .sum();

        long totalActiveBots = cities.stream()
                .mapToLong(CityWithUnpublishedReviewsDTO::getActiveBotsCount)
                .sum();

        long totalBotBalance = cities.stream()
                .mapToLong(CityWithUnpublishedReviewsDTO::getBotBalance)
                .sum();

        long averagePerCity = totalCities > 0 ? totalUnpublished / totalCities : 0;
        long averageNotArchivePerCity = totalCities > 0 ? totalUnpublishedNotArchive / totalCities : 0;
        long averageBotsPerCity = totalCities > 0 ? totalActiveBots / totalCities : 0;
        long averageBalancePerCity = totalCities > 0 ? totalBotBalance / totalCities : 0;

        // Статистика по статусам
        Map<String, Long> statusStats = cities.stream()
                .collect(Collectors.groupingBy(
                        CityWithUnpublishedReviewsDTO::getBotStatus,
                        Collectors.counting()
                ));

        // Города с критическим дефицитом ботов
        List<String> criticalCities = cities.stream()
                .filter(c -> c.getBotBalance() < -10 ||
                        (c.getActiveBotsCount() == 0 && c.getUnpublishedNotArchiveCount() > 0))
                .map(CityWithUnpublishedReviewsDTO::getCityTitle)
                .collect(Collectors.toList());

        // Города с избытком ботов
        List<String> excessCities = cities.stream()
                .filter(c -> c.getBotBalance() > 10)
                .map(CityWithUnpublishedReviewsDTO::getCityTitle)
                .collect(Collectors.toList());

        return Map.ofEntries(
                Map.entry("totalCities", totalCities),
                Map.entry("totalUnpublished", totalUnpublished),
                Map.entry("totalUnpublishedNotArchive", totalUnpublishedNotArchive),
                Map.entry("totalActiveBots", totalActiveBots),
                Map.entry("totalBotBalance", totalBotBalance),
                // ИСПРАВЬТЕ КЛЮЧИ:
                Map.entry("averagePerCity", averagePerCity),
                Map.entry("averageNotArchivePerCity", averageNotArchivePerCity),
                Map.entry("averageBotsPerCity", averageBotsPerCity),
                Map.entry("averageBalancePerCity", averageBalancePerCity),
                Map.entry("statusStats", statusStats),
                Map.entry("criticalCities", criticalCities),
                Map.entry("excessCities", excessCities),
                Map.entry("criticalCount", (long) criticalCities.size()),
                Map.entry("excessCount", (long) excessCities.size())
        );
    }

    /**
     * Получить общее количество неопубликованных отзывов
     */
    public Long getTotalUnpublishedCount() {
        List<CityWithUnpublishedReviewsDTO> cities = getCitiesWithUnpublishedReviews();
        return cities.stream()
                .mapToLong(CityWithUnpublishedReviewsDTO::getUnpublishedCount)
                .sum();
    }

    /**
     * Получить среднее количество неопубликованных отзывов на город
     */
    public Long getAverageUnpublishedPerCity() {
        List<CityWithUnpublishedReviewsDTO> cities = getCitiesWithUnpublishedReviews();
        if (cities.isEmpty()) {
            return 0L;
        }

        long total = getTotalUnpublishedCount();
        return total / cities.size();
    }

    /**
     * Получить карту: cityId -> unpublishedCount
     */
    public Map<Long, Long> getUnpublishedCountByCityId() {
        return getCitiesWithUnpublishedReviews().stream()
                .collect(Collectors.toMap(
                        CityWithUnpublishedReviewsDTO::getCityId,
                        CityWithUnpublishedReviewsDTO::getUnpublishedCount
                ));
    }

    /**
     * Получить статистику для дашборда
     */
    public Map<String, Object> getDashboardStats() {
        List<CityWithUnpublishedReviewsDTO> cities = getCitiesWithUnpublishedReviews();
        long total = getTotalUnpublishedCount();
        long average = getAverageUnpublishedPerCity();

        return Map.of(
                "totalCities", cities.size(),
                "totalUnpublished", total,
                "averagePerCity", average,
                "citiesWithManyReviews", cities.stream()
                        .filter(c -> c.getUnpublishedCount() > 10)
                        .count(),
                "citiesWithFewReviews", cities.stream()
                        .filter(c -> c.getUnpublishedCount() <= 5 && c.getUnpublishedCount() > 0)
                        .count()
        );
    }



    public List<CityWithUnpublishedReviewsDTO> getAllCitiesWithUnpublishedReviewsNoPagination(
            String search,
            String sort,
            String direction) {

        log.info("Получение ВСЕХ городов для экспорта: search={}, sort={}, direction={}",
                search, sort, direction);

        // Получаем данные в зависимости от поиска
        List<CityWithUnpublishedReviewsDTO> cities;
        if (search != null && !search.trim().isEmpty()) {
            cities = reviewRepository.findAllWithStatsBySearch(search.trim());
        } else {
            cities = reviewRepository.findAllWithStats();
        }

        // Применяем сортировку
        Comparator<CityWithUnpublishedReviewsDTO> comparator = getComparator(sort);

        // Направление сортировки
        if (direction != null && direction.equalsIgnoreCase("desc")) {
            comparator = comparator.reversed();
        }

        // Сортируем
        cities.sort(comparator);

        return cities;
    }

    private Comparator<CityWithUnpublishedReviewsDTO> getComparator(String sort) {
        if (sort == null) {
            // По умолчанию сортируем по названию
            return Comparator.comparing(CityWithUnpublishedReviewsDTO::getCityTitle);
        }

        switch (sort) {
            case "name":
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getCityTitle);
            case "countAll":
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getUnpublishedCount);
            case "countArchive":
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getUnpublishedNotArchiveCount);
            case "bots":
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getActiveBotsCount);
            case "balance":
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getBotBalance);
            default:
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getCityTitle);
        }
    }

}
