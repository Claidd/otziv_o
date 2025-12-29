package com.hunt.otziv.c_cities.dto;

public record CityReviewCountDTO(
        Long cityId,
        String cityName,
        Long unpublishedReviewCount
) {}