package com.hunt.otziv.p_products.worker_access.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
@Slf4j
public class WorkerIpIntelligenceClient {

    private static final IpIntelligence UNKNOWN = new IpIntelligence(false, false, false, "", "unavailable");

    private final WorkerCellularAccessProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Cache<String, IpIntelligence> resultCache;
    private final Cache<String, IpIntelligence> failureCache;

    public WorkerIpIntelligenceClient(
            WorkerCellularAccessProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(safeTimeout(properties.getIpIntelligenceTimeout()))
                .build();
        this.resultCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(safeCacheTtl(properties.getIpIntelligenceCacheTtl()))
                .build();
        this.failureCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(1))
                .build();
    }

    public IpIntelligence lookup(String rawIp) {
        if (!properties.isIpIntelligenceEnabled()) {
            return UNKNOWN;
        }
        String ip = normalizeLiteralIp(rawIp);
        if (ip == null) {
            return UNKNOWN;
        }

        IpIntelligence cached = resultCache.getIfPresent(ip);
        if (cached != null) {
            return cached;
        }
        IpIntelligence recentFailure = failureCache.getIfPresent(ip);
        if (recentFailure != null) {
            return recentFailure;
        }

        IpIntelligence loaded = request(ip);
        if (loaded.known()) {
            resultCache.put(ip, loaded);
        } else {
            failureCache.put(ip, loaded);
        }
        return loaded;
    }

    private IpIntelligence request(String ip) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint(ip))
                    .timeout(safeTimeout(properties.getIpIntelligenceTimeout()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("IP intelligence lookup failed: status={}", response.statusCode());
                return UNKNOWN;
            }
            IpQueryResponse payload = objectMapper.readValue(response.body(), IpQueryResponse.class);
            if (payload == null || payload.risk() == null) {
                return UNKNOWN;
            }
            IpQueryRisk risk = payload.risk();
            boolean risky = risk.isVpn() || risk.isProxy() || risk.isTor() || risk.isDatacenter();
            String organization = payload.isp() == null ? "" : safe(payload.isp().organization());
            return new IpIntelligence(true, risk.isMobile(), risky, organization, "ipquery");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("IP intelligence lookup interrupted");
            return UNKNOWN;
        } catch (Exception exception) {
            log.warn("IP intelligence lookup failed: {}", exception.getClass().getSimpleName());
            return UNKNOWN;
        }
    }

    private URI endpoint(String ip) {
        String baseUrl = safe(properties.getIpIntelligenceBaseUrl()).trim();
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("IP intelligence base URL is empty");
        }
        return URI.create((baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + ip);
    }

    private String normalizeLiteralIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return null;
        }
        String value = rawIp.trim();
        int zoneIndex = value.indexOf('%');
        if (zoneIndex >= 0) {
            value = value.substring(0, zoneIndex);
        }
        if (!value.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (Exception exception) {
            return null;
        }
    }

    private Duration safeTimeout(Duration configured) {
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(3)
                : configured;
    }

    private Duration safeCacheTtl(Duration configured) {
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofHours(24)
                : configured;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record IpIntelligence(
            boolean known,
            boolean mobile,
            boolean risky,
            String organization,
            String source
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IpQueryResponse(IpQueryIsp isp, IpQueryRisk risk) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IpQueryIsp(@JsonProperty("org") String organization) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IpQueryRisk(
            @JsonProperty("is_mobile") boolean isMobile,
            @JsonProperty("is_vpn") boolean isVpn,
            @JsonProperty("is_tor") boolean isTor,
            @JsonProperty("is_proxy") boolean isProxy,
            @JsonProperty("is_datacenter") boolean isDatacenter
    ) {
    }
}
