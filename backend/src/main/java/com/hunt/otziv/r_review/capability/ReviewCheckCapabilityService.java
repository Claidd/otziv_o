package com.hunt.otziv.r_review.capability;

import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityRepository.CapabilityRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewCheckCapabilityService {

    private static final String TOKEN_PREFIX = "rc1_";
    private static final Pattern TOKEN_FORMAT = Pattern.compile("^rc1_[A-Za-z0-9_-]{43}$");
    private static final int TOKEN_BYTES = 32;
    private static final int DEFAULT_EXPIRY_DAYS = 30;
    private static final int MAX_EXPIRY_DAYS = 365;
    private static final String ROTATION_REASON = "rotated";
    private static final byte[] MISSING_TOKEN_HASH = sha256("review-check-missing-token");

    private final ReviewCheckCapabilityRepository repository;
    private final MeterRegistry meterRegistry;
    private final SecureRandom secureRandom;

    @Autowired
    public ReviewCheckCapabilityService(
            ReviewCheckCapabilityRepository repository,
            MeterRegistry meterRegistry
    ) {
        this(repository, meterRegistry, new SecureRandom());
    }

    ReviewCheckCapabilityService(
            ReviewCheckCapabilityRepository repository,
            MeterRegistry meterRegistry,
            SecureRandom secureRandom
    ) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.secureRandom = secureRandom;
    }

    @Transactional
    public void recordLegacyUse(UUID orderDetailId, LegacyAction action) {
        if (orderDetailId == null || action == null) {
            return;
        }
        repository.recordLegacyUse(orderDetailId, sha256(canonicalLegacyToken(orderDetailId)));
        incrementUseMetric("legacy_uuid", action.metricValue());
    }

    @Transactional
    public ResolvedCapability resolveAndTouch(
            String rawToken,
            ReviewCheckCapabilityScope requiredScope,
            String action
    ) {
        String candidate = rawToken == null ? "" : rawToken;
        byte[] candidateHash = sha256(candidate);
        CapabilityRow row = repository.findByTokenHashForUpdate(candidateHash).orElse(null);

        // Always compare two 32-byte digests, including for a database miss.
        // The public outcome remains one uniform 404 contract.
        boolean tokenMatches = MessageDigest.isEqual(
                candidateHash,
                row == null ? MISSING_TOKEN_HASH : row.tokenHash()
        );
        boolean activeTokenType = row != null && "OPAQUE".equals(row.tokenType());
        boolean scopeGranted = row != null
                && requiredScope != null
                && requiredScope.includedIn(row.scopeMask());
        if (!TOKEN_FORMAT.matcher(candidate).matches()
                || !tokenMatches
                || !activeTokenType
                || !scopeGranted) {
            throw notFound();
        }
        // The database clock is authoritative for expiry and revocation. The
        // token row remains locked through the public action, which closes the
        // resolve-vs-revoke race; last-used telemetry is write-throttled.
        if (!repository.isActiveByDatabaseClock(row.id())) {
            throw notFound();
        }
        repository.touchIfActiveAndDue(row.id());

        incrementUseMetric("opaque", safeMetricAction(action));
        return new ResolvedCapability(row.id(), row.orderDetailId(), row.scopeMask(), row.expiresAt());
    }

    @Transactional
    public IssuedCapability issue(
            UUID orderDetailId,
            Set<String> scopes,
            Integer expiresInDays,
            Long actorUserId
    ) {
        if (orderDetailId == null || actorUserId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ресурс публичной ссылки не указан");
        }
        long scopeMask = ReviewCheckCapabilityScope.mask(scopes);
        int days = expiryDays(expiresInDays);
        String token = generateToken();
        CapabilityRow inserted = repository.insertOpaque(
                orderDetailId,
                sha256(token),
                scopeMask,
                actorUserId,
                days
        );
        return issued(inserted.id(), orderDetailId, token, scopeMask, inserted.expiresAt());
    }

    @Transactional
    public IssuedCapability rotate(
            long capabilityId,
            UUID orderDetailId,
            Integer expiresInDays,
            Long actorUserId
    ) {
        CapabilityRow current = requireManagedOpaque(capabilityId, orderDetailId);
        if (current.revokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Публичная ссылка уже отозвана");
        }
        if (repository.revoke(capabilityId, orderDetailId, actorUserId, ROTATION_REASON) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Публичная ссылка уже изменена");
        }

        int days = expiryDays(expiresInDays);
        String token = generateToken();
        CapabilityRow inserted = repository.insertOpaque(
                orderDetailId,
                sha256(token),
                current.scopeMask(),
                actorUserId,
                days
        );
        return issued(inserted.id(), orderDetailId, token, current.scopeMask(), inserted.expiresAt());
    }

    @Transactional
    public void revoke(
            long capabilityId,
            UUID orderDetailId,
            Long actorUserId,
            String reason
    ) {
        CapabilityRow current = requireManagedOpaque(capabilityId, orderDetailId);
        if (current.revokedAt() != null) {
            return;
        }
        String safeReason = boundedReason(reason);
        if (repository.revoke(capabilityId, orderDetailId, actorUserId, safeReason) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Публичная ссылка уже изменена");
        }
    }

    @Transactional(readOnly = true)
    public List<CapabilityMetadata> list(UUID orderDetailId) {
        if (orderDetailId == null) {
            return List.of();
        }
        return repository.findOpaqueByOrderDetailId(orderDetailId).stream()
                .map(this::metadata)
                .toList();
    }

    private CapabilityRow requireManagedOpaque(long capabilityId, UUID orderDetailId) {
        CapabilityRow row = repository.findByIdForUpdate(capabilityId).orElseThrow(ReviewCheckCapabilityService::notFound);
        if (!"OPAQUE".equals(row.tokenType()) || !row.orderDetailId().equals(orderDetailId)) {
            throw notFound();
        }
        return row;
    }

    private IssuedCapability issued(
            long id,
            UUID orderDetailId,
            String token,
            long scopeMask,
            LocalDateTime expiresAt
    ) {
        return new IssuedCapability(
                id,
                orderDetailId,
                token,
                ReviewCheckCapabilityScope.names(scopeMask),
                expiresAt
        );
    }

    private CapabilityMetadata metadata(CapabilityRow row) {
        return new CapabilityMetadata(
                row.id(),
                row.orderDetailId(),
                ReviewCheckCapabilityScope.names(row.scopeMask()),
                row.expiresAt(),
                row.revokedAt(),
                row.revocationReason(),
                row.lastUsedAt(),
                row.issuedAt(),
                row.issuedByUserId(),
                row.revokedByUserId()
        );
    }

    private String generateToken() {
        byte[] entropy = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(entropy);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String canonicalLegacyToken(UUID orderDetailId) {
        return orderDetailId.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private int expiryDays(Integer requested) {
        int value = requested == null ? DEFAULT_EXPIRY_DAYS : requested;
        if (value < 1 || value > MAX_EXPIRY_DAYS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Срок ссылки должен быть от 1 до " + MAX_EXPIRY_DAYS + " дней"
            );
        }
        return value;
    }

    private String boundedReason(String reason) {
        String value = reason == null || reason.isBlank() ? "revoked_by_operator" : reason.trim();
        if (value.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Причина отзыва слишком длинная");
        }
        return value;
    }

    private void incrementUseMetric(String type, String action) {
        meterRegistry.counter(
                "review.check.capability.use",
                "token_type", type,
                "action", action
        ).increment();
    }

    private String safeMetricAction(String action) {
        if (action == null || action.isBlank()) {
            return "unknown";
        }
        return switch (action) {
            case "view", "edit", "approve", "correction" -> action;
            default -> "unknown";
        };
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Проверка отзывов не найдена");
    }

    public enum LegacyAction {
        VIEW("view"),
        EDIT("edit"),
        APPROVE("approve"),
        CORRECTION("correction"),
        OTHER("other");

        private final String metricValue;

        LegacyAction(String metricValue) {
            this.metricValue = metricValue;
        }

        String metricValue() {
            return metricValue;
        }
    }

    public record ResolvedCapability(
            long id,
            UUID orderDetailId,
            long scopeMask,
            LocalDateTime expiresAt
    ) {
        public boolean has(ReviewCheckCapabilityScope scope) {
            return scope != null && scope.includedIn(scopeMask);
        }
    }

    public record IssuedCapability(
            long id,
            UUID orderDetailId,
            String token,
            Set<String> scopes,
            LocalDateTime expiresAt
    ) {
    }

    public record CapabilityMetadata(
            long id,
            UUID orderDetailId,
            Set<String> scopes,
            LocalDateTime expiresAt,
            LocalDateTime revokedAt,
            String revocationReason,
            LocalDateTime lastUsedAt,
            LocalDateTime issuedAt,
            Long issuedByUserId,
            Long revokedByUserId
    ) {
    }
}
