package com.hunt.otziv.reputationai.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReputationResearchRequest(
        @Size(max = 2048) String websiteOverride,
        @Size(max = 20_000) String manualDescription,
        @Size(max = 30) List<@Size(max = 300) String> productsOrServices,
        @Size(max = 40) List<@Size(max = 2048) String> publicUrls,
        Boolean includeCompanyWebsite,
        @Size(max = 40) String deepResearchProfile,
        @Size(max = 40) String deepResearchMode,
        @Positive Long baseReportJobId,
        @Size(max = 300) String sectionTitle,
        @Min(0) @Max(200) Integer sectionIndex,
        Boolean enrichCollectionGaps
) {
    public boolean shouldIncludeCompanyWebsite() {
        return includeCompanyWebsite == null || includeCompanyWebsite;
    }

    public boolean shouldEnrichCollectionGaps() {
        if (enrichCollectionGaps != null) {
            return enrichCollectionGaps;
        }
        return true;
    }
}
