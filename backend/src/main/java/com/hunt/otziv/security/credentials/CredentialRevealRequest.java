package com.hunt.otziv.security.credentials;

public record CredentialRevealRequest(
        String field,
        String sourcePage,
        String sourceEntry,
        String sourceSection
) {
}
