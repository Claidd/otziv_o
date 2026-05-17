package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.reputationai.domain.CompanyAiProfile;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchJobStatus;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationContentPackJobStatus;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReputationAiMarkdownExportService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final DeepCompanyResearchJobService deepCompanyResearchJobService;
    private final ReputationContentPackJobService contentPackJobService;

    public Optional<MarkdownExport> latestDeepReport(Long companyId) {
        return deepCompanyResearchJobService.findLatestReady(companyId)
                .map(this::deepReportExport);
    }

    public Optional<MarkdownExport> deepReport(Long companyId, Long jobId) {
        return deepCompanyResearchJobService.findByIdForCompany(companyId, jobId)
                .filter(job -> job.report() != null)
                .map(this::deepReportExport);
    }

    public Optional<MarkdownExport> latestContentPack(Long companyId) {
        return contentPackJobService.findLatest(companyId)
                .filter(job -> job.pack() != null)
                .map(this::contentPackExport);
    }

    private MarkdownExport deepReportExport(DeepCompanyResearchJobStatus job) {
        DeepCompanyResearchReport report = job.report();
        String companyName = firstText(report.companyName(), job.companyName(), "Компания " + job.companyId());
        StringBuilder markdown = new StringBuilder();

        heading(markdown, 1, "Глубокий AI-отчет: " + companyName);
        metadata(markdown, "ID компании", report.companyId());
        metadata(markdown, "Город", report.city());
        metadata(markdown, "Задача", job.jobId() == null ? "" : "#" + job.jobId());
        metadata(markdown, "Тип запуска", job.operation());
        metadata(markdown, "Провайдер", providerLabel(job.provider(), report.model()));
        metadata(markdown, "Создан", formatDate(report.createdAt()));
        metadata(markdown, "Готов", formatDate(job.completedAt()));
        markdown.append('\n');

        String reportMarkdown = clean(report.reportMarkdown());
        if (!reportMarkdown.isBlank()) {
            markdown.append(reportMarkdown).append("\n\n");
        } else {
            appendSections(markdown, report.sections());
        }

        appendQualityChecks(markdown, report.qualityChecks());
        appendWarnings(markdown, report.warnings());
        appendFactSnapshot(markdown, report.factSnapshot());
        appendReportSources(markdown, report.sources());

        return new MarkdownExport(slug(companyName) + "-deep-report.md", markdown.toString().trim() + "\n");
    }

    private MarkdownExport contentPackExport(ReputationContentPackJobStatus job) {
        ReputationContentPack pack = job.pack();
        ResearchSnapshot snapshot = pack.researchSnapshot();
        CompanyAiProfile profile = pack.companyProfile() == null
                ? new CompanyAiProfile("", "", List.of(), List.of(), List.of(), List.of(), List.of())
                : pack.companyProfile();
        String companyName = firstText(
                snapshot == null ? "" : snapshot.companyName(),
                job.companyName(),
                "Компания " + job.companyId()
        );
        StringBuilder markdown = new StringBuilder();

        heading(markdown, 1, "AI-пакет компании: " + companyName);
        metadata(markdown, "ID компании", job.companyId());
        metadata(markdown, "Город", snapshot == null ? "" : snapshot.city());
        metadata(markdown, "Категория", firstText(profile.category(), snapshot == null ? "" : snapshot.category(), ""));
        metadata(markdown, "Задача", job.jobId() == null ? "" : "#" + job.jobId());
        metadata(markdown, "Провайдер", providerLabel(job.provider(), job.model()));
        metadata(markdown, "Создан", formatDate(job.createdAt()));
        metadata(markdown, "Готов", formatDate(job.completedAt()));
        markdown.append('\n');

        if (!clean(profile.shortDescription()).isBlank()) {
            heading(markdown, 2, "Профиль компании");
            markdown.append(clean(profile.shortDescription())).append("\n\n");
        }

        appendListSection(markdown, "Продукты и услуги", profile.products(), false);
        appendListSection(markdown, "Преимущества", profile.advantages(), false);
        appendListSection(markdown, "УТП", pack.utp(), true);
        appendListSection(markdown, "Рекламные тексты", pack.adTexts(), true);
        appendListSection(markdown, "Темы постов", pack.socialPostTopics(), true);
        appendListSection(markdown, "Посты и статьи", pack.socialPosts(), true);
        appendListSection(markdown, "Темы для честного отзыва", pack.honestReviewTopics(), true);
        appendListSection(markdown, "Черновики отзывов с УТП", pack.reviewDraftTemplates(), true);
        appendListSection(markdown, "Ответы на положительные отзывы", pack.positiveReviewReplies(), true);
        appendListSection(markdown, "Ответы на негативные отзывы", pack.negativeReviewReplies(), true);
        appendListSection(markdown, "Проверки и ограничения", pack.safetyNotes(), false);
        appendListSection(markdown, "Источники", pack.sourceUrls(), false);

        return new MarkdownExport(slug(companyName) + "-content-pack.md", markdown.toString().trim() + "\n");
    }

    private void appendSections(StringBuilder markdown, List<DeepCompanyResearchReport.Section> sections) {
        for (DeepCompanyResearchReport.Section section : sections) {
            String title = firstText(section.title(), "Раздел отчета");
            heading(markdown, 2, title);
            markdown.append(clean(section.body())).append("\n\n");
        }
    }

    private void appendQualityChecks(StringBuilder markdown, List<DeepCompanyResearchReport.QualityCheck> checks) {
        if (checks == null || checks.isEmpty()) {
            return;
        }

        heading(markdown, 2, "Проверки качества");
        for (DeepCompanyResearchReport.QualityCheck check : checks) {
            markdown.append("- ")
                    .append(firstText(check.label(), check.key(), "Проверка"))
                    .append(": ")
                    .append(firstText(check.status(), "info"));
            if (!clean(check.detail()).isBlank()) {
                markdown.append(". ").append(clean(check.detail()));
            }
            markdown.append('\n');
        }
        markdown.append('\n');
    }

    private void appendWarnings(StringBuilder markdown, List<String> warnings) {
        appendListSection(markdown, "Риски и сомнения", warnings, false);
    }

    private void appendFactSnapshot(StringBuilder markdown, DeepCompanyResearchReport.FactSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        appendFactItems(markdown, "Подтвержденные факты", snapshot.confirmedFacts());
        appendFactItems(markdown, "Факты для проверки", snapshot.uncertainFacts());

        if (!snapshot.sourceReviews().isEmpty()) {
            heading(markdown, 2, "Оценка источников");
            for (DeepCompanyResearchReport.SourceReview source : snapshot.sourceReviews()) {
                markdown.append("- ")
                        .append(firstText(source.title(), source.url(), "Источник"))
                        .append(": ")
                        .append(firstText(source.status(), "status_unknown"));
                if (!clean(source.reason()).isBlank()) {
                    markdown.append(". ").append(clean(source.reason()));
                }
                if (!clean(source.url()).isBlank()) {
                    markdown.append(" (").append(clean(source.url())).append(')');
                }
                markdown.append('\n');
            }
            markdown.append('\n');
        }
    }

    private void appendFactItems(StringBuilder markdown, String title, List<DeepCompanyResearchReport.FactItem> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }

        heading(markdown, 2, title);
        for (DeepCompanyResearchReport.FactItem fact : facts) {
            markdown.append("- ")
                    .append(firstText(fact.label(), "Факт"))
                    .append(": ")
                    .append(firstText(fact.value(), "не указано"));
            if (!clean(fact.evidence()).isBlank()) {
                markdown.append(". Доказательство: ").append(clean(fact.evidence()));
            }
            if (!clean(fact.confidence()).isBlank()) {
                markdown.append(". Уверенность: ").append(clean(fact.confidence()));
            }
            markdown.append('\n');
        }
        markdown.append('\n');
    }

    private void appendReportSources(StringBuilder markdown, List<DeepCompanyResearchReport.Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }

        heading(markdown, 2, "Источники отчета");
        for (DeepCompanyResearchReport.Source source : sources) {
            markdown.append("- ").append(firstText(source.title(), source.url(), "Источник"));
            if (!clean(source.url()).isBlank()) {
                markdown.append(": ").append(clean(source.url()));
            }
            List<String> meta = new java.util.ArrayList<>();
            if (!clean(source.type()).isBlank()) {
                meta.add("type=" + clean(source.type()));
            }
            if (!clean(source.confidence()).isBlank()) {
                meta.add("confidence=" + clean(source.confidence()));
            }
            if (source.usedFor() != null && !source.usedFor().isEmpty()) {
                meta.add("usedFor=" + clean(String.join(", ", source.usedFor())));
            }
            if (!meta.isEmpty()) {
                markdown.append(" (").append(String.join("; ", meta)).append(")");
            }
            if (!clean(source.note()).isBlank()) {
                markdown.append(". ").append(clean(source.note()));
            }
            markdown.append('\n');
        }
        markdown.append('\n');
    }

    private void appendListSection(StringBuilder markdown, String title, List<String> values, boolean numbered) {
        if (values == null || values.isEmpty()) {
            return;
        }

        heading(markdown, 2, title);
        for (int index = 0; index < values.size(); index++) {
            String prefix = numbered ? (index + 1) + ". " : "- ";
            markdown.append(prefix).append(indentMultiline(clean(values.get(index)), "   ")).append('\n');
        }
        markdown.append('\n');
    }

    private void heading(StringBuilder markdown, int level, String title) {
        markdown.append("#".repeat(Math.max(1, level))).append(' ').append(clean(title)).append("\n\n");
    }

    private void metadata(StringBuilder markdown, String label, Object value) {
        String text = value == null ? "" : clean(value.toString());
        if (text.isBlank()) {
            return;
        }

        markdown.append("- **").append(label).append(":** ").append(text).append('\n');
    }

    private String providerLabel(String provider, String model) {
        String cleanProvider = clean(provider);
        String cleanModel = clean(model);
        if (cleanProvider.isBlank()) {
            return cleanModel;
        }
        if (cleanModel.isBlank()) {
            return cleanProvider;
        }
        return cleanProvider + " / " + cleanModel;
    }

    private String formatDate(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME);
    }

    private String indentMultiline(String value, String indent) {
        return clean(value)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\n" + indent);
    }

    private String slug(String value) {
        String slug = clean(value)
                .toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "reputation-ai" : slug;
    }

    private String firstText(String... values) {
        for (String value : values) {
            String clean = clean(value);
            if (!clean.isBlank()) {
                return clean;
            }
        }
        return "";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record MarkdownExport(String fileName, String markdown) {
    }
}
