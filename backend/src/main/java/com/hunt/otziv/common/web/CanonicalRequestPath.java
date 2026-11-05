package com.hunt.otziv.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;

/**
 * Request path exactly as Spring Security and Spring MVC match it: relative to the
 * context path, percent-decoded per segment and without matrix parameters.
 *
 * <p>Security decisions must never compare the raw {@code getRequestURI()} with a
 * pattern: {@code /api/leads/%6Dodified} is a different string but the same route.
 */
public final class CanonicalRequestPath {

    private CanonicalRequestPath() {
    }

    public static String of(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        String contextPath = request.getContextPath();
        RequestPath path;
        try {
            path = RequestPath.parse(uri, contextPath == null || contextPath.isBlank() ? null : contextPath);
        } catch (IllegalArgumentException contextMismatch) {
            try {
                path = RequestPath.parse(uri, null);
            } catch (IllegalArgumentException malformed) {
                return withoutMatrix(uri);
            }
        }
        StringBuilder canonical = new StringBuilder(uri.length());
        try {
            for (PathContainer.Element element : path.pathWithinApplication().elements()) {
                if (element instanceof PathContainer.PathSegment segment) {
                    canonical.append(segment.valueToMatch());
                } else {
                    canonical.append(element.value());
                }
            }
        } catch (IllegalArgumentException malformedEncoding) {
            return withoutMatrix(uri);
        }
        return canonical.toString();
    }

    public static boolean matchesPathOrDescendant(String path, String prefix) {
        return path != null && (path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    /**
     * True when the raw path contains a percent escape that legitimate clients never send:
     * an encoded unreserved character (RFC 3986 letters, digits, {@code -._~}), an encoded
     * separator/escape ({@code / \ ; %}), a NUL byte, or a malformed escape.
     */
    public static boolean containsDisallowedEncoding(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        int length = rawPath.length();
        for (int index = 0; index < length; index++) {
            if (rawPath.charAt(index) != '%') {
                continue;
            }
            if (index + 2 >= length) {
                return true;
            }
            int high = Character.digit(rawPath.charAt(index + 1), 16);
            int low = Character.digit(rawPath.charAt(index + 2), 16);
            if (high < 0 || low < 0) {
                return true;
            }
            int decoded = (high << 4) | low;
            if (isUnreserved(decoded) || decoded == '/' || decoded == '\\' || decoded == ';'
                    || decoded == '%' || decoded == 0) {
                return true;
            }
            index += 2;
        }
        return false;
    }

    private static boolean isUnreserved(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    private static String withoutMatrix(String path) {
        int matrixSeparator = path.indexOf(';');
        return matrixSeparator >= 0 ? path.substring(0, matrixSeparator) : path;
    }
}
