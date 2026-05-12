package com.hunt.otziv.reputationai.application;

public enum PageRole {
    OFFICIAL_SITE("website"),
    COMPANY_PROFILE("company_profile"),
    REVIEW_PAGE("review_page"),
    CATALOG_LISTING("catalog_listing"),
    COMPETITOR_LISTING("competitor_listing"),
    SERVICE_OR_LEGAL("service_or_legal"),
    IRRELEVANT("irrelevant"),
    UNKNOWN_PUBLIC("unknown_public");

    private final String sourceType;

    PageRole(String sourceType) {
        this.sourceType = sourceType;
    }

    public String sourceType() {
        return sourceType;
    }

    public boolean canUseAsSource() {
        return this != SERVICE_OR_LEGAL && this != IRRELEVANT;
    }

    public boolean canCrawlFromSearch() {
        return this == OFFICIAL_SITE
                || this == COMPANY_PROFILE
                || this == REVIEW_PAGE
                || this == UNKNOWN_PUBLIC;
    }
}
