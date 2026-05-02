package com.hunt.otziv.r_review.services;

import com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO;

import java.util.List;
import java.util.Map;

public interface ReviewCityService {
    List<CityWithUnpublishedReviewsDTO> getCitiesWithUnpublishedReviews();

    Map<String, Object> getCitiesStatistics();

    List<CityWithUnpublishedReviewsDTO> getAllCitiesWithUnpublishedReviewsNoPagination(String search, String sort, String direction);
}
