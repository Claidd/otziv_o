package com.hunt.otziv.reputationai.domain;

import java.time.LocalDateTime;
import java.util.List;

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
        LocalDateTime createdAt
) {
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
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public record Section(String title, String body) {
        public Section {
            title = normalize(title);
            body = normalize(body);
        }
    }

    public record Source(String title, String url, String note) {
        public Source {
            title = normalize(title);
            url = normalize(url);
            note = normalize(note);
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
}
