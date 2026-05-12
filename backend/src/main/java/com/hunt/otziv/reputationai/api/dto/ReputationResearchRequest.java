package com.hunt.otziv.reputationai.api.dto;

import java.util.List;

public record ReputationResearchRequest(
        String websiteOverride,
        String manualDescription,
        List<String> productsOrServices,
        List<String> publicUrls,
        Boolean includeCompanyWebsite,
        String deepResearchProfile
) {
    public boolean shouldIncludeCompanyWebsite() {
        return includeCompanyWebsite == null || includeCompanyWebsite;
    }
}
