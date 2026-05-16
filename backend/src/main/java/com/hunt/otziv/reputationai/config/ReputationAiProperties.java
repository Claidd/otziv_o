package com.hunt.otziv.reputationai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "reputation-ai")
public class ReputationAiProperties {

    private String provider = "local";
    private int maxWebsitePages = 36;
    private int maxDeepWebsitePages = 12;
    private int maxWebsiteChars = 20_000;
    private Duration websiteTimeout = Duration.ofSeconds(8);
    private String userAgent = "OtzivReputationAI/1.0";
    private Search search = new Search();
    private YandexGpt yandex = new YandexGpt();
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
        this.maxWebsitePages = Math.max(1, maxWebsitePages);
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
        this.maxWebsiteChars = Math.max(1000, maxWebsiteChars);
    }

    public Duration getWebsiteTimeout() {
        return websiteTimeout;
    }

    public void setWebsiteTimeout(Duration websiteTimeout) {
        this.websiteTimeout = websiteTimeout == null ? Duration.ofSeconds(8) : websiteTimeout;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
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
        private String model = "yandexgpt";
        private Duration timeout = Duration.ofSeconds(45);
        private int maxTokens = 4000;

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
            this.timeout = timeout == null ? Duration.ofSeconds(45) : timeout;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = Math.max(1000, Math.min(12000, maxTokens));
        }
    }

    public static class Search {
        private String provider = "local";
        private int maxQueries = 6;
        private int resultsPerQuery = 5;
        private int crawlResultLimit = 20;
        private YandexSearch yandex = new YandexSearch();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider == null || provider.isBlank() ? "local" : provider.trim();
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
