package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerRequest;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.HttpStatusCodeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.external_review_checks.config.ExternalReviewWorkerHttpConfig;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ExternalReviewWorkerClient {

    private final RestTemplate restTemplate;
    private final RestTemplate readinessRestTemplate;
    private static final ObjectMapper ADMISSION_JSON = new ObjectMapper();
    private final ExternalReviewCheckProperties properties;
    private final ExternalReviewCheckRuntimeSwitch runtimeSwitch;

    public ExternalReviewWorkerClient(
            @Qualifier("externalReviewWorkerRestTemplate") RestTemplate restTemplate,
            ExternalReviewCheckProperties properties,
            ExternalReviewCheckRuntimeSwitch runtimeSwitch
    ) {
        this(restTemplate, new ExternalReviewWorkerHttpConfig().externalReviewWorkerReadinessRestTemplate(properties), properties, runtimeSwitch);
    }

    @Autowired
    public ExternalReviewWorkerClient(
            @Qualifier("externalReviewWorkerRestTemplate") RestTemplate restTemplate,
            @Qualifier("externalReviewWorkerReadinessRestTemplate") RestTemplate readinessRestTemplate,
            ExternalReviewCheckProperties properties,
            ExternalReviewCheckRuntimeSwitch runtimeSwitch
    ) {
        this.restTemplate = restTemplate;
        this.readinessRestTemplate = readinessRestTemplate;
        this.properties = properties;
        this.runtimeSwitch = runtimeSwitch;
    }

    public boolean isReady() {
        if (!runtimeSwitch.isEnabled()) return false;
        try {
            Map<?, ?> body = readinessRestTemplate.getForObject(endpoint("/ready"), Map.class);
            return body != null && Boolean.TRUE.equals(body.get("ok")) && "ready".equals(body.get("state"));
        } catch (RuntimeException unavailable) {
            // No claim has been acquired. Unavailability must not consume its retry budget.
            return false;
        }
    }

    public ExternalReviewWorkerResponse verify(ExternalReviewWorkerRequest request) {
        if (!runtimeSwitch.isEnabled()) {
            throw new ExternalReviewWorkerDisabledException();
        }
        try { return restTemplate.postForObject(
                endpoint("/api/external-review-checks/verify"),
                request,
                ExternalReviewWorkerResponse.class
        ); } catch (HttpStatusCodeException failure) {
            if (admissionRefused(failure)) throw new ExternalReviewWorkerAdmissionException();
            throw failure;
        }
    }

    private boolean admissionRefused(HttpStatusCodeException failure) {
        int status = failure.getStatusCode().value();
        if ((status != 429 && status != 503) || failure.getResponseHeaders() == null
                || !"rejected-v1".equals(failure.getResponseHeaders().getFirst("X-Otziv-Admission"))
                || failure.getResponseBodyAsByteArray().length > 4096) return false;
        try {
            var body = ADMISSION_JSON.readTree(failure.getResponseBodyAsByteArray());
            String code = body.path("code").asText();
            return "ERROR".equals(body.path("status").asText())
                    && ((status == 429 && "worker_busy".equals(code))
                    || (status == 503 && ("worker_not_ready".equals(code) || "draining".equals(code))));
        } catch (Exception malformed) { return false; }
    }

    private String endpoint(String path) {
        String baseUrl = properties.getWorkerBaseUrl() == null ? "" : properties.getWorkerBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }
}
