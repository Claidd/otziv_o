package com.hunt.otziv.b_bots.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.b_bots.config.MultiBrowserProperties;
import com.hunt.otziv.b_bots.dto.BrowserOpenResponse;
import com.hunt.otziv.b_bots.model.BotBrowserSession;
import com.hunt.otziv.b_bots.model.BotBrowserSessionStatus;
import com.hunt.otziv.b_bots.repository.BotBrowserSessionRepository;
import com.hunt.otziv.b_bots.service.BotBrowserAccessService.AuthorizedBot;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class BotBrowserSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");
    private static final String SESSION_ID = "7c121c71-7bc4-4a25-a33c-78c7fe63e5c9";

    @Mock
    private BotBrowserSessionRepository repository;

    @Mock
    private BotBrowserAccessService accessService;

    @Mock
    private RestTemplate browserRestTemplate;

    private BotBrowserSessionService service;
    private MultiBrowserProperties properties;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        properties = new MultiBrowserProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://browser.internal");
        properties.setConnectionMode(MultiBrowserProperties.ConnectionMode.PROXY);
        properties.setProxyUrl("socks5://proxy.internal:1080");
        properties.setHeartbeatIntervalSeconds(20);
        properties.setHeartbeatTimeoutSeconds(75);
        properties.setSessionMaxSeconds(1800);
        service = new BotBrowserSessionService(
                repository,
                accessService,
                browserRestTemplate,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        authentication = authentication("worker");
    }

    @Test
    void openClaimsBeforeConnectRechecksAccessAndReturnsOnlyLeaseCapability() {
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "user /part ?", "ФИО"));
        AtomicReference<BotBrowserSession> stored = captureSavedSession();
        when(repository.markOpen(anyString(), eq(0L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(browserRestTemplate.postForEntity(any(URI.class), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "vncUrl", "https://vnc.example.test/session/secret-token",
                        "vncPassword", "aB3_xY9-",
                        "userAgent", "test-agent",
                        "platform", "Android",
                        "screenResolution", "1920x1080"
                )));

        BrowserOpenResponse response = service.open(7L, authentication);

        assertThat(response.vncUrl()).isEqualTo("https://vnc.example.test/session/secret-token");
        assertThat(response.vncPassword()).isEqualTo("aB3_xY9-");
        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.heartbeatIntervalSeconds()).isEqualTo(20);
        assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(response.botId()).isEqualTo(7L);
        assertThat(response.userAgent()).isEqualTo("test-agent");
        assertThat(response.platform()).isEqualTo("Android");
        assertThat(response.screenResolution()).isEqualTo("1920x1080");
        assertThat(stored.get().getExternalKeySnapshot()).isEqualTo("user/part?-7");
        assertThat(stored.get().getStatus()).isEqualTo(BotBrowserSessionStatus.OPENING);
        assertThat(BotBrowserSession.class.getDeclaredFields())
                .extracting(field -> field.getName().toLowerCase())
                .noneMatch(name -> name.contains("vnc") || name.contains("url"));
        verify(accessService, times(2)).requireAccess(7L, authentication);
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(browserRestTemplate).postForEntity(any(URI.class), request.capture(), eq(Map.class));
        Map<?, ?> requestBody = (Map<?, ?>) request.getValue().getBody();
        assertThat(requestBody.get("connectionMode")).isEqualTo("PROXY");
        assertThat(requestBody.get("proxyUrl")).isEqualTo("socks5://proxy.internal:1080");
    }

    @Test
    void directModeExplicitlyClearsAStaleProxyBeforeConnect() {
        properties.setConnectionMode(MultiBrowserProperties.ConnectionMode.DIRECT);
        properties.setProxyUrl("socks5://stale-proxy.invalid:1080");
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "direct-user", "ФИО"));
        captureSavedSession();
        when(repository.markOpen(anyString(), eq(0L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(browserRestTemplate.postForEntity(any(URI.class), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "vncUrl", "https://vnc.example.test/direct",
                        "vncPassword", "aB3_xY9-",
                        "userAgent", "test-agent",
                        "platform", "Android",
                        "screenResolution", "1920x1080"
                )));

        service.open(7L, authentication);

        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(browserRestTemplate).postForEntity(any(URI.class), request.capture(), eq(Map.class));
        Map<?, ?> requestBody = (Map<?, ?>) request.getValue().getBody();
        assertThat(requestBody.get("connectionMode")).isEqualTo("DIRECT");
        assertThat(requestBody.get("proxyUrl")).isEqualTo("");
    }

    @Test
    void uniqueActiveClaimStopsASecondConnectBeforeCallingUpstream() {
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "login", "ФИО"));
        when(repository.saveAndFlush(any(BotBrowserSession.class)))
                .thenThrow(new DataIntegrityViolationException("active bot"));

        assertThatThrownBy(() -> service.open(7L, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(browserRestTemplate);
    }

    @Test
    void legacyOpenWithoutHeartbeatCapabilityKeepsLeaseUntilAbsoluteExpiry() {
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "legacy-login", "ФИО"));
        AtomicReference<BotBrowserSession> stored = captureSavedSession();
        when(repository.markOpen(
                anyString(),
                eq(0L),
                eq(now()),
                eq(now().plusSeconds(1800))
        )).thenReturn(1);
        when(browserRestTemplate.postForEntity(any(URI.class), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "vncUrl", "https://vnc.example.test/legacy-session",
                        "vncPassword", "aB3_xY9-"
                )));

        service.open(7L, authentication, false);

        assertThat(stored.get().getHeartbeatExpiresAt())
                .isEqualTo(stored.get().getAbsoluteExpiresAt())
                .isEqualTo(now().plusSeconds(1800));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "https://vnc.example.test/session%0d%0aLocation:https://evil.test",
            "https://vnc.example.test/session%00",
            "https://vnc.example.test/session%7f"
    })
    void unsafePostConnectUrlTriggersBestEffortStopUsingImmutableSnapshot(String unsafeVncUrl) {
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "old-login", "ФИО"));
        AtomicReference<BotBrowserSession> stored = captureSavedSession();
        when(browserRestTemplate.postForEntity(any(URI.class), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("vncUrl", unsafeVncUrl)));
        AtomicInteger loads = new AtomicInteger();
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            BotBrowserSession opening = stored.get();
            return Optional.of(loads.getAndIncrement() == 0 ? opening : closingCopy(opening));
        });
        when(repository.beginClosing(anyString(), eq(BotBrowserSessionStatus.OPENING), eq(0L), any()))
                .thenReturn(1);
        when(repository.markClosed(anyString(), eq(1L), any())).thenReturn(1);

        assertThatThrownBy(() -> service.open(7L, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        ArgumentCaptor<URI> stoppedUri = ArgumentCaptor.forClass(URI.class);
        verify(browserRestTemplate).postForLocation(stoppedUri.capture(), any(HttpEntity.class));
        assertThat(stoppedUri.getValue().getRawPath())
                .endsWith("/integration/profiles/old-login-7/stop");
        verify(repository).markClosed(anyString(), eq(1L), any());
    }

    @Test
    void reassignmentDetectedAfterConnectRevokesCapabilityAndStopsSnapshot() {
        ResponseStatusException reassigned = new ResponseStatusException(HttpStatus.NOT_FOUND, "Ресурс не найден");
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "before-reassignment", "ФИО"))
                .thenThrow(reassigned);
        AtomicReference<BotBrowserSession> stored = captureSavedSession();
        when(browserRestTemplate.postForEntity(any(URI.class), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "vncUrl", "https://vnc.example.test/must-not-leak"
                )));
        AtomicInteger loads = new AtomicInteger();
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            BotBrowserSession opening = stored.get();
            return Optional.of(loads.getAndIncrement() == 0 ? opening : closingCopy(opening));
        });
        when(repository.beginClosing(anyString(), eq(BotBrowserSessionStatus.OPENING), eq(0L), any()))
                .thenReturn(1);
        when(repository.markClosed(anyString(), eq(1L), any())).thenReturn(1);

        assertThatThrownBy(() -> service.open(7L, authentication))
                .isSameAs(reassigned);

        verify(browserRestTemplate).postForLocation(any(URI.class), any(HttpEntity.class));
        verify(repository, never()).markOpen(anyString(), anyLong(), any(), any());
    }

    @Test
    void heartbeatRequiresFreshAccessAndSameOpener() {
        BotBrowserSession session = openSession("worker");
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "current-login", "ФИО"));
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(repository.heartbeat(
                eq(SESSION_ID),
                eq(7L),
                eq("worker"),
                eq(3L),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(1);

        service.heartbeat(7L, SESSION_ID, authentication);

        verify(accessService).requireAccess(7L, authentication);
        verify(repository).heartbeat(
                eq(SESSION_ID),
                eq(7L),
                eq("worker"),
                eq(3L),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        );
    }

    @Test
    void expiredHeartbeatCannotReviveLeaseBeforeSweeperRuns() {
        BotBrowserSession expired = openSession("worker");
        expired.setHeartbeatExpiresAt(now().minusSeconds(1));
        BotBrowserSession closing = closingCopy(expired);
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "current-login", "ФИО"));
        when(repository.findById(SESSION_ID))
                .thenReturn(Optional.of(expired), Optional.of(closing));
        when(repository.beginClosing(SESSION_ID, BotBrowserSessionStatus.OPEN, 3L, now()))
                .thenReturn(1);
        when(repository.markClosed(SESSION_ID, 4L, now())).thenReturn(1);

        assertThatThrownBy(() -> service.heartbeat(7L, SESSION_ID, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).heartbeat(
                anyString(), anyLong(), anyString(), anyLong(), any(), any()
        );
        verify(browserRestTemplate).postForLocation(any(URI.class), any(HttpEntity.class));
    }

    @Test
    void postConnectConflictDoesNotReclaimAnInFlightClosingStop() {
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "old-login", "ФИО"));
        AtomicReference<BotBrowserSession> stored = captureSavedSession();
        when(browserRestTemplate.postForEntity(any(URI.class), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "vncUrl", "https://vnc.example.test/session",
                        "vncPassword", "aB3_xY9-"
                )));
        when(repository.markOpen(anyString(), eq(0L), any(), any())).thenReturn(0);
        when(repository.findById(anyString()))
                .thenAnswer(invocation -> Optional.of(closingCopy(stored.get())));

        assertThatThrownBy(() -> service.open(7L, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).beginClosing(anyString(), any(), anyLong(), any());
        verify(browserRestTemplate, never()).postForLocation(any(URI.class), any(HttpEntity.class));
    }

    @Test
    void closeBySessionStillWorksAfterReassignmentAndStopsSnapshot() {
        BotBrowserSession open = openSession("worker");
        open.setExternalKeySnapshot("immutable-old-login-7");
        BotBrowserSession closing = closingCopy(open);
        when(repository.findById(SESSION_ID))
                .thenReturn(Optional.of(open), Optional.of(closing));
        when(repository.beginClosing(SESSION_ID, BotBrowserSessionStatus.OPEN, 3L, now()))
                .thenReturn(1);
        when(repository.markClosed(SESSION_ID, 4L, now())).thenReturn(1);

        service.close(7L, SESSION_ID, authentication);

        verifyNoInteractions(accessService);
        ArgumentCaptor<URI> stoppedUri = ArgumentCaptor.forClass(URI.class);
        verify(browserRestTemplate).postForLocation(stoppedUri.capture(), any(HttpEntity.class));
        assertThat(stoppedUri.getValue().getRawPath())
                .endsWith("/integration/profiles/immutable-old-login-7/stop");
    }

    @Test
    void providerNotFoundStillCompletesIdempotentClose() {
        BotBrowserSession open = openSession("worker");
        BotBrowserSession closing = closingCopy(open);
        when(repository.findById(SESSION_ID))
                .thenReturn(Optional.of(open), Optional.of(closing));
        when(repository.beginClosing(SESSION_ID, BotBrowserSessionStatus.OPEN, 3L, now()))
                .thenReturn(1);
        when(browserRestTemplate.postForLocation(any(URI.class), any(HttpEntity.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        when(repository.markClosed(SESSION_ID, 4L, now())).thenReturn(1);

        service.close(7L, SESSION_ID, authentication);

        verify(repository).markClosed(SESSION_ID, 4L, now());
        verify(repository, never()).markStopRetry(anyString(), anyLong(), any(), any(), anyString());
    }

    @Test
    void legacyCloseAllowsFreshAuthorizedGlobalRoleToStopAnotherOpenersTrackedSession() {
        Authentication admin = authentication("admin", "ROLE_ADMIN");
        BotBrowserSession open = openSession("other-subject");
        BotBrowserSession closing = closingCopy(open);
        when(accessService.requireAccess(7L, admin))
                .thenReturn(new AuthorizedBot(7L, "current-login", "ФИО"));
        when(repository.findFirstByBotIdAndStatusInOrderByCreatedAtDesc(eq(7L), any()))
                .thenReturn(Optional.of(open));
        when(repository.beginClosing(SESSION_ID, BotBrowserSessionStatus.OPEN, 3L, now()))
                .thenReturn(1);
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(closing));
        when(repository.markClosed(SESSION_ID, 4L, now())).thenReturn(1);

        service.closeLegacy(7L, admin);

        verify(accessService).requireAccess(7L, admin);
        verify(browserRestTemplate).postForLocation(any(URI.class), any(HttpEntity.class));
    }

    @Test
    void legacyWorkerCannotStopAnotherOpenersTrackedSession() {
        BotBrowserSession open = openSession("other-subject");
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "current-login", "ФИО"));
        when(repository.findFirstByBotIdAndStatusInOrderByCreatedAtDesc(eq(7L), any()))
                .thenReturn(Optional.of(open));

        assertThatThrownBy(() -> service.closeLegacy(7L, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(browserRestTemplate, never()).postForLocation(any(URI.class), any(HttpEntity.class));
    }

    @Test
    void stopFailureLeavesConditionalRetryStateWithoutLeakingProviderDetails() {
        BotBrowserSession retry = openSession("worker");
        retry.setStatus(BotBrowserSessionStatus.STOP_RETRY);
        retry.setVersion(8L);
        retry.setStopAttempts(1);
        BotBrowserSession closing = closingCopy(retry);
        closing.setVersion(9L);
        when(repository.findSweepCandidates(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(retry));
        when(repository.beginClosing(
                SESSION_ID,
                BotBrowserSessionStatus.STOP_RETRY,
                8L,
                now()
        )).thenReturn(1);
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(closing));
        when(browserRestTemplate.postForLocation(any(URI.class), any(HttpEntity.class)))
                .thenThrow(new RestClientException("secret provider response"));
        when(repository.markStopRetry(
                eq(SESSION_ID),
                eq(9L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyString()
        )).thenReturn(1);

        service.sweepExpiredSessions();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(repository).markStopRetry(
                eq(SESSION_ID),
                eq(9L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                error.capture()
        );
        assertThat(error.getValue())
                .isEqualTo("upstream_stop_failed:RestClientException")
                .doesNotContain("secret", "provider response");
    }

    @Test
    void secondSweeperThatLosesVersionClaimDoesNotDuplicateProviderStop() {
        BotBrowserSession candidate = openSession("worker");
        when(repository.findSweepCandidates(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(repository.beginClosing(
                SESSION_ID,
                BotBrowserSessionStatus.OPEN,
                3L,
                now()
        )).thenReturn(0);

        service.sweepExpiredSessions();

        verify(browserRestTemplate, never()).postForLocation(any(URI.class), any(HttpEntity.class));
        verify(repository, never()).markClosed(anyString(), anyLong(), any(LocalDateTime.class));
        verify(repository, never()).markStopRetry(
                anyString(),
                anyLong(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyString()
        );
    }

    private AtomicReference<BotBrowserSession> captureSavedSession() {
        AtomicReference<BotBrowserSession> stored = new AtomicReference<>();
        when(repository.saveAndFlush(any(BotBrowserSession.class))).thenAnswer(invocation -> {
            BotBrowserSession session = invocation.getArgument(0);
            session.setVersion(0L);
            stored.set(session);
            return session;
        });
        return stored;
    }

    private BotBrowserSession openSession(String subject) {
        BotBrowserSession session = new BotBrowserSession();
        session.setSessionId(SESSION_ID);
        session.setBotId(7L);
        session.setExternalKeySnapshot("old-login-7");
        session.setOpenerUsername("worker");
        session.setOpenerSubject(subject);
        session.setStatus(BotBrowserSessionStatus.OPEN);
        session.setCreatedAt(now().minusMinutes(1));
        session.setUpdatedAt(now().minusMinutes(1));
        session.setLastHeartbeatAt(now().minusSeconds(10));
        session.setHeartbeatExpiresAt(now().plusSeconds(65));
        session.setAbsoluteExpiresAt(now().plusMinutes(20));
        session.setVersion(3L);
        return session;
    }

    private BotBrowserSession closingCopy(BotBrowserSession source) {
        BotBrowserSession closing = new BotBrowserSession();
        closing.setSessionId(source.getSessionId());
        closing.setBotId(source.getBotId());
        closing.setExternalKeySnapshot(source.getExternalKeySnapshot());
        closing.setOpenerUsername(source.getOpenerUsername());
        closing.setOpenerSubject(source.getOpenerSubject());
        closing.setStatus(BotBrowserSessionStatus.CLOSING);
        closing.setCreatedAt(source.getCreatedAt());
        closing.setUpdatedAt(now());
        closing.setLastHeartbeatAt(source.getLastHeartbeatAt());
        closing.setHeartbeatExpiresAt(source.getHeartbeatExpiresAt());
        closing.setAbsoluteExpiresAt(source.getAbsoluteExpiresAt());
        closing.setStopAttempts(source.getStopAttempts());
        closing.setVersion(source.getVersion() + 1);
        return closing;
    }

    private Authentication authentication(String username) {
        return authentication(username, "ROLE_WORKER");
    }

    private Authentication authentication(String username, String... roles) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(username, "n/a", roles);
        token.setAuthenticated(true);
        return token;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }
}
