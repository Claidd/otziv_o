package com.hunt.otziv.c_companies.service;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Identity comes only from a 2GIS card URL, never from a company name or address. */
public final class TwoGisUrl {
    private static final Set<String> HOSTS = Set.of(
            "2gis.ru", "2gis.com", "2gis.kz", "2gis.kg", "2gis.uz", "2gis.ae", "2gis.by");
    private static final Pattern FIRM = Pattern.compile("/firm/([0-9]{5,32})(?=/|$)");
    private static final Pattern GEO = Pattern.compile("/geo/([0-9]{5,32})(?=/|$)");
    private static final Pattern ADD_REVIEW = Pattern.compile("^/reviews/([0-9]{5,32})/addReview/?$");
    private static final Pattern SHORT = Pattern.compile("^/[a-zA-Z0-9_-]{1,100}/?$");

    private TwoGisUrl() { }

    public static boolean supports(String value) {
        try {
            String host = URI.create(value == null ? "" : value.trim()).getHost();
            return host != null && (HOSTS.contains(host.toLowerCase(Locale.ROOT)) || "go.2gis.com".equalsIgnoreCase(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static URI parse(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || !(HOSTS.contains(host) || "go.2gis.com".equals(host))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Нужна ссылка на карточку организации в 2ГИС");
        }
    }

    public static Optional<String> cardId(URI uri) {
        if (!HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        var match = FIRM.matcher(uri.getRawPath());
        if (!match.find()) {
            match = GEO.matcher(uri.getRawPath());
            if (!match.find()) {
                match = ADD_REVIEW.matcher(uri.getRawPath());
                if (!match.find()) {
                    return Optional.empty();
                }
            }
        }
        String id = match.group(1);
        // Search, inside and branches URLs can contain a selected /firm/<id> card.
        // Ambiguous multiple selections must be clarified rather than guessed.
        while (match.find()) {
            if (!id.equals(match.group(1))) {
                return Optional.empty();
            }
        }
        return Optional.of(id);
    }

    public static boolean isShort(URI uri) {
        return "go.2gis.com".equalsIgnoreCase(uri.getHost()) && SHORT.matcher(uri.getRawPath()).matches();
    }

    public static String shortKey(URI uri) {
        if (!isShort(uri)) {
            throw new IllegalArgumentException("Нужна короткая ссылка 2ГИС");
        }
        return "https://go.2gis.com" + uri.getRawPath().replaceAll("/$", "");
    }
}
