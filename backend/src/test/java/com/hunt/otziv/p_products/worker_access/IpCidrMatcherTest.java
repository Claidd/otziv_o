package com.hunt.otziv.p_products.worker_access;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpCidrMatcherTest {

    @Test
    void matchesIpv4AndIpv6Networks() {
        IpCidrMatcher matcher = new IpCidrMatcher(List.of("100.64.0.0/10", "2a00:1fa0::/32"));

        assertTrue(matcher.matches("100.64.12.34"));
        assertTrue(matcher.matches("100.127.255.254"));
        assertTrue(matcher.matches("2a00:1fa0:1234::1"));
        assertFalse(matcher.matches("100.128.0.1"));
        assertFalse(matcher.matches("2a01:1fa0::1"));
    }

    @Test
    void rejectsHostnamesAndInvalidCidrs() {
        IpCidrMatcher matcher = new IpCidrMatcher(List.of("10.0.0.0/8"));

        assertFalse(matcher.matches("example.com"));
        assertThrows(IllegalArgumentException.class, () -> new IpCidrMatcher(List.of("10.0.0.0/99")));
    }
}
