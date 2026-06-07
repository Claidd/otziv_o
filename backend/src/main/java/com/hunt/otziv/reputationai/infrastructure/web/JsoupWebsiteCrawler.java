package com.hunt.otziv.reputationai.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.security.OutboundUrlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class JsoupWebsiteCrawler implements WebsiteCrawler {

    private static final Pattern SITEMAP_LOC_PATTERN = Pattern.compile("(?is)<loc>\\s*(https?://[^<\\s]+)\\s*</loc>");
    private static final Pattern PRICE_PATTERN = Pattern.compile("(?iu)(?:от\\s*)?\\d[\\d\\s]{1,7}\\s*(?:₽|руб\\.?|р/ч|р\\b)");
    private static final int MAX_QUEUED_URLS_MULTIPLIER = 4;
    private static final List<String> SINGLE_PAGE_PLATFORM_HOSTS = List.of(
            "yandex.ru",
            "2gis.ru",
            "vk.com",
            "vk.ru",
            "zoon.ru",
            "flamp.ru",
            "tripadvisor.ru",
            "tripadvisor.com",
            "otzovik.com",
            "irecommend.ru",
            "spravker.ru",
            "yell.ru",
            "mir-kvestov.ru",
            "google.com",
            "google.ru"
    );

    private final ReputationAiProperties properties;
    private final OutboundUrlGuard urlGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<CrawledPage> crawl(List<String> urls) {
        return crawl(urls, null);
    }

    @Override
    public List<CrawledPage> crawl(List<String> urls, Set<String> deepCrawlHosts) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }

        List<CrawledPage> pages = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> normalizedDeepCrawlHosts = normalizeHosts(deepCrawlHosts);

        for (String url : urls) {
            if (pages.size() >= properties.getMaxWebsitePages()) {
                break;
            }

            String normalized = normalizeUrl(url);
            if (!normalized.isBlank() && urlGuard.isAllowed(normalized)) {
                crawlRoot(normalized, pages, visited, normalizedDeepCrawlHosts);
            }
        }

        return pages;
    }

    private void crawlRoot(String rootUrl, List<CrawledPage> pages, Set<String> visited, Set<String> deepCrawlHosts) {
        if (rootUrl.isBlank() || !isHttpUrl(rootUrl)) {
            return;
        }

        String rootHost = host(rootUrl);
        if (rootHost.isBlank()) {
            return;
        }

        Queue<String> queue = new ArrayDeque<>();
        Set<String> queued = new LinkedHashSet<>();
        boolean followInternalLinks = shouldFollowInternalLinks(rootHost)
                && (deepCrawlHosts == null || deepCrawlHosts.isEmpty() || deepCrawlHosts.contains(rootHost));
        int rootPageStart = pages.size();
        int rootPageBudget = followInternalLinks ? properties.getMaxDeepWebsitePages() : 1;
        enqueue(rootUrl, rootHost, queue, queued, visited);
        if (followInternalLinks) {
            discoverSitemapUrls(rootUrl, rootHost).forEach(url -> enqueue(url, rootHost, queue, queued, visited));
        }

        while (!queue.isEmpty()
                && pages.size() < properties.getMaxWebsitePages()
                && pages.size() - rootPageStart < rootPageBudget) {
            String currentUrl = queue.poll();
            if (!visited.add(currentUrl)) {
                continue;
            }

            try {
                Document document = fetch(currentUrl, false).parse();
                String text = limit(buildPageText(document, currentUrl));
                if (!text.isBlank() && !isBlockedOrServicePage(document.title(), text, currentUrl)) {
                    pages.add(new CrawledPage(currentUrl, document.title(), text));
                }

                if (!followInternalLinks) {
                    continue;
                }

                for (Element link : document.select("a[href]")) {
                    if (queued.size() >= properties.getMaxWebsitePages() * MAX_QUEUED_URLS_MULTIPLIER) {
                        break;
                    }

                    String href = normalizeUrl(link.absUrl("href"));
                    enqueue(href, rootHost, queue, queued, visited);
                }
            } catch (Exception exception) {
                log.warn("Reputation AI crawler skipped {}: {}", currentUrl, exception.getMessage());
            }
        }
    }

    private Connection connect(String url) {
        return Jsoup.connect(url)
                .userAgent(properties.getUserAgent())
                .referrer("https://yandex.ru/")
                .timeout((int) properties.getWebsiteTimeout().toMillis())
                .followRedirects(false)
                .ignoreHttpErrors(true);
    }

    private Connection.Response fetch(String url, boolean ignoreContentType) throws IOException {
        String currentUrl = url;
        urlGuard.assertAllowed(currentUrl);
        for (int redirects = 0; redirects <= urlGuard.maxRedirects(); redirects++) {
            Connection.Response response = connect(currentUrl)
                    .ignoreContentType(ignoreContentType)
                    .execute();
            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return response;
            }

            currentUrl = urlGuard.resolveRedirect(currentUrl, response.header("Location"));
        }

        throw new IOException("Too many redirects");
    }

    private void enqueue(String url, String rootHost, Queue<String> queue, Set<String> queued, Set<String> visited) {
        String normalized = normalizeUrl(url);
        if (normalized.isBlank()
                || visited.contains(normalized)
                || queued.contains(normalized)
                || !rootHost.equals(host(normalized))
                || !urlGuard.isAllowed(normalized)
                || isKnownPlatformServiceUrl(normalized)
                || isLikelyAssetUrl(normalized)) {
            return;
        }

        queue.add(normalized);
        queued.add(normalized);
    }

    private List<String> discoverSitemapUrls(String rootUrl, String rootHost) {
        try {
            URI uri = URI.create(rootUrl);
            String scheme = uri.getScheme() == null || uri.getScheme().isBlank() ? "https" : uri.getScheme();
            String authority = uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()
                    ? uri.getHost()
                    : uri.getRawAuthority();
            String sitemapUrl = scheme + "://" + authority + "/sitemap.xml";
            String sitemap = fetch(sitemapUrl, true).body();

            LinkedHashSet<String> urls = new LinkedHashSet<>();
            Matcher matcher = SITEMAP_LOC_PATTERN.matcher(sitemap == null ? "" : sitemap);
            while (matcher.find() && urls.size() < properties.getMaxWebsitePages() * MAX_QUEUED_URLS_MULTIPLIER) {
                String url = normalizeUrl(matcher.group(1));
                if (rootHost.equals(host(url)) && urlGuard.isAllowed(url) && !isLikelyAssetUrl(url)) {
                    urls.add(url);
                }
            }
            return List.copyOf(urls);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String buildPageText(Document document, String currentUrl) {
        List<String> parts = new ArrayList<>();
        addLine(parts, "URL страницы", currentUrl);
        addLine(parts, "SEO title", document.title());
        addLine(parts, "Описание страницы", metaContent(document, "description"));

        List<String> headings = document.select("h1, h2")
                .eachText()
                .stream()
                .map(this::normalizeInline)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(10)
                .toList();
        if (!headings.isEmpty()) {
            addLine(parts, "Заголовки страницы", String.join("; ", headings));
        }

        extractJsonLdFacts(document).forEach(parts::add);

        Document visibleDocument = document.clone();
        visibleDocument.select("script, style, noscript, svg, canvas, iframe, nav, footer, .menu, .sidebar").remove();
        String visibleText = normalizeInline(visibleDocument.body() == null ? "" : visibleDocument.body().text());
        visibleText = cleanupPlatformText(currentUrl, visibleText);

        OfferSummary pageOffer = inferPageOffer(document, currentUrl, visibleText);
        if (pageOffer != null) {
            parts.add(formatOffer(pageOffer));
        }

        extractImageFacts(document).forEach(parts::add);
        addLine(parts, "Текст страницы", visibleText);

        return normalizeMultiline(String.join("\n", parts));
    }

    private List<String> extractJsonLdFacts(Document document) {
        List<String> facts = new ArrayList<>();
        for (Element script : document.select("script[type=application/ld+json]")) {
            String json = script.data();
            if (json == null || json.isBlank()) {
                continue;
            }

            try {
                collectJsonLdFacts(objectMapper.readTree(json), facts);
            } catch (Exception exception) {
                facts.addAll(extractLenientProductFacts(json));
            }
        }
        return facts.stream().distinct().limit(20).toList();
    }

    private void collectJsonLdFacts(JsonNode node, List<String> facts) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectJsonLdFacts(child, facts));
            return;
        }
        if (node.has("@graph")) {
            collectJsonLdFacts(node.get("@graph"), facts);
        }

        String type = jsonText(node, "@type").toLowerCase(Locale.ROOT);
        if (type.contains("product") || type.contains("service")) {
            String name = jsonText(node, "name");
            String description = jsonText(node, "description");
            String url = jsonText(node, "url");
            String price = jsonOfferPrice(node);
            if (!name.isBlank()) {
                facts.add(formatOffer(new OfferSummary(name, description, price, url)));
            }
        } else if (type.contains("organization") || type.contains("localbusiness")) {
            List<String> addresses = new ArrayList<>();
            JsonNode addressNode = node.get("address");
            if (addressNode != null && addressNode.isArray()) {
                addressNode.forEach(address -> addresses.add(formatAddress(address)));
            } else if (addressNode != null) {
                addresses.add(formatAddress(addressNode));
            }

            List<String> parts = new ArrayList<>();
            addInline(parts, "название", jsonText(node, "name"));
            addInline(parts, "телефон", jsonText(node, "telephone"));
            if (!addresses.isEmpty()) {
                addInline(parts, "адреса", String.join("; ", addresses));
            }
            if (!parts.isEmpty()) {
                facts.add("Организация: " + String.join(" | ", parts));
            }
        }
    }

    private List<String> extractLenientProductFacts(String json) {
        List<String> facts = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?is)\"@type\"\\s*:\\s*\"(?:Product|Service)\"(.*?)(?=\"@type\"\\s*:\\s*\"(?:Product|Service)\"|\\]\\s*$|</script>)")
                .matcher(json);
        while (matcher.find() && facts.size() < 20) {
            String block = matcher.group(1);
            String name = jsonField(block, "name");
            if (name.isBlank()) {
                continue;
            }
            facts.add(formatOffer(new OfferSummary(
                    name,
                    jsonField(block, "description"),
                    joinNonBlank(jsonField(block, "price"), jsonField(block, "priceCurrency")),
                    jsonField(block, "url")
            )));
        }
        return facts;
    }

    private OfferSummary inferPageOffer(Document document, String url, String visibleText) {
        if (isIndexOrSelectionPage(url, document.title())) {
            return null;
        }

        String titleName = cleanOfferName(document.title());
        String name = isLikelyOfferName(titleName)
                ? titleName
                : document.select("h2").eachText().stream()
                .map(this::cleanOfferName)
                .filter(this::isLikelyOfferName)
                .findFirst()
                .orElseGet(() -> cleanOfferName(document.title()));
        if (!isLikelyOfferName(name)) {
            return null;
        }

        String price = extractFirstPrice(visibleText);
        String description = metaContent(document, "description");
        return new OfferSummary(name, description, price, url);
    }

    private List<String> extractImageFacts(Document document) {
        LinkedHashSet<String> facts = new LinkedHashSet<>();
        for (Element image : document.select("img[alt], img[title]")) {
            String text = firstNonBlank(image.attr("alt"), image.attr("title"));
            text = normalizeInline(text);
            if (text.length() >= 5 && !isNoisyImageText(text)) {
                facts.add("Фото/изображение: " + text);
            }
            if (facts.size() >= 12) {
                break;
            }
        }
        return List.copyOf(facts);
    }

    private String formatOffer(OfferSummary offer) {
        List<String> parts = new ArrayList<>();
        parts.add("Услуга/товар: " + normalizeInline(offer.name()));
        addInline(parts, "описание", limitInline(offer.description(), 360));
        addInline(parts, "цена", offer.price());
        addInline(parts, "URL", offer.url());
        return String.join(" | ", parts);
    }

    private String formatAddress(JsonNode address) {
        if (address == null || address.isMissingNode() || address.isNull()) {
            return "";
        }
        if (address.isTextual()) {
            return normalizeInline(address.asText());
        }
        return joinNonBlank(
                jsonText(address, "addressLocality"),
                jsonText(address, "streetAddress"),
                jsonText(address, "addressCountry")
        );
    }

    private String jsonOfferPrice(JsonNode node) {
        JsonNode offers = node.get("offers");
        if (offers == null || offers.isMissingNode() || offers.isNull()) {
            return "";
        }
        if (offers.isArray() && !offers.isEmpty()) {
            offers = offers.get(0);
        }
        return joinNonBlank(jsonText(offers, "price"), jsonText(offers, "priceCurrency"));
    }

    private String jsonText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isValueNode()) {
            return normalizeInline(value.asText());
        }
        return normalizeInline(value.toString());
    }

    private String jsonField(String block, String fieldName) {
        Pattern pattern = Pattern.compile("(?is)\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"");
        Matcher matcher = pattern.matcher(block == null ? "" : block);
        if (!matcher.find()) {
            return "";
        }
        return normalizeInline(matcher.group(1)
                .replace("\\\"", "\"")
                .replace("\\n", " "));
    }

    private String metaContent(Document document, String name) {
        return normalizeInline(document.select("meta[name=" + name + "]").attr("content"));
    }

    private String cleanOfferName(String value) {
        String clean = normalizeInline(value)
                .replaceAll("(?iu)\\s+в\\s+иркутске\\s+и\\s+ангарске", "")
                .replaceAll("(?iu)(иркутск|ирктск|иркутске|иркутска|ангарск|ангарске|ангарска)", "")
                .replaceAll("(?iu)\\bв\\s+и\\b", "")
                .replaceAll("(?iu)\\bг\\.\\s*", "")
                .replaceAll("[,.;:]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return clean;
    }

    private boolean isLikelyOfferName(String value) {
        String lower = normalizeInline(value).toLowerCase(Locale.ROOT);
        if (lower.length() < 3 || lower.length() > 80) {
            return false;
        }
        return containsAny(lower, List.of("квест", "лазертаг", "нерф", "картинг", "день рождения", "праздник", "выпускной", "прятки", "амонг", "хагги", "уэнсдей", "сталкер", "психоз", "тюрьма", "замок", "экзорцизм", "звездные"));
    }

    private boolean isIndexOrSelectionPage(String url, String title) {
        String path = "";
        try {
            path = URI.create(url).getPath();
        } catch (Exception ignored) {
            // ignore malformed URL here; normal URL validation happens earlier
        }
        String lower = normalizeInline(path + " " + title).toLowerCase(Locale.ROOT);
        return path == null
                || path.isBlank()
                || "/".equals(path)
                || lower.contains("index.html")
                || lower.contains("main.html")
                || lower.contains("404.html")
                || lower.contains("выбор города")
                || lower.contains("выбор категории");
    }

    private String extractFirstPrice(String text) {
        Matcher matcher = PRICE_PATTERN.matcher(text == null ? "" : text);
        return matcher.find() ? normalizeInline(matcher.group()) : "";
    }

    private void addLine(List<String> target, String label, String value) {
        String clean = normalizeInline(value);
        if (!clean.isBlank()) {
            target.add(label + ": " + clean);
        }
    }

    private void addInline(List<String> target, String label, String value) {
        String clean = normalizeInline(value);
        if (!clean.isBlank()) {
            target.add(label + ": " + clean);
        }
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }

        String normalized = url.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }

        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }

        return normalized;
    }

    private boolean isLikelyAssetUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(jpg|jpeg|png|gif|webp|svg|css|js|woff|woff2|ttf|eot|ico|mp4|ogg|webm|pdf)(\\?.*)?$");
    }

    private boolean isHttpUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception exception) {
            return "";
        }
    }

    private Set<String> normalizeHosts(Set<String> hosts) {
        if (hosts == null) {
            return null;
        }
        return hosts.stream()
                .map(host -> host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "").trim())
                .filter(host -> !host.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeInline(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String normalizeMultiline(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.lines()
                .map(this::normalizeInline)
                .filter(line -> !line.isBlank())
                .reduce("", (left, right) -> left.isBlank() ? right : left + "\n" + right);
    }

    private String limit(String text) {
        if (text.length() <= properties.getMaxWebsiteChars()) {
            return text;
        }

        return text.substring(0, properties.getMaxWebsiteChars()).trim();
    }

    private String limitInline(String text, int maxLength) {
        String clean = normalizeInline(text);
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength).trim();
    }

    private boolean isBlockedOrServicePage(String title, String text, String url) {
        String cleanTitle = normalizeInline(title).toLowerCase(Locale.ROOT);
        String haystack = normalizeInline((title == null ? "" : title)
                + " " + (text == null ? "" : text)
                + " " + (url == null ? "" : url)).toLowerCase(Locale.ROOT);
        return isKnownPlatformServiceUrl(url)
                || cleanTitle.contains("лицензионное соглашение")
                || cleanTitle.contains("пользовательское соглашение")
                || cleanTitle.contains("политика конфиденциальности")
                || cleanTitle.contains("условия подписки")
                || cleanTitle.contains("служба поддержки компаний")
                || cleanTitle.contains("награда яндекса")
                || haystack.contains("smartcaptcha")
                || haystack.contains("smart-captcha")
                || haystack.contains("captcha")
                || haystack.contains("are you not a robot")
                || haystack.contains("я не робот")
                || haystack.contains("please confirm that you are not a robot")
                || haystack.contains("403 forbidden")
                || haystack.contains("access denied")
                || haystack.contains("решение частых проблем");
    }

    boolean shouldFollowInternalLinks(String rootHost) {
        String normalizedHost = rootHost == null ? "" : rootHost.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        return SINGLE_PAGE_PLATFORM_HOSTS.stream().noneMatch(host -> normalizedHost.equals(host) || normalizedHost.endsWith("." + host));
    }

    boolean isKnownPlatformServiceUrl(String url) {
        String normalized = normalizeUrl(url);
        String lowerHost = host(normalized);
        String lowerPath = path(normalized).toLowerCase(Locale.ROOT);
        if (lowerHost.equals("yandex.ru") || lowerHost.endsWith(".yandex.ru")) {
            return lowerPath.startsWith("/legal/")
                    || lowerPath.startsWith("/support/")
                    || lowerPath.startsWith("/project/maps/goodplace")
                    || lowerPath.contains("/goodplace/")
                    || lowerPath.contains("goodplace_b2b")
                    || lowerPath.contains("yandex_plus")
                    || lowerPath.contains("_agreement")
                    || lowerPath.contains("_conditions")
                    || lowerPath.contains("confidential");
        }
        return false;
    }

    private String cleanupPlatformText(String url, String text) {
        String cleaned = normalizeInline(text);
        String currentHost = host(url);
        if (currentHost.equals("yandex.ru") || currentHost.endsWith(".yandex.ru")) {
            cleaned = cleaned
                    .replaceAll("(?iu)\\bShow business'?s response\\b", " ")
                    .replaceAll("(?iu)\\bLevel\\s+\\d+\\s+Local Expert\\s+Subscribe\\s+[A-Za-z]+\\s+\\d{1,2}(?:,\\s+\\d{4})?\\b", " ")
                    .replaceAll("(?iu)\\bSearch Directions\\b", " ")
                    .replaceAll("(?iu)\\bOverview Products and services News Photos\\b", " ")
                    .replaceAll("(?iu)\\bGood Place\\s+\\d{4}\\b", " ");
        } else if (currentHost.equals("mir-kvestov.ru") || currentHost.endsWith(".mir-kvestov.ru")) {
            cleaned = cleaned
                    .replaceAll("(?iu)ДобавитьУдалить", " ")
                    .replaceAll("(?iu)сравнения\\s+сравнения", " ")
                    .replaceAll("(?iu)Войти\\s+или\\s+зарегистрироваться", " ")
                    .replaceAll("(?iu)Войти\\s+с\\s+VK\\s+ID\\s+Войти\\s+с\\s+Яндекс\\s+ID\\s+Почта\\s+Mail\\s+Email\\s+Условия\\s+Пользовательского\\s+соглашения", " ");
        }
        return normalizeInline(cleaned);
    }

    private boolean isNoisyImageText(String text) {
        String lower = normalizeInline(text).toLowerCase(Locale.ROOT);
        return lower.equals("почта")
                || lower.equals("mail")
                || lower.equals("logo")
                || lower.equals("логотип")
                || lower.equals("image");
    }

    private boolean containsAny(String text, List<String> needles) {
        return needles.stream().anyMatch(text::contains);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second == null ? "" : second.trim();
    }

    private String joinNonBlank(String... values) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(normalizeInline(value));
                }
            }
        }
        return String.join(" ", result);
    }

    private String path(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? "" : path;
        } catch (Exception exception) {
            return "";
        }
    }

    private record OfferSummary(String name, String description, String price, String url) {
    }
}
