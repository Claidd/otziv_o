package com.hunt.otziv.b_bots.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.b_bots.dto.BrowserBotMetadataResponse;
import com.hunt.otziv.b_bots.dto.BrowserOpenRequest;
import com.hunt.otziv.b_bots.dto.BrowserOpenResponse;
import com.hunt.otziv.b_bots.service.BotBrowserAccessService;
import com.hunt.otziv.b_bots.service.BotBrowserAccessService.AuthorizedBot;
import com.hunt.otziv.b_bots.service.BotBrowserSessionService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class BotBrowserControllerTest {

    @Mock
    private BotBrowserAccessService accessService;

    @Mock
    private BotBrowserSessionService sessionService;

    private BotBrowserController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new BotBrowserController(accessService, sessionService);
        authentication = new TestingAuthenticationToken("worker", "n/a", "ROLE_WORKER");
    }

    @Test
    void metadataIsPasswordFreeAndNotCacheable() {
        when(accessService.requireAccess(7L, authentication))
                .thenReturn(new AuthorizedBot(7L, "79990000000", "Иванов И.И."));

        ResponseEntity<BrowserBotMetadataResponse> response =
                controller.getBrowserMetadata(7L, authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(new BrowserBotMetadataResponse(
                7L,
                "79990000000",
                "Иванов И.И."
        ));
    }

    @Test
    void openReturnsOpaqueLeaseContractAndNoStore() {
        BrowserOpenResponse open = new BrowserOpenResponse(
                "7c121c71-7bc4-4a25-a33c-78c7fe63e5c9",
                "https://vnc.example.test/session/secret-token",
                20,
                Instant.parse("2026-08-01T10:30:00Z")
        );
        when(sessionService.open(7L, authentication, true)).thenReturn(open);

        ResponseEntity<BrowserOpenResponse> response = controller.openBrowser(
                7L,
                new BrowserOpenRequest(true),
                authentication
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(open);
        verify(sessionService).open(7L, authentication, true);
    }

    @Test
    void heartbeatAndSessionCloseDelegateByOpaqueSessionId() {
        String sessionId = "7c121c71-7bc4-4a25-a33c-78c7fe63e5c9";

        ResponseEntity<Void> heartbeat = controller.heartbeatBrowserSession(7L, sessionId, authentication);
        ResponseEntity<Void> close = controller.closeBrowserSession(7L, sessionId, authentication);

        assertThat(heartbeat.getStatusCode().value()).isEqualTo(204);
        assertThat(close.getStatusCode().value()).isEqualTo(204);
        assertThat(heartbeat.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(close.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        verify(sessionService).heartbeat(7L, sessionId, authentication);
        verify(sessionService).close(7L, sessionId, authentication);
    }

    @Test
    void legacyCloseRemainsAServiceGuardedFallback() {
        ResponseEntity<Void> response = controller.closeBrowser(7L, authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(sessionService).closeLegacy(7L, authentication);
    }

    @Test
    void objectLevel404IsPreservedAndSessionOpenIsNotCalled() {
        ResponseStatusException denied = new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Ресурс не найден"
        );
        when(sessionService.open(404L, authentication, false)).thenThrow(denied);

        assertThatThrownBy(() -> controller.openBrowser(404L, null, authentication)).isSameAs(denied);

        verifyNoInteractions(accessService);
    }
}
