package com.hunt.otziv.reputationai.application;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReputationAiPdfExportServiceTest {

    @Test
    void rendersCyrillicMarkdownToPdf() throws Exception {
        ReputationAiMarkdownExportService markdownExportService = mock(ReputationAiMarkdownExportService.class);
        when(markdownExportService.latestDeepReport(7L)).thenReturn(Optional.of(
                new ReputationAiMarkdownExportService.MarkdownExport(
                        "ромашка-deep-report.md",
                        """
                                # Глубокий AI-отчет: Ромашка

                                - **ID компании:** 7
                                - **Город:** Иркутск
                                - **Создан:** 13.05.2026 12:30
                                - **Готов:** 13.05.2026 12:45

                                ## Сводка

                                Компания помогает подобрать материалы, доставляет заказы и показывает цены «под ключ»: №1 — 3000 ₽.

                                ## Источники отчета

                                - Сайт: https://example.ru
                                """
                )
        ));
        ReputationAiPdfExportService service = new ReputationAiPdfExportService(markdownExportService);

        ReputationAiPdfExportService.PdfExport export = service.latestDeepReport(7L).orElseThrow();

        assertThat(export.fileName()).isEqualTo("ромашка-deep-report.pdf");
        assertThat(export.bytes()).startsWith("%PDF".getBytes());
        assertThat(export.bytes().length).isGreaterThan(1000);
        try (PDDocument document = Loader.loadPDF(export.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("Глубокий AI-отчет: Ромашка");
            assertThat(document.getDocumentInformation().getAuthor()).isEqualTo("Компания О!");
            assertThat(document.getDocumentInformation().getSubject()).contains("Глубокий AI-отчет").contains("Ромашка");
            assertThat(document.getDocumentInformation().getCreator()).isEqualTo("AI-помощник репутации");
            assertThat(document.getDocumentInformation().getCreationDate()).isNotNull();
            assertThat(text)
                    .contains("Компания О!")
                    .contains("Глубокий AI-отчет")
                    .contains("Ромашка")
                    .contains("Дата отчета: 13.05.2026 12:45")
                    .contains("Документ сформирован из сохраненного результата AI-раздела.")
                    .contains("Иркутск")
                    .contains("Компания помогает подобрать материалы")
                    .contains("цены \"под ключ\": No.1 - 3000 руб.")
                    .contains("https://example.ru")
                    .contains("Сформировано AI-помощником репутации")
                    .contains("Страница 1 из 1");
        }
    }

    @Test
    void addsClientFooterWithPageNumbersToEveryPage() throws Exception {
        ReputationAiMarkdownExportService markdownExportService = mock(ReputationAiMarkdownExportService.class);
        String repeatedSection = """
                ## Раздел с подробностями

                Компания ведет клиентский сервис, собирает обратную связь и регулярно обновляет карточки на площадках.
                """
                .repeat(80);
        when(markdownExportService.latestContentPack(9L)).thenReturn(Optional.of(
                new ReputationAiMarkdownExportService.MarkdownExport(
                        "ромашка-content-pack.md",
                        """
                                # AI-пакет компании: Ромашка

                                - **ID компании:** 9
                                - **Создан:** 13.05.2026 11:00
                                - **Готов:** 13.05.2026 11:10

                                %s
                                """.formatted(repeatedSection)
                )
        ));
        ReputationAiPdfExportService service = new ReputationAiPdfExportService(markdownExportService);

        ReputationAiPdfExportService.PdfExport export = service.latestContentPack(9L).orElseThrow();

        try (PDDocument document = Loader.loadPDF(export.bytes())) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("AI-пакет компании: Ромашка");
            assertThat(text)
                    .contains("AI-пакет репутации")
                    .contains("Дата отчета: 13.05.2026 11:10")
                    .contains("Страница 1 из " + document.getNumberOfPages())
                    .contains("Страница " + document.getNumberOfPages() + " из " + document.getNumberOfPages());
        }
    }
}
