package com.hunt.otziv.reputationai.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public record DeepCompanyResearchReport(
        Long companyId,
        String companyName,
        String city,
        String provider,
        String model,
        String responseId,
        String reportMarkdown,
        List<Section> sections,
        List<Source> sources,
        List<String> warnings,
        List<QualityCheck> qualityChecks,
        FactSnapshot factSnapshot,
        List<String> reviewIdeas,
        LocalDateTime createdAt
) {
    public DeepCompanyResearchReport(
            Long companyId,
            String companyName,
            String city,
            String provider,
            String model,
            String responseId,
            String reportMarkdown,
            List<Section> sections,
            List<Source> sources,
            List<String> warnings,
            List<QualityCheck> qualityChecks,
            FactSnapshot factSnapshot,
            LocalDateTime createdAt
    ) {
        this(
                companyId,
                companyName,
                city,
                provider,
                model,
                responseId,
                reportMarkdown,
                sections,
                sources,
                warnings,
                qualityChecks,
                factSnapshot,
                List.of(),
                createdAt
        );
    }

    public DeepCompanyResearchReport {
        companyName = normalize(companyName);
        city = normalize(city);
        provider = normalize(provider);
        model = normalize(model);
        responseId = normalize(responseId);
        reportMarkdown = normalize(reportMarkdown);
        sections = sections == null ? List.of() : List.copyOf(sections);
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : warnings.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        qualityChecks = qualityChecks == null ? List.of() : qualityChecks.stream()
                .filter(value -> value != null)
                .toList();
        factSnapshot = factSnapshot == null ? FactSnapshot.empty() : factSnapshot;
        reviewIdeas = normalizeReviewIdeas(reviewIdeas);
        if (reviewIdeas.isEmpty()) {
            reviewIdeas = reviewIdeasFromSections(sections);
        }
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public record Section(String title, String body) {
        public Section {
            title = normalize(title);
            body = normalize(body);
        }
    }

    public record Source(
            String title,
            String url,
            String note,
            String type,
            List<String> usedFor,
            String confidence
    ) {
        public Source(String title, String url, String note) {
            this(title, url, note, "", List.of(), "");
        }

        public Source {
            title = normalize(title);
            url = normalize(url);
            note = normalize(note);
            type = normalize(type);
            if (type.isBlank()) {
                type = "other";
            }
            usedFor = usedFor == null ? List.of() : usedFor.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            confidence = normalize(confidence);
            if (confidence.isBlank()) {
                confidence = "medium";
            }
        }
    }

    public record QualityCheck(String key, String label, String status, String detail) {
        public QualityCheck {
            key = normalize(key);
            label = normalize(label);
            status = normalize(status);
            detail = normalize(detail);
        }
    }

    public record FactSnapshot(
            List<FactItem> confirmedFacts,
            List<FactItem> uncertainFacts,
            List<SourceReview> sourceReviews
    ) {
        public FactSnapshot {
            confirmedFacts = confirmedFacts == null ? List.of() : confirmedFacts.stream()
                    .filter(value -> value != null)
                    .toList();
            uncertainFacts = uncertainFacts == null ? List.of() : uncertainFacts.stream()
                    .filter(value -> value != null)
                    .toList();
            sourceReviews = sourceReviews == null ? List.of() : sourceReviews.stream()
                    .filter(value -> value != null)
                    .toList();
        }

        public static FactSnapshot empty() {
            return new FactSnapshot(List.of(), List.of(), List.of());
        }
    }

    public record FactItem(String label, String value, String evidence, String confidence) {
        public FactItem {
            label = normalize(label);
            value = normalize(value);
            evidence = normalize(evidence);
            confidence = normalize(confidence);
        }
    }

    public record SourceReview(String title, String url, String status, String reason) {
        public SourceReview {
            title = normalize(title);
            url = normalize(url);
            status = normalize(status);
            reason = normalize(reason);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> normalizeReviewIdeas(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String clean = cleanReviewIdea(value);
            if (!clean.isBlank()) {
                result.add(clean);
            }
            if (result.size() >= 30) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static List<String> reviewIdeasFromSections(List<Section> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        List<String> ideas = new ArrayList<>();
        for (Section section : sections) {
            if (!isReviewIdeaHeading(section.title())) {
                continue;
            }
            for (String line : section.body().split("\\R")) {
                String cleanLine = line == null ? "" : line.trim();
                if (!cleanLine.matches("^(?:\\d+[.)]|[-*])\\s+.*")) {
                    continue;
                }
                String idea = cleanReviewIdea(cleanLine.replaceFirst("^(?:\\d+[.)]|[-*])\\s+", ""));
                if (!idea.isBlank() && !ideas.contains(idea)) {
                    ideas.add(idea);
                }
                if (ideas.size() >= 30) {
                    return List.copyOf(ideas);
                }
            }
        }
        return List.copyOf(ideas);
    }

    private static boolean isReviewIdeaHeading(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return clean.contains("иде")
                && clean.contains("отзыв")
                && !clean.matches(".*(пост|faq|карточ|контент|коммент|дозбор|спросить|уточн).*");
    }

    private static String cleanReviewIdea(String value) {
        return normalize(value)
                .replaceAll("^#{1,6}\\s*", "")
                .replaceAll("^\\*\\*(.+)\\*\\*$", "$1")
                .replaceAll("(?iu)^отзыв\\s+о\\s+", "")
                .replaceAll("(?iu)^отзыв\\s+про\\s+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
