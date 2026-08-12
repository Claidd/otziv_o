package com.hunt.otziv.b_bots.service;

import com.hunt.otziv.b_bots.config.MultiBrowserProperties;
import com.hunt.otziv.b_bots.dto.BrowserOpenResponse;
import com.hunt.otziv.b_bots.model.BotBrowserSession;
import com.hunt.otziv.b_bots.model.BotBrowserSessionStatus;
import com.hunt.otziv.b_bots.repository.BotBrowserSessionRepository;
import com.hunt.otziv.b_bots.service.BotBrowserAccessService.AuthorizedBot;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Slf4j
public class BotBrowserSessionService {

    private static final int MAX_VNC_URL_LENGTH = 4096;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final int SWEEP_BATCH_SIZE = 100;
    private static final Pattern ENCODED_CONTROL = Pattern.compile(
            "(?i).*(?:%0[0-9a-f]|%1[0-9a-f]|%7f).*"
    );
    private static final Set<String> GLOBAL_BROWSER_ROLES = Set.of(
            "ROLE_ADMIN",
            "ROLE_OWNER",
            "ROLE_MANAGER"
    );
    private static final Set<BotBrowserSessionStatus> ACTIVE_STATUSES = Set.of(
            BotBrowserSessionStatus.OPENING,
            BotBrowserSessionStatus.OPEN,
            BotBrowserSessionStatus.CLOSING,
            BotBrowserSessionStatus.STOP_RETRY
    );

    private final BotBrowserSessionRepository repository;
    private final BotBrowserAccessService accessService;
    private final RestTemplate browserRestTemplate;
    private final MultiBrowserProperties properties;
    private final Clock clock;

    @Autowired
    public BotBrowserSessionService(
            BotBrowserSessionRepository repository,
            BotBrowserAccessService accessService,
            @Qualifier("browserRestTemplate") RestTemplate browserRestTemplate,
            MultiBrowserProperties properties
    ) {
        this(repository, accessService, browserRestTemplate, properties, Clock.systemUTC());
    }

    BotBrowserSessionService(
            BotBrowserSessionRepository repository,
            BotBrowserAccessService accessService,
            RestTemplate browserRestTemplate,
            MultiBrowserProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.accessService = accessService;
        this.browserRestTemplate = browserRestTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public BrowserOpenResponse open(long botId, Authentication authentication) {
        return open(botId, authentication, true);
    }

    public BrowserOpenResponse open(
            long botId,
            Authentication authentication,
            boolean heartbeatSupported
    ) {
        AuthorizedBot bot = accessService.requireAccess(botId, authentication);
        CallerIdentity opener = callerIdentity(authentication);
        LocalDateTime now = now();
        BotBrowserSession session = new BotBrowserSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setBotId(bot.id());
        session.setExternalKeySnapshot(externalKey(bot));
        session.setOpenerUsername(opener.username());
        session.setOpenerSubject(opener.subject());
        session.setStatus(BotBrowserSessionStatus.OPENING);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setLastHeartbeatAt(now);
        session.setAbsoluteExpiresAt(now.plusSeconds(sessionMaxSeconds()));
        session.setHeartbeatExpiresAt(heartbeatSupported
                ? earliest(now.plusSeconds(heartbeatTimeoutSeconds()), session.getAbsoluteExpiresAt())
                : session.getAbsoluteExpiresAt());

        try {
            session = repository.saveAndFlush(session);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Для аккаунта уже открывается или работает браузерная сессия"
            );
        }

        boolean connectAttempted = false;
        try {
            connectAttempted = true;
            Map<?, ?> upstream = connect(session.getExternalKeySnapshot());

            // The object-level decision is deliberately queried again after the
            // remote side may have created a profile. A reassignment in this
            // window must never leak the returned VNC capability.
            accessService.requireAccess(botId, authentication);

            String vncUrl = requireHttpVncUrl(upstream.get("vncUrl"));
            String vncPassword = requireVncPassword(upstream.get("vncPassword"));
            LocalDateTime openedAt = now();
            int updated = repository.markOpen(
                    session.getSessionId(),
                    session.getVersion(),
                    openedAt,
                    heartbeatSupported
                            ? earliest(openedAt.plusSeconds(heartbeatTimeoutSeconds()), session.getAbsoluteExpiresAt())
                            : session.getAbsoluteExpiresAt()
            );
            if (updated != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Браузерная сессия была отозвана во время запуска"
                );
            }

            log.info("Browser session opened: botId={}, sessionId={}", botId, session.getSessionId());
            return new BrowserOpenResponse(
                    session.getSessionId(),
                    vncUrl,
                    vncPassword,
                    heartbeatIntervalSeconds(),
                    session.getAbsoluteExpiresAt().toInstant(ZoneOffset.UTC),
                    botId,
                    safeMetadata(upstream.get("userAgent"), 1024),
                    safeMetadata(upstream.get("platform"), 128),
                    safeMetadata(upstream.get("screenResolution"), 64)
            );
        } catch (ResponseStatusException exception) {
            if (connectAttempted) {
                bestEffortCloseAfterOpenFailure(session.getSessionId());
            }
            throw exception;
        } catch (RestClientException exception) {
            if (connectAttempted) {
                bestEffortCloseAfterOpenFailure(session.getSessionId());
            }
            log.warn("Browser upstream open failed: botId={}, sessionId={}, errorType={}",
                    botId, session.getSessionId(), exception.getClass().getSimpleName());
            throw upstreamFailure();
        } catch (RuntimeException exception) {
            if (connectAttempted) {
                bestEffortCloseAfterOpenFailure(session.getSessionId());
            }
            log.warn("Browser open lifecycle failed: botId={}, sessionId={}, errorType={}",
                    botId, session.getSessionId(), exception.getClass().getSimpleName());
            throw upstreamFailure();
        }
    }

    public void heartbeat(long botId, String rawSessionId, Authentication authentication) {
        String sessionId = canonicalSessionId(rawSessionId);
        CallerIdentity opener = callerIdentity(authentication);

        // Heartbeats retain object-level authorization. If the bot was
        // reassigned, the old opener can no longer keep the lease alive.
        accessService.requireAccess(botId, authentication);

        for (int attempt = 0; attempt < 2; attempt++) {
            BotBrowserSession session = repository.findById(sessionId).orElseThrow(this::notFound);
            requireSessionOwner(session, botId, opener);
            LocalDateTime now = now();
            if (session.getStatus() != BotBrowserSessionStatus.OPEN
                    || !session.getHeartbeatExpiresAt().isAfter(now)
                    || !session.getAbsoluteExpiresAt().isAfter(now)) {
                if (ACTIVE_STATUSES.contains(session.getStatus())
                        && session.getStatus() != BotBrowserSessionStatus.CLOSING) {
                    closeClaimedSession(session, false);
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Браузерная сессия больше не активна");
            }

            int updated = repository.heartbeat(
                    sessionId,
                    botId,
                    opener.subject(),
                    session.getVersion(),
                    now,
                    earliest(now.plusSeconds(heartbeatTimeoutSeconds()), session.getAbsoluteExpiresAt())
            );
            if (updated == 1) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Состояние браузерной сессии изменилось");
    }

    public void close(long botId, String rawSessionId, Authentication authentication) {
        String sessionId = canonicalSessionId(rawSessionId);
        CallerIdentity opener = callerIdentity(authentication);
        Optional<BotBrowserSession> found = repository.findById(sessionId);
        if (found.isEmpty()) {
            return;
        }

        BotBrowserSession session = found.get();
        requireSessionOwner(session, botId, opener);
        if (session.getStatus() == BotBrowserSessionStatus.CLOSED
                || session.getStatus() == BotBrowserSessionStatus.CLOSING) {
            return;
        }
        if (!closeClaimedSession(session, true)) {
            BotBrowserSession current = repository.findById(sessionId).orElseThrow(this::notFound);
            requireSessionOwner(current, botId, opener);
            if (current.getStatus() != BotBrowserSessionStatus.CLOSED
                    && current.getStatus() != BotBrowserSessionStatus.CLOSING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Состояние браузерной сессии изменилось");
            }
        }
    }

    /**
     * Compatibility endpoint for clients deployed before session IDs. It may
     * close only the caller's tracked session; otherwise it performs the old
     * fresh-access guarded stop for an untracked legacy profile.
     */
    public void closeLegacy(long botId, Authentication authentication) {
        AuthorizedBot bot = accessService.requireAccess(botId, authentication);
        CallerIdentity opener = callerIdentity(authentication);
        Optional<BotBrowserSession> active = repository
                .findFirstByBotIdAndStatusInOrderByCreatedAtDesc(botId, ACTIVE_STATUSES);
        if (active.isPresent()) {
            BotBrowserSession session = active.get();
            if (!session.getOpenerSubject().equals(opener.subject()) && !opener.globalBrowserRole()) {
                throw notFound();
            }
            if (session.getStatus() != BotBrowserSessionStatus.CLOSING) {
                closeClaimedSession(session, true);
            }
            return;
        }

        try {
            stop(externalKey(bot));
        } catch (RuntimeException exception) {
            log.warn("Legacy browser close failed: botId={}, errorType={}",
                    botId, exception.getClass().getSimpleName());
            throw upstreamFailure();
        }
    }

    @Scheduled(
            fixedDelayString = "${multibrowser.session-sweep-delay-ms:30000}",
            initialDelayString = "${multibrowser.session-sweep-initial-delay-ms:45000}"
    )
    public void sweepExpiredSessions() {
        LocalDateTime now = now();
        LocalDateTime openingCutoff = now.minusSeconds(openingTimeoutSeconds());
        // Longer than the provider HTTP timeout, so a second node does not
        // reclaim CLOSING while the first node can still be inside stop().
        LocalDateTime closingCutoff = now.minusSeconds(Math.max(120, openingTimeoutSeconds()));
        var candidates = repository.findSweepCandidates(
                now,
                openingCutoff,
                closingCutoff,
                PageRequest.of(0, SWEEP_BATCH_SIZE)
        );
        for (BotBrowserSession candidate : candidates) {
            try {
                closeClaimedSession(candidate, false);
            } catch (RuntimeException exception) {
                log.warn("Browser session sweep failed: botId={}, sessionId={}, errorType={}",
                        candidate.getBotId(), candidate.getSessionId(), exception.getClass().getSimpleName());
            }
        }
    }

    private Map<?, ?> connect(String externalKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("externalKey", externalKey);
        body.put("connectionMode", properties.connectionModeForConnect());
        body.put("proxyUrl", properties.proxyUrlForConnect());
        body.put("detectionLevel", "ENHANCED");
        body.put("forceNewFingerprint", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = browserRestTemplate.postForEntity(
                upstreamUri("integration", "profiles", "connect"),
                new HttpEntity<>(body, headers),
                Map.class
        );
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw upstreamFailure();
        }
        return response.getBody();
    }

    private boolean closeClaimedSession(BotBrowserSession candidate, boolean propagateStopFailure) {
        if (candidate == null || candidate.getStatus() == BotBrowserSessionStatus.CLOSED) {
            return true;
        }
        int claimed = repository.beginClosing(
                candidate.getSessionId(),
                candidate.getStatus(),
                candidate.getVersion(),
                now()
        );
        if (claimed != 1) {
            return false;
        }

        BotBrowserSession closing = repository.findById(candidate.getSessionId()).orElse(null);
        if (closing == null) {
            return true;
        }
        try {
            stop(closing.getExternalKeySnapshot());
        } catch (RuntimeException exception) {
            LocalDateTime failedAt = now();
            int deferred = repository.markStopRetry(
                    closing.getSessionId(),
                    closing.getVersion(),
                    failedAt,
                    failedAt.plusSeconds(stopRetryDelaySeconds(closing.getStopAttempts())),
                    sanitizedStopError(exception)
            );
            if (deferred != 1) {
                log.warn("Browser session stop retry transition lost optimistic race: botId={}, sessionId={}, version={}",
                        closing.getBotId(), closing.getSessionId(), closing.getVersion());
            }
            log.warn("Browser upstream stop deferred: botId={}, sessionId={}, errorType={}",
                    closing.getBotId(), closing.getSessionId(), exception.getClass().getSimpleName());
            if (propagateStopFailure) {
                throw upstreamFailure();
            }
            return true;
        }

        int completed = repository.markClosed(closing.getSessionId(), closing.getVersion(), now());
        if (completed != 1) {
            log.warn("Browser session close completion lost optimistic race: botId={}, sessionId={}, version={}",
                    closing.getBotId(), closing.getSessionId(), closing.getVersion());
            return false;
        }
        log.info("Browser session closed: botId={}, sessionId={}", closing.getBotId(), closing.getSessionId());
        return true;
    }

    private void bestEffortCloseAfterOpenFailure(String sessionId) {
        try {
            repository.findById(sessionId).ifPresent(session -> {
                // A CLOSING row is already owned by another stop attempt. It is
                // reclaimed only by the sweeper after the provider timeout.
                if (session.getStatus() != BotBrowserSessionStatus.CLOSING) {
                    closeClaimedSession(session, false);
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Browser post-connect cleanup deferred: sessionId={}, errorType={}",
                    sessionId, exception.getClass().getSimpleName());
        }
    }

    private void stop(String externalKeySnapshot) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            browserRestTemplate.postForLocation(
                    upstreamUri("integration", "profiles", externalKeySnapshot, "stop"),
                    new HttpEntity<Void>(headers)
            );
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().value() != HttpStatus.NOT_FOUND.value()
                    && exception.getStatusCode().value() != HttpStatus.GONE.value()) {
                throw exception;
            }
        }
    }

    private URI upstreamUri(String... pathSegments) {
        return UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                .pathSegment(pathSegments)
                .build()
                .encode()
                .toUri();
    }

    private String externalKey(AuthorizedBot bot) {
        String raw = bot.login() == null ? "" : bot.login();
        String key = raw.trim().replaceAll("\\s+", "") + "-" + bot.id();
        if (key.length() > 96) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Идентификатор браузерного профиля слишком длинный");
        }
        return key;
    }

    private CallerIdentity callerIdentity(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw notFound();
        }
        String username = authentication.getName().trim();
        String subject = username;
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication
                && jwtAuthentication.getToken().getSubject() != null
                && !jwtAuthentication.getToken().getSubject().isBlank()) {
            subject = jwtAuthentication.getToken().getSubject().trim();
        }
        if (username.length() > 255 || subject.length() > 512) {
            throw notFound();
        }
        boolean globalBrowserRole = authentication.getAuthorities().stream()
                .anyMatch(authority -> GLOBAL_BROWSER_ROLES.contains(authority.getAuthority()));
        return new CallerIdentity(username, subject, globalBrowserRole);
    }

    private void requireSessionOwner(BotBrowserSession session, long botId, CallerIdentity opener) {
        if (session.getBotId() == null
                || session.getBotId() != botId
                || !session.getOpenerSubject().equals(opener.subject())) {
            throw notFound();
        }
    }

    private String canonicalSessionId(String rawSessionId) {
        try {
            return UUID.fromString(rawSessionId == null ? "" : rawSessionId.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw notFound();
        }
    }

    private String requireHttpVncUrl(Object rawValue) {
        if (!(rawValue instanceof String value)
                || value.isBlank()
                || value.length() > MAX_VNC_URL_LENGTH
                || containsControlCharacter(value)
                || ENCODED_CONTROL.matcher(value).matches()) {
            throw upstreamFailure();
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!("http".equals(scheme.toLowerCase(Locale.ROOT)))
                    && !("https".equals(scheme.toLowerCase(Locale.ROOT))))
                    || !uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535) {
                throw upstreamFailure();
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw upstreamFailure();
        }
    }

    private boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || (character >= 0x7f && character <= 0x9f)) {
                return true;
            }
        }
        return false;
    }

    private String requireVncPassword(Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw upstreamFailure();
        }
        String password = value.trim();
        if (password.isEmpty() || password.length() > 128 || containsControlCharacter(password)) {
            throw upstreamFailure();
        }
        return password;
    }

    private String safeMetadata(Object rawValue, int maxLength) {
        if (!(rawValue instanceof String value)) {
            return null;
        }
        String clean = value.trim();
        if (clean.isEmpty() || clean.length() > maxLength || containsControlCharacter(clean)) {
            return null;
        }
        return clean;
    }

    private int heartbeatIntervalSeconds() {
        return Math.max(5, properties.getHeartbeatIntervalSeconds());
    }

    private int heartbeatTimeoutSeconds() {
        return Math.max(heartbeatIntervalSeconds() * 2 + 5, properties.getHeartbeatTimeoutSeconds());
    }

    private int sessionMaxSeconds() {
        return Math.max(heartbeatTimeoutSeconds(), properties.getSessionMaxSeconds());
    }

    private int openingTimeoutSeconds() {
        return Math.max(30, properties.getOpeningTimeoutSeconds());
    }

    private long stopRetryDelaySeconds(int attempts) {
        long base = Math.max(5, properties.getStopRetrySeconds());
        int exponent = Math.min(Math.max(0, attempts), 4);
        return Math.min(300, base * (1L << exponent));
    }

    private LocalDateTime earliest(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String sanitizedStopError(RuntimeException exception) {
        String value = "upstream_stop_failed:" + exception.getClass().getSimpleName();
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ресурс не найден");
    }

    private ResponseStatusException upstreamFailure() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Сервис браузера временно недоступен");
    }

    private record CallerIdentity(String username, String subject, boolean globalBrowserRole) {
    }
}
