package com.hunt.otziv.reputationai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "reputation-ai")
public class ReputationAiProperties {

    private String provider = "deepseek";
    private int maxWebsitePages = 36;
    private int maxDeepWebsitePages = 12;
    private int maxWebsiteChars = 20_000;
    private Duration websiteTimeout = Duration.ofSeconds(8);
    private int maxWebsiteRequests = 48;
    private int maxWebsiteResponseBytes = 1_500_000;
    private int maxWebsiteTotalBytes = 8_000_000;
    private int maxWebsiteUrlLength = 2_048;
    private int maxWebsiteInputUrls = 40;
    private Duration websiteCrawlDeadline = Duration.ofSeconds(45);
    private String userAgent = "OtzivReputationAI/1.0";
    private Search search = new Search();
    private YandexGpt yandex = new YandexGpt();
    private DeepSeek deepseek = new DeepSeek();
    private OpenAi openai = new OpenAi();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getMaxWebsitePages() {
        return maxWebsitePages;
    }

    public void setMaxWebsitePages(int maxWebsitePages) {
        this.maxWebsitePages = Math.max(1, Math.min(64, maxWebsitePages));
    }

    public int getMaxDeepWebsitePages() {
        return maxDeepWebsitePages;
    }

    public void setMaxDeepWebsitePages(int maxDeepWebsitePages) {
        this.maxDeepWebsitePages = Math.max(1, Math.min(20, maxDeepWebsitePages));
    }

    public int getMaxWebsiteChars() {
        return maxWebsiteChars;
    }

    public void setMaxWebsiteChars(int maxWebsiteChars) {
        this.maxWebsiteChars = Math.max(1000, Math.min(100_000, maxWebsiteChars));
    }

    public Duration getWebsiteTimeout() {
        return websiteTimeout;
    }

    public void setWebsiteTimeout(Duration websiteTimeout) {
        this.websiteTimeout = boundedDuration(websiteTimeout, Duration.ofSeconds(8), Duration.ofMillis(250), Duration.ofSeconds(30));
    }

    public int getMaxWebsiteRequests() {
        return maxWebsiteRequests;
    }

    public void setMaxWebsiteRequests(int maxWebsiteRequests) {
        this.maxWebsiteRequests = Math.max(1, Math.min(128, maxWebsiteRequests));
    }

    public int getMaxWebsiteResponseBytes() {
        return maxWebsiteResponseBytes;
    }

    public void setMaxWebsiteResponseBytes(int maxWebsiteResponseBytes) {
        this.maxWebsiteResponseBytes = Math.max(16_384, Math.min(5_000_000, maxWebsiteResponseBytes));
    }

    public int getMaxWebsiteTotalBytes() {
        return maxWebsiteTotalBytes;
    }

    public void setMaxWebsiteTotalBytes(int maxWebsiteTotalBytes) {
        this.maxWebsiteTotalBytes = Math.max(65_536, Math.min(25_000_000, maxWebsiteTotalBytes));
    }

    public int getMaxWebsiteUrlLength() {
        return maxWebsiteUrlLength;
    }

    public void setMaxWebsiteUrlLength(int maxWebsiteUrlLength) {
        this.maxWebsiteUrlLength = Math.max(256, Math.min(8_192, maxWebsiteUrlLength));
    }

    public int getMaxWebsiteInputUrls() {
        return maxWebsiteInputUrls;
    }

    public void setMaxWebsiteInputUrls(int maxWebsiteInputUrls) {
        this.maxWebsiteInputUrls = Math.max(1, Math.min(100, maxWebsiteInputUrls));
    }

    public Duration getWebsiteCrawlDeadline() {
        return websiteCrawlDeadline;
    }

    public void setWebsiteCrawlDeadline(Duration websiteCrawlDeadline) {
        this.websiteCrawlDeadline = boundedDuration(
                websiteCrawlDeadline,
                Duration.ofSeconds(45),
                Duration.ofSeconds(1),
                Duration.ofMinutes(2)
        );
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent == null || userAgent.isBlank() ? "OtzivReputationAI/1.0" : userAgent.trim();
    }

    private static Duration boundedDuration(Duration value, Duration fallback, Duration minimum, Duration maximum) {
        Duration safe = value == null || value.isNegative() || value.isZero() ? fallback : value;
        if (safe.compareTo(minimum) < 0) {
            return minimum;
        }
        return safe.compareTo(maximum) > 0 ? maximum : safe;
    }

    public Search getSearch() {
        return search;
    }

    public void setSearch(Search search) {
        this.search = search == null ? new Search() : search;
    }

    public YandexGpt getYandex() {
        return yandex;
    }

    public void setYandex(YandexGpt yandex) {
        this.yandex = yandex == null ? new YandexGpt() : yandex;
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(DeepSeek deepseek) {
        this.deepseek = deepseek == null ? new DeepSeek() : deepseek;
    }

    public OpenAi getOpenai() {
        return openai;
    }

    public void setOpenai(OpenAi openai) {
        this.openai = openai == null ? new OpenAi() : openai;
    }

    public static class YandexGpt {
        private String apiKey = "";
        private String folderId = "";
        private String baseUrl = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion";
        private String responsesBaseUrl = "https://ai.api.cloud.yandex.net/v1";
        private String apiMode = "completion";
        private String model = "yandexgpt";
        private Duration timeout = Duration.ofMinutes(6);
        private int maxTokens = 24000;
        private int maxToolCalls = 24;
        private String searchContextSize = "high";
        private String searchRegion = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
        }

        public String getFolderId() {
            return folderId;
        }

        public void setFolderId(String folderId) {
            this.folderId = folderId == null ? "" : folderId.trim();
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
                    : baseUrl.trim();
        }

        public String getResponsesBaseUrl() {
            return responsesBaseUrl;
        }

        public void setResponsesBaseUrl(String responsesBaseUrl) {
            this.responsesBaseUrl = responsesBaseUrl == null || responsesBaseUrl.isBlank()
                    ? "https://ai.api.cloud.yandex.net/v1"
                    : responsesBaseUrl.trim().replaceAll("/+$", "");
        }

        public String getApiMode() {
            return apiMode;
        }

        public void setApiMode(String apiMode) {
            this.apiMode = apiMode == null || apiMode.isBlank() ? "completion" : apiMode.trim();
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model == null || model.isBlank() ? "yandexgpt" : model.trim();
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout == null ? Duration.ofMinutes(6) : timeout;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = Math.max(1000, Math.min(32000, maxTokens));
        }

        public int getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(int maxToolCalls) {
            this.maxToolCalls = Math.max(1, Math.min(64, maxToolCalls));
        }

        public String getSearchContextSize() {
            return searchContextSize;
        }

        public void setSearchContextSize(String searchContextSize) {
            this.searchContextSize = searchContextSize == null || searchContextSize.isBlank()
                    ? "high"
                    : searchContextSize.trim();
        }

        public String getSearchRegion() {
            return searchRegion;
        }

        public void setSearchRegion(String searchRegion) {
            this.searchRegion = searchRegion == null ? "" : searchRegion.trim();
        }
    }

    public static class DeepSeek {
        private String apiKey = "";
        private String baseUrl = "https://api.deepseek.com";
        private String anthropicBaseUrl = "https://api.deepseek.com/anthropic";
        private String model = "deepseek-v4-pro";
        private Duration timeout = Duration.ofMinutes(10);
        private int maxTokens = 24000;
        private boolean thinkingEnabled = true;
        private String reasoningEffort = "high";
        private boolean anthropicWebSearchEnabled = true;
        private int anthropicWebSearchMaxUses = 6;
        private boolean anthropicDeepSearchEnabled = true;
        private int anthropicDeepSearchPasses = 4;
        private boolean anthropicFallbackEnabled = true;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? "https://api.deepseek.com"
                    : baseUrl.trim().replaceAll("/+$", "");
        }

        public String getAnthropicBaseUrl() {
            return anthropicBaseUrl;
        }

        public void setAnthropicBaseUrl(String anthropicBaseUrl) {
            this.anthropicBaseUrl = anthropicBaseUrl == null || anthropicBaseUrl.isBlank()
                    ? "https://api.deepseek.com/anthropic"
                    : anthropicBaseUrl.trim().replaceAll("/+$", "");
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model == null || model.isBlank() ? "deepseek-v4-pro" : model.trim();
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = Math.max(1000, Math.min(384000, maxTokens));
        }

        public boolean isThinkingEnabled() {
            return thinkingEnabled;
        }

        public void setThinkingEnabled(boolean thinkingEnabled) {
            this.thinkingEnabled = thinkingEnabled;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            String normalized = reasoningEffort == null ? "" : reasoningEffort.trim().toLowerCase();
            this.reasoningEffort = "max".equals(normalized) ? "max" : "high";
        }

        public boolean isAnthropicWebSearchEnabled() {
            return anthropicWebSearchEnabled;
        }

        public void setAnthropicWebSearchEnabled(boolean anthropicWebSearchEnabled) {
            this.anthropicWebSearchEnabled = anthropicWebSearchEnabled;
        }

        public int getAnthropicWebSearchMaxUses() {
            return anthropicWebSearchMaxUses;
        }

        public void setAnthropicWebSearchMaxUses(int anthropicWebSearchMaxUses) {
            this.anthropicWebSearchMaxUses = Math.max(1, Math.min(20, anthropicWebSearchMaxUses));
        }

        public boolean isAnthropicDeepSearchEnabled() {
            return anthropicDeepSearchEnabled;
        }

        public void setAnthropicDeepSearchEnabled(boolean anthropicDeepSearchEnabled) {
            this.anthropicDeepSearchEnabled = anthropicDeepSearchEnabled;
        }

        public int getAnthropicDeepSearchPasses() {
            return anthropicDeepSearchPasses;
        }

        public void setAnthropicDeepSearchPasses(int anthropicDeepSearchPasses) {
            this.anthropicDeepSearchPasses = Math.max(1, Math.min(4, anthropicDeepSearchPasses));
        }

        public boolean isAnthropicFallbackEnabled() {
            return anthropicFallbackEnabled;
        }

        public void setAnthropicFallbackEnabled(boolean anthropicFallbackEnabled) {
            this.anthropicFallbackEnabled = anthropicFallbackEnabled;
        }
    }

    public static class Search {
        private String provider = "yandex";
        private int maxQueries = 10;
        private int resultsPerQuery = 5;
        private int crawlResultLimit = 20;
        private YandexSearch yandex = new YandexSearch();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider == null || provider.isBlank() ? "yandex" : provider.trim();
        }

        public int getMaxQueries() {
            return maxQueries;
        }

        public void setMaxQueries(int maxQueries) {
            this.maxQueries = Math.max(0, Math.min(12, maxQueries));
        }

        public int getResultsPerQuery() {
            return resultsPerQuery;
        }

        public void setResultsPerQuery(int resultsPerQuery) {
            this.resultsPerQuery = Math.max(1, Math.min(20, resultsPerQuery));
        }

        public int getCrawlResultLimit() {
            return crawlResultLimit;
        }

        public void setCrawlResultLimit(int crawlResultLimit) {
            this.crawlResultLimit = Math.max(0, Math.min(20, crawlResultLimit));
        }

        public YandexSearch getYandex() {
            return yandex;
        }

        public void setYandex(YandexSearch yandex) {
            this.yandex = yandex == null ? new YandexSearch() : yandex;
        }
    }

    public static class YandexSearch {
        private String apiKey = "";
        private String folderId = "";
        private String baseUrl = "https://searchapi.api.cloud.yandex.net";
        private Duration timeout = Duration.ofSeconds(12);
        private String searchType = "SEARCH_TYPE_RU";
        private String familyMode = "FAMILY_MODE_MODERATE";
        private String region = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
        }

        public String getFolderId() {
            return folderId;
        }

        public void setFolderId(String folderId) {
            this.folderId = folderId == null ? "" : folderId.trim();
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? "https://searchapi.api.cloud.yandex.net"
                    : baseUrl.trim();
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout == null ? Duration.ofSeconds(12) : timeout;
        }

        public String getSearchType() {
            return searchType;
        }

        public void setSearchType(String searchType) {
            this.searchType = searchType == null || searchType.isBlank() ? "SEARCH_TYPE_RU" : searchType.trim();
        }

        public String getFamilyMode() {
            return familyMode;
        }

        public void setFamilyMode(String familyMode) {
            this.familyMode = familyMode == null || familyMode.isBlank() ? "FAMILY_MODE_MODERATE" : familyMode.trim();
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region == null ? "" : region.trim();
        }
    }

    public static class OpenAi {
        private String apiKey = "";
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-4.1-mini";
        private Duration timeout = Duration.ofSeconds(60);
        private int maxOutputTokens = 6000;
        private Proxy proxy = new Proxy();
        private DeepResearch deepResearch = new DeepResearch();
        private ResearchReport researchReport = new ResearchReport();

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? "https://api.openai.com/v1"
                    : baseUrl.trim().replaceAll("/+$", "");
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model == null || model.isBlank() ? "gpt-4.1-mini" : model.trim();
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = Math.max(1000, Math.min(100000, maxOutputTokens));
        }

        public Proxy getProxy() {
            return proxy;
        }

        public void setProxy(Proxy proxy) {
            this.proxy = proxy == null ? new Proxy() : proxy;
        }

        public DeepResearch getDeepResearch() {
            return deepResearch;
        }

        public void setDeepResearch(DeepResearch deepResearch) {
            this.deepResearch = deepResearch == null ? new DeepResearch() : deepResearch;
        }

        public ResearchReport getResearchReport() {
            return researchReport;
        }

        public void setResearchReport(ResearchReport researchReport) {
            this.researchReport = researchReport == null ? new ResearchReport() : researchReport;
        }

        public static class Proxy {
            private boolean enabled = false;
            private String host = "";
            private int port = 8888;
            private String username = "";
            private String password = "";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host == null ? "" : host.trim();
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = Math.max(1, Math.min(65535, port));
            }

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username == null ? "" : username.trim();
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password == null ? "" : password;
            }
        }

        public static class DeepResearch {
            private String model = "o4-mini-deep-research";
            private Duration timeout = Duration.ofMinutes(8);
            private int maxToolCalls = 40;
            private int maxOutputTokens = 24000;
            private boolean background = false;

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model == null || model.isBlank() ? "o4-mini-deep-research" : model.trim();
            }

            public Duration getTimeout() {
                return timeout;
            }

            public void setTimeout(Duration timeout) {
                this.timeout = timeout == null ? Duration.ofMinutes(8) : timeout;
            }

            public int getMaxToolCalls() {
                return maxToolCalls;
            }

            public void setMaxToolCalls(int maxToolCalls) {
                this.maxToolCalls = Math.max(1, Math.min(200, maxToolCalls));
            }

            public int getMaxOutputTokens() {
                return maxOutputTokens;
            }

            public void setMaxOutputTokens(int maxOutputTokens) {
                this.maxOutputTokens = Math.max(2000, Math.min(100000, maxOutputTokens));
            }

            public boolean isBackground() {
                return background;
            }

            public void setBackground(boolean background) {
                this.background = background;
            }
        }

        public static class ResearchReport {
            private String model = "gpt-5.5";
            private Duration timeout = Duration.ofMinutes(6);
            private int maxToolCalls = 32;
            private int maxOutputTokens = 12000;
            private String reasoningEffort = "low";
            private String searchContextSize = "medium";

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model == null || model.isBlank() ? "gpt-5.5" : model.trim();
            }

            public Duration getTimeout() {
                return timeout;
            }

            public void setTimeout(Duration timeout) {
                this.timeout = timeout == null ? Duration.ofMinutes(6) : timeout;
            }

            public int getMaxToolCalls() {
                return maxToolCalls;
            }

            public void setMaxToolCalls(int maxToolCalls) {
                this.maxToolCalls = Math.max(1, Math.min(200, maxToolCalls));
            }

            public int getMaxOutputTokens() {
                return maxOutputTokens;
            }

            public void setMaxOutputTokens(int maxOutputTokens) {
                this.maxOutputTokens = Math.max(2000, Math.min(100000, maxOutputTokens));
            }

            public String getReasoningEffort() {
                return reasoningEffort;
            }

            public void setReasoningEffort(String reasoningEffort) {
                this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
            }

            public String getSearchContextSize() {
                return searchContextSize;
            }

            public void setSearchContextSize(String searchContextSize) {
                this.searchContextSize = searchContextSize == null || searchContextSize.isBlank()
                        ? "medium"
                        : searchContextSize.trim();
            }
        }
    }
}
