package com.hunt.otziv.reputationai.application;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PageRoleClassifier {

    private static final Pattern GENERIC_CATALOG_TITLE_PATTERN = Pattern.compile(
            "(?iu)(^|\\s)(все|лучшие|топ|рейтинг|народный рейтинг|каталог|подборка|список|сравнение|категории)\\b"
    );
    private static final List<String> SERVICE_PATH_MARKERS = List.of(
            "/legal", "/support", "/help", "/privacy", "/terms", "/agreement", "/license",
            "/rules", "/confidential", "/login", "/auth", "/account", "/register", "/signin",
            "/subscribe", "/subscription", "/cookie", "/faq"
    );
    private static final List<String> SERVICE_TEXT_MARKERS = List.of(
            "лицензионное соглашение", "пользовательское соглашение", "политика конфиденциальности",
            "условия подписки", "служба поддержки", "правила сервиса", "войти или зарегистрироваться",
            "регистрация и доступ", "частые вопросы", "нет такой страницы"
    );
    private static final List<String> CATALOG_PATH_MARKERS = List.of(
            "/catalog", "/categories", "/category", "/ratings", "/rating", "/top", "/best",
            "/search", "/tags", "/tag", "/selection", "/collections", "/collection"
    );
    private static final List<String> PROFILE_PATH_MARKERS = List.of(
            "/company/", "/companies/", "/org/", "/firm/", "/biz/", "/place/", "/places/",
            "/maps/org/", "/filial/", "/branches/"
    );
    private static final List<String> REVIEW_MARKERS = List.of(
            "отзыв", "reviews", "rating", "рейтинг", "оценк", "звезд", "stars"
    );
    private static final List<String> PROFILE_MARKERS = List.of(
            "адрес", "телефон", "контакты", "режим работы", "карта", "филиал", "организация", "компания"
    );

    public PageRole classify(String url, String title, String text, PageRoleContext context) {
        String normalizedUrl = normalizeUrl(url);
        String path = path(normalizedUrl);
        String titleText = normalizeInline(title);
        String textValue = normalizeInline(text);
        String haystack = normalizeForMatch(String.join(" ", normalizedUrl, titleText, textValue));

        if (isServiceOrLegalPage(normalizedUrl, titleText, textValue)) {
            return PageRole.SERVICE_OR_LEGAL;
        }
        if (isOfficialWebsitePage(normalizedUrl, context == null ? "" : context.officialWebsite())) {
            return PageRole.OFFICIAL_SITE;
        }

        boolean hasCompany = containsBusinessName(haystack, normalizedUrl, context);
        boolean hasCity = context != null && !context.city().isBlank() && haystack.contains(normalizeForMatch(context.city()));
        boolean hasBusinessToken = context != null && context.businessTokens().stream()
                .map(this::normalizeForMatch)
                .filter(token -> token.length() >= 4)
                .anyMatch(haystack::contains);
        boolean reviewPage = containsAny(haystack, REVIEW_MARKERS) || path.contains("/reviews");
        boolean profilePage = containsAny(path, PROFILE_PATH_MARKERS)
                || containsAny(haystack, PROFILE_MARKERS);

        if (isGenericCatalogPage(normalizedUrl, titleText, textValue, context)) {
            return hasCity || hasBusinessToken ? PageRole.CATALOG_LISTING : PageRole.IRRELEVANT;
        }
        if (path.contains("/reviews") && hasCompany) {
            return PageRole.REVIEW_PAGE;
        }
        if (profilePage && hasCompany) {
            return PageRole.COMPANY_PROFILE;
        }
        if (reviewPage && hasCompany) {
            return PageRole.REVIEW_PAGE;
        }
        if (hasCompany) {
            return PageRole.COMPANY_PROFILE;
        }
        if (hasCity && hasBusinessToken) {
            return PageRole.COMPETITOR_LISTING;
        }
        return PageRole.UNKNOWN_PUBLIC;
    }

    public boolean isServiceOrLegalPage(String url, String title, String text) {
        String normalizedUrl = normalizeUrl(url);
        String lowerPath = path(normalizedUrl).toLowerCase(Locale.ROOT);
        String titleOnly = normalizeForMatch(title);
        String haystack = normalizeForMatch(String.join(" ", normalizedUrl, title, text));
        return SERVICE_PATH_MARKERS.stream().anyMatch(lowerPath::startsWith)
                || SERVICE_PATH_MARKERS.stream().anyMatch(lowerPath::contains)
                || titleOnly.contains("404")
                || titleOnly.contains("page not found")
                || titleOnly.contains("страница не найдена")
                || titleOnly.contains("not found")
                || titleOnly.contains("нет такой страницы")
                || lowerPath.contains("sitemap")
                || haystack.contains("sitemap")
                || SERVICE_TEXT_MARKERS.stream().anyMatch(titleOnly::contains)
                || haystack.contains("seo title: лицензионное соглашение")
                || haystack.contains("seo title: пользовательское соглашение")
                || haystack.contains("seo title: политика конфиденциальности")
                || haystack.contains("seo title: условия подписки")
                || haystack.contains("seo title: служба поддержки");
    }

    public boolean isGenericCatalogPage(String url, String title, String text, PageRoleContext context) {
        String normalizedUrl = normalizeUrl(url);
        String lowerPath = path(normalizedUrl).toLowerCase(Locale.ROOT);
        String haystack = normalizeForMatch(String.join(" ", title, text));
        boolean catalogPath = CATALOG_PATH_MARKERS.stream().anyMatch(lowerPath::startsWith)
                || CATALOG_PATH_MARKERS.stream().anyMatch(lowerPath::contains)
                || isShortPluralListingPath(lowerPath);
        boolean catalogTitle = GENERIC_CATALOG_TITLE_PATTERN.matcher(haystack).find();
        boolean companySpecificPath = PROFILE_PATH_MARKERS.stream().anyMatch(lowerPath::contains)
                && lowerPath.split("/").length >= 3;
        boolean hasCompany = containsBusinessName(haystack, normalizedUrl, context);
        return (catalogPath || catalogTitle) && !(companySpecificPath && hasCompany);
    }

    public boolean isOfficialWebsitePage(String url, String officialWebsite) {
        if (url == null || url.isBlank() || officialWebsite == null || officialWebsite.isBlank()) {
            return false;
        }
        String urlHost = host(normalizeUrl(url));
        String officialHost = host(normalizeUrl(officialWebsite));
        return !urlHost.isBlank() && urlHost.equals(officialHost);
    }

    public String sourceType(PageRole role, String prefix) {
        PageRole safeRole = role == null ? PageRole.UNKNOWN_PUBLIC : role;
        String safePrefix = prefix == null || prefix.isBlank() ? "" : prefix.trim();
        return safePrefix.isBlank() ? safeRole.sourceType() : safePrefix + ":" + safeRole.sourceType();
    }

    public String host(String url) {
        try {
            String host = URI.create(normalizeUrl(url)).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception exception) {
            return "";
        }
    }

    private boolean containsBusinessName(String haystack, String url, PageRoleContext context) {
        if (context == null || context.companyName().isBlank()) {
            return false;
        }
        String normalizedName = normalizeForMatch(context.companyName());
        String compactName = compact(normalizedName);
        String compactHaystack = compact(haystack + " " + path(url));
        return haystack.contains(normalizedName)
                || compactName.length() >= 4 && compactHaystack.contains(compactName);
    }

    private boolean isShortPluralListingPath(String lowerPath) {
        if (lowerPath == null || lowerPath.isBlank() || "/".equals(lowerPath)) {
            return false;
        }
        String clean = lowerPath.replaceAll("/+$", "");
        int segments = (int) List.of(clean.split("/")).stream().filter(segment -> !segment.isBlank()).count();
        String last = clean.substring(clean.lastIndexOf('/') + 1);
        return segments == 1 && List.of(
                "quests", "kvesty", "reviews", "ratings", "companies", "catalog",
                "categories", "places", "firms", "orgs", "services", "products"
        ).contains(last);
    }

    private boolean containsAny(String value, List<String> markers) {
        String lower = normalizeForMatch(value);
        return markers.stream()
                .map(this::normalizeForMatch)
                .anyMatch(lower::contains);
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
        return fragmentIndex >= 0 ? normalized.substring(0, fragmentIndex) : normalized;
    }

    private String path(String url) {
        try {
            String path = URI.create(normalizeUrl(url)).getPath();
            return path == null ? "" : path;
        } catch (Exception exception) {
            return "";
        }
    }

    private String normalizeInline(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String normalizeForMatch(String text) {
        return normalizeInline(text).toLowerCase(Locale.ROOT);
    }

    private String compact(String text) {
        return normalizeForMatch(text).replaceAll("[^a-zа-яё0-9]+", "");
    }
}
