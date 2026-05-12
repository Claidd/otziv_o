package com.hunt.otziv.reputationai.infrastructure.search.yandex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.search.SearchProvider;
import com.hunt.otziv.reputationai.infrastructure.search.SearchQuery;
import com.hunt.otziv.reputationai.infrastructure.search.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class YandexSearchProvider implements SearchProvider {

    private final ReputationAiProperties properties;
    private final ObjectMapper objectMapper;
    private final YandexSearchXmlParser xmlParser;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String providerName() {
        return "yandex";
    }

    @Override
    public boolean isAvailable() {
        ReputationAiProperties.YandexSearch yandex = properties.getSearch().getYandex();
        return !isBlank(yandex.getApiKey()) && !isBlank(yandex.getFolderId());
    }

    @Override
    public List<SearchResult> search(SearchQuery query) {
        if (query.text().isBlank() || !isAvailable()) {
            return List.of();
        }

        ReputationAiProperties.Search search = properties.getSearch();
        ReputationAiProperties.YandexSearch yandex = search.getYandex();
        int limit = Math.min(query.limit(), search.getResultsPerQuery());

        try {
            String requestJson = objectMapper.writeValueAsString(buildRequestBody(query.text(), limit, yandex));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(yandex.getBaseUrl()) + "/v2/web/search"))
                    .timeout(yandex.getTimeout())
                    .header("Authorization", "Api-Key " + yandex.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Yandex Search API returned HTTP {}", response.statusCode());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response.body());
            String rawData = root.path("rawData").asText("");
            if (rawData.isBlank()) {
                return List.of();
            }

            String xml = new String(Base64.getDecoder().decode(rawData), StandardCharsets.UTF_8);
            return xmlParser.parse(xml, limit);
        } catch (Exception exception) {
            log.warn("Yandex Search API request failed: {}", exception.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> buildRequestBody(String queryText, int limit, ReputationAiProperties.YandexSearch yandex) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("searchType", yandex.getSearchType());
        query.put("queryText", queryText);
        query.put("familyMode", yandex.getFamilyMode());
        query.put("page", "0");
        query.put("fixTypoMode", "FIX_TYPO_MODE_ON");

        Map<String, Object> groupSpec = new LinkedHashMap<>();
        groupSpec.put("groupMode", "GROUP_MODE_FLAT");
        groupSpec.put("groupsOnPage", String.valueOf(limit));
        groupSpec.put("docsInGroup", "1");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("groupSpec", groupSpec);
        body.put("maxPassages", "2");
        body.put("folderId", yandex.getFolderId());
        body.put("responseFormat", "FORMAT_XML");
        body.put("userAgent", properties.getUserAgent());

        if (!isBlank(yandex.getRegion())) {
            body.put("region", yandex.getRegion());
        }

        return body;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://searchapi.api.cloud.yandex.net";
        }

        return value.replaceAll("/+$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
