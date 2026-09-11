package com.hunt.otziv.r_review.photo;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Serializes attaching a URL with retiring it, including manually entered URLs. */
@Service
@RequiredArgsConstructor
public class ReviewPhotoReferencePolicy {
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAssignable(String url) {
        if (url == null || url.isBlank()) return;
        MapSqlParameterSource parameters = parameters(url);
        ensureReference(parameters);
        Boolean retired = jdbc.queryForObject("""
                SELECT retired FROM review_photo_reference_guard
                WHERE reference_hash = :hash FOR UPDATE
                """, parameters, Boolean.class);
        if (Boolean.TRUE.equals(retired)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Фотография уже заменена. Загрузите файл повторно.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retireForDeletion(String url) {
        if (url == null || url.isBlank()) return false;
        MapSqlParameterSource parameters = parameters(url);
        ensureReference(parameters);
        jdbc.update("UPDATE review_photo_reference_guard SET retired = TRUE WHERE reference_hash = :hash", parameters);
        // Caller uses READ_COMMITTED. The URL fence already excludes new
        // attachments, so reference scanning must not lock unrelated reviews.
        String reference = canonicalReference(url);
        return Boolean.TRUE.equals(jdbc.getJdbcTemplate().query(connection -> {
            var statement = connection.prepareStatement(
                    "SELECT review_url FROM reviews WHERE review_url IS NOT NULL AND review_url <> ''",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            // MySQL streams the legacy URL column; no full result is retained
            // in memory. Only asynchronous deletion uses this compatibility scan.
            statement.setFetchSize(Integer.MIN_VALUE);
            return statement;
        }, result -> {
            while (result.next()) {
                if (reference.equals(canonicalReference(result.getString(1)))) return false;
            }
            return true;
        }));
    }

    private void ensureReference(MapSqlParameterSource parameters) {
        jdbc.update("""
                INSERT INTO review_photo_reference_guard (reference_hash, retired)
                VALUES (:hash, FALSE)
                ON DUPLICATE KEY UPDATE reference_hash = :hash
                """, parameters);
    }

    private MapSqlParameterSource parameters(String url) {
        try {
            return new MapSqlParameterSource("hash", MessageDigest.getInstance("SHA-256")
                    .digest(canonicalReference(url).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String canonicalReference(String url) {
        String value = url == null ? "" : url.trim();
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getScheme() == null) return value;
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) port = -1;
            return new URI(scheme, null,
                    uri.getHost().toLowerCase(Locale.ROOT), port, uri.getPath(), null, null)
                    .normalize().toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException invalid) {
            return value;
        }
    }
}
