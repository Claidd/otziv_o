package com.hunt.otziv.text_generator.service.parser;

import com.hunt.otziv.text_generator.service.toGPT.ReviewGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class WebsiteParserServiceImpl implements WebsiteParserService{

    private final ReviewGeneratorService reviewGeneratorService;


    private static final int MAX_PAGES = 20; // Ограничим, чтобы не зациклиться
    private final Set<String> visitedUrls = new HashSet<>();

    @Override
    public String extractTextFromWebsite(String rootUrl) {
        visitedUrls.clear();
        if (rootUrl == null || rootUrl.isBlank()) return "⚠️ Не указан сайт.";
        if (!rootUrl.startsWith("http")) rootUrl = "https://" + rootUrl;

        try {
            URI rootUri = new URI(rootUrl);
            String domain = rootUri.getHost();

            String raw = crawl(rootUrl, domain, 0);
            String raw2 = cleanAndDeduplicateText(raw);
            return reviewGeneratorService.safeAnalyzeSiteText(raw2);
        } catch (Exception e) {
            log.error("Ошибка при парсинге сайта: {}", e.getMessage(), e);
            return "⚠️ Ошибка при обработке сайта.";
        }
    }


    private String crawl(String url, String domain, int depth) {
        if (visitedUrls.contains(url) || visitedUrls.size() >= MAX_PAGES) return "";

        try {
            log.info("🔗 Чтение страницы: {}", url);
            visitedUrls.add(url);

            Document doc = Jsoup.connect(url).get();

            // Удаляем шум перед извлечением
            doc.select(" script, style, .menu, .sidebar, .breadcrumbs").remove();

            StringBuilder allText = new StringBuilder(doc.body().text()).append("\n");

            Elements links = doc.select("a[href]");
            for (Element link : links) {
                String absHref = link.absUrl("href");
                if (absHref.contains(domain)) {
                    allText.append(crawl(absHref.split("#")[0], domain, depth + 1));
                }
            }

            return allText.toString();

        } catch (Exception e) {
            log.warn("⚠️ Проблема с {}: {}", url, e.getMessage());
            return "";
        }
    }

    private String cleanAndDeduplicateText(String raw) {
        return Arrays.stream(raw.split("\\s+"))
                .map(String::trim)
                .filter(word -> !word.isBlank())
                .map(word -> word.replaceAll("[^а-яА-Яa-zA-Z0-9]", "").toLowerCase())
                .distinct()
                .collect(Collectors.joining(" "));
    }

}
