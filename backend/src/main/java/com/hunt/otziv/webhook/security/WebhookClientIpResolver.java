package com.hunt.otziv.webhook.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

@Component
public class WebhookClientIpResolver {

    private static final int HARD_MAX_FORWARDED_ADDRESSES = 64;
    private static final int HARD_MAX_FORWARDED_HEADER_LENGTH = 8_192;

    private final List<CidrRange> trustedProxies;
    private final int maxForwardedAddresses;
    private final int maxForwardedHeaderLength;

    public WebhookClientIpResolver(
            @Value("${webhook.rate-limit.trusted-proxies:}") String trustedProxyCidrs,
            @Value("${webhook.rate-limit.max-forwarded-addresses:16}") int maxForwardedAddresses,
            @Value("${webhook.rate-limit.max-forwarded-header-length:2048}") int maxForwardedHeaderLength
    ) {
        this.trustedProxies = parseCidrs(trustedProxyCidrs);
        this.maxForwardedAddresses = clamp(maxForwardedAddresses, 1, HARD_MAX_FORWARDED_ADDRESSES);
        this.maxForwardedHeaderLength = clamp(maxForwardedHeaderLength, 1, HARD_MAX_FORWARDED_HEADER_LENGTH);
    }

    public String resolve(HttpServletRequest request) {
        HttpServletRequest sourceRequest = unwrapForwardedRequest(request);
        ParsedAddress immediatePeer = parseAddress(sourceRequest.getRemoteAddr());
        if (immediatePeer == null) {
            return "unknown";
        }

        if (!isTrustedProxy(immediatePeer)) {
            return immediatePeer.normalized();
        }

        ParsedForwardedHeader forwarded = parseForwardedFor(sourceRequest);
        if (forwarded.present()) {
            if (!forwarded.valid()) {
                return immediatePeer.normalized();
            }

            ParsedAddress current = immediatePeer;
            List<ParsedAddress> chain = forwarded.addresses();
            for (int index = chain.size() - 1; index >= 0; index--) {
                if (!isTrustedProxy(current)) {
                    break;
                }
                current = chain.get(index);
            }
            return current.normalized();
        }

        ParsedAddress realIp = parseSingleHeader(sourceRequest, "X-Real-IP");
        return realIp == null ? immediatePeer.normalized() : realIp.normalized();
    }

    private static HttpServletRequest unwrapForwardedRequest(HttpServletRequest request) {
        ServletRequest current = request;
        // Spring's ForwardedHeaderFilter wraps the request before this filter and may rewrite
        // remoteAddr from an untrusted XFF value. The container request retains the real peer.
        for (int depth = 0; depth < 16 && current instanceof ServletRequestWrapper wrapper; depth++) {
            ServletRequest nested = wrapper.getRequest();
            if (nested == current) {
                break;
            }
            current = nested;
        }
        return current instanceof HttpServletRequest httpRequest ? httpRequest : request;
    }

    private ParsedForwardedHeader parseForwardedFor(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("X-Forwarded-For");
        if (values == null || !values.hasMoreElements()) {
            return ParsedForwardedHeader.absent();
        }

        List<ParsedAddress> addresses = new ArrayList<>();
        int totalLength = 0;
        while (values.hasMoreElements()) {
            String value = values.nextElement();
            if (value == null || value.isBlank()) {
                return ParsedForwardedHeader.invalid();
            }

            int separatorLength = totalLength == 0 ? 0 : 1;
            if (value.length() > maxForwardedHeaderLength - totalLength - separatorLength) {
                return ParsedForwardedHeader.invalid();
            }
            totalLength += separatorLength + value.length();

            int tokenStart = 0;
            for (int index = 0; index <= value.length(); index++) {
                if (index < value.length() && value.charAt(index) != ',') {
                    continue;
                }
                if (addresses.size() >= maxForwardedAddresses) {
                    return ParsedForwardedHeader.invalid();
                }

                ParsedAddress address = parseAddress(value.substring(tokenStart, index).trim());
                if (address == null) {
                    return ParsedForwardedHeader.invalid();
                }
                addresses.add(address);
                tokenStart = index + 1;
            }
        }

        return addresses.isEmpty()
                ? ParsedForwardedHeader.invalid()
                : ParsedForwardedHeader.valid(addresses);
    }

    private ParsedAddress parseSingleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }

        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.length() > maxForwardedHeaderLength) {
            return null;
        }
        return parseAddress(value.trim());
    }

    private boolean isTrustedProxy(ParsedAddress address) {
        return trustedProxies.stream().anyMatch(range -> range.contains(address.bytes()));
    }

    private static List<CidrRange> parseCidrs(String configuredCidrs) {
        if (configuredCidrs == null || configuredCidrs.isBlank()) {
            return List.of();
        }

        List<CidrRange> parsed = new ArrayList<>();
        for (String configuredCidr : configuredCidrs.split(",")) {
            String cidr = configuredCidr.trim();
            if (cidr.isEmpty()) {
                continue;
            }
            parsed.add(parseCidr(cidr));
        }
        return List.copyOf(parsed);
    }

    private static CidrRange parseCidr(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash <= 0 || slash != cidr.lastIndexOf('/') || slash == cidr.length() - 1) {
            throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + cidr);
        }

        ParsedAddress address = parseAddress(cidr.substring(0, slash).trim());
        if (address == null) {
            throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + cidr);
        }

        int prefixLength;
        try {
            prefixLength = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + cidr, exception);
        }

        int addressBits = address.bytes().length * Byte.SIZE;
        if (prefixLength < 0 || prefixLength > addressBits) {
            throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + cidr);
        }

        byte[] network = address.bytes().clone();
        int completeBytes = prefixLength / Byte.SIZE;
        int remainingBits = prefixLength % Byte.SIZE;
        if (remainingBits > 0) {
            network[completeBytes] &= (byte) (0xFF << (Byte.SIZE - remainingBits));
            completeBytes++;
        }
        for (int index = completeBytes; index < network.length; index++) {
            network[index] = 0;
        }
        return new CidrRange(network, prefixLength);
    }

    private static ParsedAddress parseAddress(String rawAddress) {
        if (rawAddress == null) {
            return null;
        }

        String address = rawAddress.trim();
        if (address.startsWith("[") && address.endsWith("]") && address.length() > 2) {
            address = address.substring(1, address.length() - 1);
        }
        if (address.isEmpty() || address.indexOf('%') >= 0) {
            return null;
        }

        byte[] bytes;
        if (address.indexOf(':') >= 0) {
            if (!hasOnlyIpv6Characters(address)) {
                return null;
            }
            try {
                bytes = InetAddress.getByName(address).getAddress();
            } catch (UnknownHostException exception) {
                return null;
            }
            if (bytes.length != 16) {
                return null;
            }
        } else {
            bytes = parseIpv4(address);
            if (bytes == null) {
                return null;
            }
        }

        try {
            return new ParsedAddress(bytes, InetAddress.getByAddress(bytes).getHostAddress());
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private static byte[] parseIpv4(String address) {
        byte[] bytes = new byte[4];
        int octetIndex = 0;
        int octetValue = 0;
        int octetDigits = 0;

        for (int index = 0; index <= address.length(); index++) {
            char character = index == address.length() ? '.' : address.charAt(index);
            if (character == '.') {
                if (octetDigits == 0 || octetIndex >= bytes.length || octetValue > 255) {
                    return null;
                }
                bytes[octetIndex++] = (byte) octetValue;
                octetValue = 0;
                octetDigits = 0;
                continue;
            }
            if (character < '0' || character > '9' || octetDigits >= 3) {
                return null;
            }
            octetValue = octetValue * 10 + character - '0';
            octetDigits++;
        }

        return octetIndex == bytes.length ? bytes : null;
    }

    private static boolean hasOnlyIpv6Characters(String address) {
        for (int index = 0; index < address.length(); index++) {
            char character = address.charAt(index);
            boolean allowed = character == ':'
                    || character == '.'
                    || character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private record ParsedAddress(byte[] bytes, String normalized) {
    }

    private record CidrRange(byte[] network, int prefixLength) {

        private boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }

            int completeBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < completeBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }

            int mask = 0xFF << (Byte.SIZE - remainingBits);
            return (address[completeBytes] & mask) == (network[completeBytes] & mask);
        }
    }

    private record ParsedForwardedHeader(boolean present, boolean valid, List<ParsedAddress> addresses) {

        private static ParsedForwardedHeader absent() {
            return new ParsedForwardedHeader(false, true, List.of());
        }

        private static ParsedForwardedHeader invalid() {
            return new ParsedForwardedHeader(true, false, List.of());
        }

        private static ParsedForwardedHeader valid(List<ParsedAddress> addresses) {
            return new ParsedForwardedHeader(true, true, List.copyOf(addresses));
        }
    }
}
