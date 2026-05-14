package com.hunt.otziv.reputationai.domain;

import java.util.List;

public record ReputationContentPack(
        ResearchSnapshot researchSnapshot,
        CompanyAiProfile companyProfile,
        List<String> utp,
        List<String> adTexts,
        List<String> socialPostTopics,
        List<String> socialPosts,
        List<String> honestReviewTopics,
        List<String> reviewDraftTemplates,
        List<String> positiveReviewReplies,
        List<String> negativeReviewReplies,
        List<String> sourceUrls,
        List<String> safetyNotes
) {
    public ReputationContentPack {
        utp = safeList(utp);
        adTexts = safeList(adTexts);
        socialPostTopics = safeList(socialPostTopics);
        socialPosts = safeList(socialPosts);
        honestReviewTopics = safeReviewList(honestReviewTopics);
        reviewDraftTemplates = safeReviewList(reviewDraftTemplates);
        positiveReviewReplies = safeList(positiveReviewReplies);
        negativeReviewReplies = safeList(negativeReviewReplies);
        sourceUrls = safeList(sourceUrls);
        safetyNotes = safeList(safetyNotes);
    }

    private static List<String> safeList(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static List<String> safeReviewList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(ReputationContentPack::cleanReviewText)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String cleanReviewText(String value) {
        return value
                .replace('[', ' ')
                .replace(']', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
