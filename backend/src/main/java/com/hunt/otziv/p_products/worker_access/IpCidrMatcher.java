package com.hunt.otziv.p_products.worker_access;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

final class IpCidrMatcher {

    private final List<CidrBlock> blocks;

    IpCidrMatcher(List<String> cidrs) {
        List<CidrBlock> parsed = new ArrayList<>();
        if (cidrs != null) {
            for (String cidr : cidrs) {
                if (cidr == null || cidr.isBlank()) {
                    continue;
                }
                parsed.add(CidrBlock.parse(cidr.trim()));
            }
        }
        this.blocks = List.copyOf(parsed);
    }

    boolean matches(String rawAddress) {
        InetAddress address = parseLiteral(rawAddress);
        if (address == null) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return blocks.stream().anyMatch(block -> block.matches(bytes));
    }

    boolean isEmpty() {
        return blocks.isEmpty();
    }

    private static InetAddress parseLiteral(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) {
            return null;
        }
        String value = rawAddress.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int zoneIndex = value.indexOf('%');
        if (zoneIndex >= 0) {
            value = value.substring(0, zoneIndex);
        }
        if (!value.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private record CidrBlock(byte[] network, int prefixLength) {

        private static CidrBlock parse(String value) {
            String[] parts = value.split("/", -1);
            InetAddress address = parseLiteral(parts[0]);
            if (address == null || parts.length > 2) {
                throw new IllegalArgumentException("Некорректный CIDR мобильной сети: " + value);
            }
            int maxPrefix = address.getAddress().length * Byte.SIZE;
            int prefix = parts.length == 1 ? maxPrefix : parsePrefix(parts[1], maxPrefix, value);
            byte[] network = address.getAddress().clone();
            clearHostBits(network, prefix);
            return new CidrBlock(network, prefix);
        }

        private static int parsePrefix(String rawPrefix, int maxPrefix, String value) {
            try {
                int prefix = Integer.parseInt(rawPrefix);
                if (prefix < 0 || prefix > maxPrefix) {
                    throw new IllegalArgumentException("Некорректная маска CIDR мобильной сети: " + value);
                }
                return prefix;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Некорректная маска CIDR мобильной сети: " + value, exception);
            }
        }

        private static void clearHostBits(byte[] network, int prefixLength) {
            for (int bit = prefixLength; bit < network.length * Byte.SIZE; bit++) {
                int byteIndex = bit / Byte.SIZE;
                int bitIndex = 7 - (bit % Byte.SIZE);
                network[byteIndex] = (byte) (network[byteIndex] & ~(1 << bitIndex));
            }
        }

        private boolean matches(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (Byte.SIZE - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
