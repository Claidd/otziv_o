package com.hunt.otziv.payments.service;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Central allow-list for URLs that are persisted or returned by payment flows.
 */
public final class PaymentUrlPolicy {

    private static final Set<String> WEB_SCHEMES = Set.of("http", "https");
    private static final String LEGACY_TEST_BANK_SCHEME = "bankapp";
    private static final Pattern NSPK_BANK_SCHEME = Pattern.compile("bank(?:b2b)?[0-9]{12}");
    private static final Pattern ENCODED_CONTROL = Pattern.compile("(?i).*(?:%0[0-9a-f]|%1[0-9a-f]|%7f).*");

    private PaymentUrlPolicy() {
    }

    public enum Purpose {
        MANUAL_EXTERNAL(512, false),
        TBANK_PAYMENT(1024, false),
        SBP_PAYLOAD(2048, true);

        private final int maxLength;
        private final boolean allowSbpSchemes;

        Purpose(int maxLength, boolean allowSbpSchemes) {
            this.maxLength = maxLength;
            this.allowSbpSchemes = allowSbpSchemes;
        }
    }

    public static String require(String value, Purpose purpose, HttpStatus status, String reason) {
        String clean = clean(value);
        if (!isValid(clean, purpose)) {
            throw new ResponseStatusException(status, reason);
        }
        return clean;
    }

    public static String optional(String value, Purpose purpose, HttpStatus status, String reason) {
        String clean = clean(value);
        return clean.isBlank() ? "" : require(clean, purpose, status, reason);
    }

    public static String safe(String value, Purpose purpose) {
        String clean = clean(value);
        return isValid(clean, purpose) ? clean : "";
    }

    /**
     * Fail-closed mapper for legacy read paths. A genuinely absent value keeps
     * the historical default; a nonblank or control-bearing unsafe value is
     * removed and is never replaced with another payment recipient.
     */
    public static String safeOrDefault(String value, String fallback, Purpose purpose) {
        if (value == null || (value.isBlank() && !hasControlCharacters(value))) {
            return safe(fallback, purpose);
        }
        return safe(value, purpose);
    }

    public static String requireOrDefault(
            String value,
            String fallback,
            Purpose purpose,
            HttpStatus status,
            String reason
    ) {
        if (value != null && (!value.isBlank() || hasControlCharacters(value))) {
            return require(value, purpose, status, reason);
        }
        return require(
                fallback,
                purpose,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Некорректная резервная ссылка оплаты"
        );
    }

    public static boolean isValid(String value, Purpose purpose) {
        if (purpose == null
                || value == null
                || value.isBlank()
                || value.length() > purpose.maxLength
                || hasControlCharacters(value)
                || ENCODED_CONTROL.matcher(value).matches()) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (WEB_SCHEMES.contains(scheme)) {
                return uri.isAbsolute()
                        && uri.getHost() != null
                        && !uri.getHost().isBlank()
                        && uri.getRawUserInfo() == null
                        && validPort(uri);
            }
            if (!purpose.allowSbpSchemes || !isAllowedSbpScheme(scheme)) {
                return false;
            }
            if (!uri.isAbsolute()
                    || uri.getRawAuthority() == null
                    || uri.getRawAuthority().isBlank()
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null
                    || !validPort(uri)) {
                return false;
            }
            return LEGACY_TEST_BANK_SCHEME.equals(scheme)
                    ? "pay".equalsIgnoreCase(uri.getHost())
                    : "qr.nspk.ru".equalsIgnoreCase(uri.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Distinguishes an absent legacy value (which may use the historical
     * default) from a persisted nonblank/control-bearing value that must be
     * quarantined and explicitly replaced.
     */
    public static boolean isUnsafeConfigured(String value, Purpose purpose) {
        return value != null
                && (!value.isBlank() || hasControlCharacters(value))
                && !isValid(clean(value), purpose);
    }

    public static boolean isGenericSbpPayload(String value) {
        String clean = clean(value);
        if (!isValid(clean, Purpose.SBP_PAYLOAD)) {
            return false;
        }
        try {
            URI uri = new URI(clean);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "qr.nspk.ru".equalsIgnoreCase(uri.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isAllowedSbpScheme(String scheme) {
        return LEGACY_TEST_BANK_SCHEME.equals(scheme) || NSPK_BANK_SCHEME.matcher(scheme).matches();
    }

    private static boolean validPort(URI uri) {
        return uri.getPort() >= -1 && uri.getPort() <= 65535;
    }

    private static boolean hasControlCharacters(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || (character >= 0x7f && character <= 0x9f)) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String value) {
        if (value == null || hasControlCharacters(value)) {
            return value == null ? "" : value;
        }
        return value.trim();
    }
}
