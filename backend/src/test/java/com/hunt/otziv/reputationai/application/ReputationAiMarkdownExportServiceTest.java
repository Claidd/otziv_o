package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.reputationai.domain.CompanyAiProfile;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchJobStatus;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationContentPackJobStatus;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReputationAiMarkdownExportServiceTest {

    @Mock
    private DeepCompanyResearchJobService deepCompanyResearchJobService;

    @Mock
    private ReputationContentPackJobService contentPackJobService;

    private ReputationAiMarkdownExportService service;

    @BeforeEach
    void setUp() {
        service = new ReputationAiMarkdownExportService(deepCompanyResearchJobService, contentPackJobService);
    }

    @Test
    void exportsLatestDeepReportAsMarkdown() {
        DeepCompanyResearchReport report = new DeepCompanyResearchReport(
                7L,
                "Ромашка",
                "Иркутск",
                "openai",
                "gpt-5.5",
                "resp_1",
                "## Сводка\n\nСильные стороны подтверждены источниками.",
                List.of(new DeepCompanyResearchReport.Section("Сводка", "Сильные стороны подтверждены источниками.")),
                List.of(new DeepCompanyResearchReport.Source("Сайт", "https://example.ru", "Официальный сайт")),
                List.of("Проверьте актуальность цен."),
                List.of(new DeepCompanyResearchReport.QualityCheck("sources", "Источники", "pass", "Есть публичная ссылка")),
                new DeepCompanyResearchReport.FactSnapshot(
                        List.of(new DeepCompanyResearchReport.FactItem("Компания", "Ромашка", "CRM", "high")),
                        List.of(),
                        List.of()
                ),
                LocalDateTime.of(2026, 5, 13, 10, 30)
        );
        DeepCompanyResearchJobStatus job = new DeepCompanyResearchJobStatus(
                18L,
                7L,
                "Ромашка",
                "DONE",
                "openai",
                "gpt-5.5",
                "resp_1",
                "",
                report,
                "full_report",
                LocalDateTime.of(2026, 5, 13, 10, 0),
                LocalDateTime.of(2026, 5, 13, 10, 35),
                LocalDateTime.of(2026, 5, 13, 10, 1),
                LocalDateTime.of(2026, 5, 13, 10, 35)
        );
        when(deepCompanyResearchJobService.findLatestReady(7L)).thenReturn(java.util.Optional.of(job));

        ReputationAiMarkdownExportService.MarkdownExport export = service.latestDeepReport(7L).orElseThrow();

        assertThat(export.fileName()).isEqualTo("ромашка-deep-report.md");
        assertThat(export.markdown())
                .contains("# Глубокий AI-отчет: Ромашка")
                .contains("- **ID компании:** 7")
                .contains("## Сводка")
                .contains("Сильные стороны подтверждены источниками.")
                .contains("## Проверки качества")
                .contains("Источники: pass")
                .contains("## Источники отчета")
                .contains("https://example.ru");
    }

    @Test
    void exportsLatestContentPackAsMarkdown() {
        ResearchSnapshot snapshot = new ResearchSnapshot(
                7L,
                "Ромашка",
                "Иркутск",
                "https://example.ru",
                "Строительные материалы",
                "",
                "",
                List.of("штукатурка"),
                List.of("доставка"),
                List.of(),
                List.of(),
                List.of(),
                List.of(new CompanySource("website", "Сайт", "https://example.ru", "Факты")),
                "local",
                false,
                List.of(),
                0,
                1,
                List.of(),
                LocalDateTime.of(2026, 5, 13, 9, 0)
        );
        CompanyAiProfile profile = new CompanyAiProfile(
                "Магазин материалов с доставкой.",
                "Строительные материалы",
                List.of("штукатурка"),
                List.of("доставка"),
                List.of(),
                List.of(),
                List.of()
        );
        ReputationContentPack pack = new ReputationContentPack(
                snapshot,
                profile,
                List.of("Доставка и консультация в одном месте."),
                List.of("Закажите материалы без лишних звонков."),
                List.of("Как выбрать штукатурку"),
                List.of("Пост о выборе\nс практическим чек-листом"),
                List.of("Покупатель оценил помощь менеджера"),
                List.of("Менеджер помог подобрать штукатурку."),
                List.of("Спасибо за отзыв!"),
                List.of("Спасибо за сигнал, проверим ситуацию."),
                List.of("https://example.ru"),
                List.of("Не обещайте неподтвержденные скидки.")
        );
        ReputationContentPackJobStatus job = new ReputationContentPackJobStatus(
                22L,
                7L,
                "Ромашка",
                "DONE",
                "openai",
                "gpt-5.5",
                "",
                pack,
                LocalDateTime.of(2026, 5, 13, 11, 0),
                LocalDateTime.of(2026, 5, 13, 11, 15),
                LocalDateTime.of(2026, 5, 13, 11, 1),
                LocalDateTime.of(2026, 5, 13, 11, 15)
        );
        when(contentPackJobService.findLatest(7L)).thenReturn(java.util.Optional.of(job));

        ReputationAiMarkdownExportService.MarkdownExport export = service.latestContentPack(7L).orElseThrow();

        assertThat(export.fileName()).isEqualTo("ромашка-content-pack.md");
        assertThat(export.markdown())
                .contains("# AI-пакет компании: Ромашка")
                .contains("## Профиль компании")
                .contains("Магазин материалов с доставкой.")
                .contains("## УТП")
                .contains("1. Доставка и консультация в одном месте.")
                .contains("## Посты и статьи")
                .contains("Пост о выборе\n   с практическим чек-листом")
                .contains("## Источники")
                .contains("https://example.ru");
    }
}
