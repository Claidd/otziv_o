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

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
