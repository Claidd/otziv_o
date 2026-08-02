package com.hunt.otziv.security;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

@Component
public class OutboundUrlGuard {

    private static final int MAX_REDIRECTS = 5;

    private final boolean allowLocalAddresses;

    public OutboundUrlGuard() {
        this(false);
    }

    private OutboundUrlGuard(boolean allowLocalAddresses) {
        this.allowLocalAddresses = allowLocalAddresses;
    }

    public static OutboundUrlGuard allowLocalAddressesForTests() {
        return new OutboundUrlGuard(true);
    }

    public static boolean isPublicHttpUrl(String value) {
        return new OutboundUrlGuard(false).isAllowed(value);
    }

    public boolean isAllowed(String value) {
        try {
            resolve(value);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public void assertAllowed(String value) {
        resolve(value);
    }

    /**
     * Resolves and validates a URL once. Callers must use {@link ResolvedTarget#addresses()}
     * for the subsequent socket connection instead of resolving the host again.
     */
    public ResolvedTarget resolve(String value) {
        URI uri = parseHttpUri(value);
        String host = uri.getHost();
        if (!hasText(host) || hasUserInfo(uri)) {
            throw new IllegalArgumentException("Outbound URL is not allowed");
        }
        if (!allowLocalAddresses && isLocalhostName(host)) {
            throw new IllegalArgumentException("Outbound URL is not allowed");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Outbound URL host cannot be resolved", exception);
        }
        if (addresses.length == 0) {
            throw new IllegalArgumentException("Outbound URL host cannot be resolved");
        }
        if (!allowLocalAddresses && Arrays.stream(addresses).anyMatch(address -> !isPublicAddress(address))) {
            throw new IllegalArgumentException("Outbound URL is not allowed");
        }
        return new ResolvedTarget(uri, addresses);
    }

    public int maxRedirects() {
        return MAX_REDIRECTS;
    }

    public String resolveRedirect(String currentUrl, String location) {
        if (!hasText(location)) {
            throw new IllegalArgumentException("Redirect location is empty");
        }

        URI current = parseHttpUri(currentUrl);
        URI resolved = current.resolve(location.trim());
        String normalized = resolved.toString();
        assertAllowed(normalized);
        return normalized;
    }

    public String normalizeHttpUrl(String value) {
        return parseHttpUri(value).toString();
    }

    private static URI parseHttpUri(String value) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("URL is empty");
        }

        String normalized = value.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }

        URI uri = URI.create(normalized);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http/https URLs are allowed");
        }
        if (!hasText(uri.getHost())) {
            throw new IllegalArgumentException("URL host is empty");
        }
        return uri;
    }

    private static boolean hasUserInfo(URI uri) {
        return hasText(uri.getUserInfo()) || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"));
    }

    private static boolean isLocalhostName(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("localhost") || lower.endsWith(".localhost");
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return !(first == 0
                    || first == 10
                    || first == 127
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && (second == 0 || second == 168))
                    || (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 224);
        }

        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xfe) != 0xfc;
        }

        return true;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record ResolvedTarget(URI uri, InetAddress[] addresses) {
        public ResolvedTarget {
            addresses = addresses == null ? new InetAddress[0] : addresses.clone();
        }

        @Override
        public InetAddress[] addresses() {
            return addresses.clone();
        }
    }
}
