package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerRequest;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
public class ExternalReviewWorkerClient {

    private final RestTemplate restTemplate;
    private final ExternalReviewCheckProperties properties;

    public ExternalReviewWorkerResponse verify(ExternalReviewWorkerRequest request) {
        return restTemplate.postForObject(
                endpoint("/api/external-review-checks/verify"),
                request,
                ExternalReviewWorkerResponse.class
        );
    }

    private String endpoint(String path) {
        String baseUrl = properties.getWorkerBaseUrl() == null ? "" : properties.getWorkerBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }
}
