package com.hunt.otziv.p_products.worker_access.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@ConfigurationProperties(prefix = "otziv.worker.cellular-access")
public class WorkerCellularAccessProperties {

    private Mode mode = Mode.OFF;
    private boolean requireMobileDevice = true;
    private List<String> allowedCidrs = new ArrayList<>();
    private boolean ipIntelligenceEnabled;
    private String ipIntelligenceBaseUrl = "https://api.ipquery.io/";
    private Duration ipIntelligenceTimeout = Duration.ofSeconds(3);
    private Duration ipIntelligenceCacheTtl = Duration.ofHours(24);
    private boolean violationStatisticsEnabled = true;
    private Duration violationEpisodeWindow = Duration.ofMinutes(30);
    private int violationRetentionDays = 90;
    private boolean countUnknownNetworkViolations = true;
    private boolean violationStatisticsVisibleToManagers = true;
    private Set<String> enforcedReasons = new LinkedHashSet<>(Set.of(
            "NON_CELLULAR_NETWORK",
            "VPN_PROXY_OR_DATACENTER"
    ));
    private boolean enforceNativeVirtualDevice = true;

    public enum Mode {
        OFF,
        AUDIT,
        ENFORCE
    }
}
