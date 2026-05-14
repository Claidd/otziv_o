package com.hunt.otziv.reputationai.application;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReputationAiPdfExportService {
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\(([^)\\s]+)\\)");
    private static final Pattern PRIMARY_HEADING = Pattern.compile("^#\\s+(.+)$");
    private static final Pattern METADATA = Pattern.compile("^-\\s+\\*\\*([^*]+?):\\*\\*\\s*(.+)$");
    private static final float MARGIN = 52F;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_WIDTH = PAGE_WIDTH - (MARGIN * 2);
    private static final float BODY_FONT_SIZE = 10.5F;
    private static final float BODY_LEADING = 15F;
    private static final float FOOTER_FONT_SIZE = 8.3F;
    private static final Color DARK = new Color(54, 57, 73);
    private static final Color INFO = new Color(125, 141, 161);
    private static final Color PRIMARY = new Color(108, 155, 207);
    private static final Color LINE = new Color(220, 226, 235);

    private final ReputationAiMarkdownExportService markdownExportService;

    public Optional<PdfExport> latestDeepReport(Long companyId) {
        return markdownExportService.latestDeepReport(companyId).map(this::pdfExport);
    }

    public Optional<PdfExport> deepReport(Long companyId, Long jobId) {
        return markdownExportService.deepReport(companyId, jobId).map(this::pdfExport);
    }

    public Optional<PdfExport> latestContentPack(Long companyId) {
        return markdownExportService.latestContentPack(companyId).map(this::pdfExport);
    }

    private PdfExport pdfExport(ReputationAiMarkdownExportService.MarkdownExport markdownExport) {
        String fileName = markdownExport.fileName().replaceFirst("\\.md$", ".pdf");
        return new PdfExport(fileName, renderPdf(markdownExport.markdown(), fileName));
    }

    private byte[] renderPdf(String markdown, String fileName) {
        try (PDDocument document = new PDDocument()) {
            PDType0Font font = loadFont(document);
            DocumentSummary summary = documentSummary(markdown, fileName);
            applyMetadata(document, summary);

            PdfWriter writer = new PdfWriter(document, font, summary);
            boolean titleSkipped = false;
            for (String line : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                if (!titleSkipped && isPrimaryTitle(line, summary)) {
                    titleSkipped = true;
                    continue;
                }
                writer.writeMarkdownLine(line);
            }
            writer.close();
            writeFooters(document, font);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось сформировать PDF-экспорт AI-помощника", exception);
        }
    }

    private void applyMetadata(PDDocument document, DocumentSummary summary) {
        PDDocumentInformation information = document.getDocumentInformation();
        information.setTitle(summary.title());
        information.setAuthor("Компания О!");
        information.setSubject(summary.typeLabel() + " для " + summary.companyName());
        information.setCreator("AI-помощник репутации");
        information.setKeywords("reputation-ai, export, pdf");
        information.setCreationDate(Calendar.getInstance());
    }

    private DocumentSummary documentSummary(String markdown, String fileName) {
        String title = "";
        String createdAt = "";
        String completedAt = "";

        for (String line : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (title.isBlank()) {
                Matcher headingMatcher = PRIMARY_HEADING.matcher(line.trim());
                if (headingMatcher.matches()) {
                    title = normalizeInline(headingMatcher.group(1));
                }
            }

            Matcher metadataMatcher = METADATA.matcher(line.trim());
            if (metadataMatcher.matches()) {
                String label = normalizeInline(metadataMatcher.group(1));
                String value = normalizeInline(metadataMatcher.group(2));
                if ("Создан".equals(label)) {
                    createdAt = value;
                } else if ("Готов".equals(label)) {
                    completedAt = value;
                }
            }
        }

        String typeLabel = fileName.contains("content-pack") || title.startsWith("AI-пакет")
                ? "AI-пакет репутации"
                : "Глубокий AI-отчет";
        String fallbackTitle = typeLabel + ": " + fileName.replaceFirst("\\.pdf$", "");
        String documentTitle = firstNonBlank(title, fallbackTitle);
        String companyName = companyNameFromTitle(documentTitle);
        String reportDate = firstNonBlank(completedAt, createdAt, "не указана");
        return new DocumentSummary(documentTitle, companyName, typeLabel, reportDate);
    }

    private String companyNameFromTitle(String title) {
        int separator = title.indexOf(':');
        if (separator >= 0 && separator + 1 < title.length()) {
            String companyName = normalizeInline(title.substring(separator + 1));
            if (!companyName.isBlank()) {
                return companyName;
            }
        }
        return normalizeInline(title);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String clean = value == null ? "" : value.trim();
            if (!clean.isBlank()) {
                return clean;
            }
        }
        return "";
    }

    private boolean isPrimaryTitle(String line, DocumentSummary summary) {
        Matcher headingMatcher = PRIMARY_HEADING.matcher((line == null ? "" : line).trim());
        return headingMatcher.matches() && normalizeInline(headingMatcher.group(1)).equals(summary.title());
    }

    private PDType0Font loadFont(PDDocument document) throws IOException {
        ClassPathResource fontResource = new ClassPathResource("static/font/borders.ttf");
        try (InputStream input = fontResource.getInputStream()) {
            return PDType0Font.load(document, input, true);
        }
    }

    private String normalizeInline(String value) {
        String result = value == null ? "" : value.trim();
        Matcher matcher = LINK.matcher(result);
        StringBuilder linked = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group(1) + " (" + matcher.group(2) + ")";
            matcher.appendReplacement(linked, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(linked);
        result = linked.toString();
        return result
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> wrap(PDType0Font font, String text, float fontSize, float width) throws IOException {
        String safeText = normalizeForFont(font, normalizeControlChars(text));
        if (safeText.isBlank()) {
            return List.of("");
        }

        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : safeText.split(" ")) {
            if (word.isBlank()) {
                continue;
            }
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (textWidth(font, candidate, fontSize) <= width) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }

            if (!current.isEmpty()) {
                lines.add(current.toString());
                current.setLength(0);
            }

            if (textWidth(font, word, fontSize) <= width) {
                current.append(word);
            } else {
                List<String> chunks = wrapLongWord(font, word, fontSize, width);
                lines.addAll(chunks.subList(0, Math.max(0, chunks.size() - 1)));
                if (!chunks.isEmpty()) {
                    current.append(chunks.getLast());
                }
            }
        }

        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private List<String> wrapLongWord(PDType0Font font, String word, float fontSize, float width) throws IOException {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < word.length(); ) {
            int codePoint = word.codePointAt(offset);
            String symbol = new String(Character.toChars(codePoint));
            String candidate = current + symbol;
            if (!current.isEmpty() && textWidth(font, candidate, fontSize) > width) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(symbol);
            offset += Character.charCount(codePoint);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private float textWidth(PDType0Font font, String text, float fontSize) throws IOException {
        return font.getStringWidth(text) / 1000F * fontSize;
    }

    private String normalizeForFont(PDType0Font font, String value) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String replacement = replacementFor(codePoint);
            if (canRender(font, replacement)) {
                result.append(replacement);
            } else {
                result.append("?");
            }
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private boolean canRender(PDType0Font font, String value) throws IOException {
        try {
            font.getStringWidth(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String replacementFor(int codePoint) {
        return switch (codePoint) {
            case 0x00A0 -> " ";
            case 0x00AB, 0x00BB, 0x201C, 0x201D, 0x201E -> "\"";
            case 0x2018, 0x2019 -> "'";
            case 0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2212 -> "-";
            case 0x2022 -> "-";
            case 0x2026 -> "...";
            case 0x2116 -> "No.";
            case 0x20BD -> "руб.";
            default -> new String(Character.toChars(codePoint));
        };
    }

    private String normalizeControlChars(String value) {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isISOControl(codePoint)) {
                result.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private void writeFooters(PDDocument document, PDType0Font font) throws IOException {
        int pageNumber = 1;
        int totalPages = document.getNumberOfPages();
        for (PDPage page : document.getPages()) {
            try (PDPageContentStream footer = new PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
            )) {
                float ruleY = 42F;
                footer.setStrokingColor(LINE);
                footer.setLineWidth(0.6F);
                footer.moveTo(MARGIN, ruleY);
                footer.lineTo(PAGE_WIDTH - MARGIN, ruleY);
                footer.stroke();

                String left = "Сформировано AI-помощником репутации";
                String right = "Страница " + pageNumber + " из " + totalPages;
                writeTextAt(footer, font, left, FOOTER_FONT_SIZE, MARGIN, 28F, INFO);

                String normalizedRight = normalizeForFont(font, normalizeControlChars(right));
                float rightWidth = textWidth(font, normalizedRight, FOOTER_FONT_SIZE);
                writeTextAt(footer, font, normalizedRight, FOOTER_FONT_SIZE, PAGE_WIDTH - MARGIN - rightWidth, 28F, INFO);
            }
            pageNumber++;
        }
    }

    private void writeTextAt(
            PDPageContentStream content,
            PDType0Font font,
            String text,
            float fontSize,
            float x,
            float y,
            Color color
    ) throws IOException {
        content.beginText();
        content.setNonStrokingColor(color);
        content.setFont(font, fontSize);
        content.newLineAtOffset(x, y);
        content.showText(normalizeForFont(font, normalizeControlChars(text)));
        content.endText();
    }

    private final class PdfWriter {
        private final PDDocument document;
        private final PDType0Font font;
        private final DocumentSummary summary;
        private PDPageContentStream content;
        private float y;
        private boolean previousBlank = false;

        private PdfWriter(PDDocument document, PDType0Font font, DocumentSummary summary) throws IOException {
            this.document = document;
            this.font = font;
            this.summary = summary;
            addPage();
            writeDocumentHeader();
        }

        private void writeMarkdownLine(String rawLine) throws IOException {
            String line = rawLine == null ? "" : rawLine.stripTrailing();
            if (line.isBlank()) {
                if (!previousBlank) {
                    moveDown(6F);
                }
                previousBlank = true;
                return;
            }
            previousBlank = false;

            if (line.startsWith("# ")) {
                writeParagraph(normalizeInline(line.substring(2)), 16.5F, 22F, 0F, 12F);
                return;
            }
            if (line.startsWith("## ")) {
                writeParagraph(normalizeInline(line.substring(3)), 13.4F, 18F, 0F, 12F);
                return;
            }
            if (line.startsWith("### ")) {
                writeParagraph(normalizeInline(line.substring(4)), 11.8F, 16.5F, 0F, 8F);
                return;
            }

            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                writeParagraph("- " + normalizeInline(trimmed.substring(2)), BODY_FONT_SIZE, BODY_LEADING, 12F, 1F);
                return;
            }
            if (trimmed.matches("^\\d+\\.\\s+.*")) {
                writeParagraph(normalizeInline(trimmed), BODY_FONT_SIZE, BODY_LEADING, 12F, 1F);
                return;
            }

            writeParagraph(normalizeInline(line), BODY_FONT_SIZE, BODY_LEADING, 0F, 1F);
        }

        private void writeParagraph(String text, float fontSize, float leading, float indent, float topSpacing) throws IOException {
            if (topSpacing > 0) {
                moveDown(topSpacing);
            }

            float availableWidth = PAGE_WIDTH - (MARGIN * 2) - indent;
            for (String line : wrap(font, text, fontSize, availableWidth)) {
                writeLine(line, fontSize, leading, indent);
            }
        }

        private void writeLine(String text, float fontSize, float leading, float indent) throws IOException {
            ensureSpace(leading);
            writeTextAt(content, font, text, fontSize, MARGIN + indent, y, DARK);
            y -= leading;
        }

        private void moveDown(float value) throws IOException {
            ensureSpace(value);
            y -= value;
        }

        private void ensureSpace(float required) throws IOException {
            if (y - required >= MARGIN) {
                return;
            }
            close();
            addPage();
        }

        private void addPage() throws IOException {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN;
        }

        private void writeDocumentHeader() throws IOException {
            float headerY = PAGE_HEIGHT - MARGIN;
            writeTextAt(content, font, "Компания О!", 10.5F, MARGIN, headerY, PRIMARY);
            headerY -= 16F;
            writeTextAt(content, font, summary.typeLabel(), 10.2F, MARGIN, headerY, INFO);
            headerY -= 24F;

            List<String> titleLines = wrap(font, summary.companyName(), 18F, CONTENT_WIDTH);
            int writtenTitleLines = 0;
            for (String titleLine : titleLines) {
                if (writtenTitleLines >= 2) {
                    break;
                }
                writeTextAt(content, font, titleLine, 18F, MARGIN, headerY, DARK);
                headerY -= 22F;
                writtenTitleLines++;
            }

            headerY -= 2F;
            writeTextAt(content, font, "Дата отчета: " + summary.reportDate(), 9.2F, MARGIN, headerY, INFO);
            headerY -= 14F;
            writeTextAt(content, font, "Документ сформирован из сохраненного результата AI-раздела.", 9.2F, MARGIN, headerY, INFO);
            headerY -= 14F;

            content.setStrokingColor(LINE);
            content.setLineWidth(0.8F);
            content.moveTo(MARGIN, headerY);
            content.lineTo(PAGE_WIDTH - MARGIN, headerY);
            content.stroke();
            y = headerY - 20F;
        }

        private void close() throws IOException {
            if (content != null) {
                content.close();
                content = null;
            }
        }
    }

    public record PdfExport(String fileName, byte[] bytes) {
    }

    private record DocumentSummary(String title, String companyName, String typeLabel, String reportDate) {
    }
}
