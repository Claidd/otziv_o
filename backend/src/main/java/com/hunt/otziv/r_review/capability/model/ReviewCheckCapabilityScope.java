package com.hunt.otziv.r_review.capability.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public actions granted by an opaque review-check capability.
 *
 * <p>The bit values are persisted, so they must never be renumbered or reused.</p>
 */
public enum ReviewCheckCapabilityScope {
    VIEW(1L),
    EDIT(1L << 1),
    APPROVE(1L << 2),
    SEND_CORRECTION(1L << 3);

    public static final long ALL_PUBLIC_MASK = Arrays.stream(values())
            .mapToLong(ReviewCheckCapabilityScope::bit)
            .reduce(0L, (left, right) -> left | right);

    private final long bit;

    ReviewCheckCapabilityScope(long bit) {
        this.bit = bit;
    }

    public long bit() {
        return bit;
    }

    public boolean includedIn(long mask) {
        return (mask & bit) == bit;
    }

    public static long mask(Set<String> requestedScopes) {
        if (requestedScopes == null || requestedScopes.isEmpty()) {
            return ALL_PUBLIC_MASK;
        }

        long mask = 0L;
        for (String requestedScope : requestedScopes) {
            if (requestedScope == null || requestedScope.isBlank()) {
                throw invalidScope();
            }
            try {
                mask |= valueOf(requestedScope.trim().toUpperCase(Locale.ROOT)).bit();
            } catch (IllegalArgumentException exception) {
                throw invalidScope();
            }
        }
        return mask;
    }

    public static Set<String> names(long mask) {
        return EnumSet.allOf(ReviewCheckCapabilityScope.class).stream()
                .filter(scope -> scope.includedIn(mask))
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ResponseStatusException invalidScope() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестное право публичной ссылки");
    }
}
