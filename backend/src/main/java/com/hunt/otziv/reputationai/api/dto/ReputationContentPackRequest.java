package com.hunt.otziv.reputationai.api.dto;

import java.util.List;

public record ReputationContentPackRequest(
        String productOrService,
        String manualDescription,
        List<String> productsOrServices,
        List<String> publicUrls,
        Boolean includeCompanyWebsite,
        Integer adTextsCount,
        Integer socialPostsCount,
        Integer positiveReplyCount,
        Integer negativeReplyCount,
        String contentPackProfile
) {
    public ReputationResearchRequest toResearchRequest() {
        return new ReputationResearchRequest(
                null,
                manualDescription,
                productsOrServices,
                publicUrls,
                includeCompanyWebsite,
                null
        );
    }
}
