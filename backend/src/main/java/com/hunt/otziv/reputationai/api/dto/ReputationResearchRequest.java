package com.hunt.otziv.reputationai.api.dto;

import java.util.List;

public record ReputationResearchRequest(
        String websiteOverride,
        String manualDescription,
        List<String> productsOrServices,
        List<String> publicUrls,
        Boolean includeCompanyWebsite,
        String deepResearchProfile,
        String deepResearchMode,
        Long baseReportJobId,
        String sectionTitle,
        Integer sectionIndex,
        Boolean enrichCollectionGaps
) {
    public boolean shouldIncludeCompanyWebsite() {
        return includeCompanyWebsite == null || includeCompanyWebsite;
    }

    public boolean shouldEnrichCollectionGaps() {
        if (enrichCollectionGaps != null) {
            return enrichCollectionGaps;
        }
        String profile = deepResearchProfile == null ? "" : deepResearchProfile.trim().toLowerCase();
        return "quality".equals(profile) || "maximum".equals(profile);
    }
}
