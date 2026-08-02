package com.hunt.otziv.b_bots.controller;

import com.hunt.otziv.b_bots.dto.BrowserBotMetadataResponse;
import com.hunt.otziv.b_bots.dto.BrowserOpenRequest;
import com.hunt.otziv.b_bots.dto.BrowserOpenResponse;
import com.hunt.otziv.b_bots.services.BotBrowserAccessService;
import com.hunt.otziv.b_bots.services.BotBrowserAccessService.AuthorizedBot;
import com.hunt.otziv.b_bots.services.BotBrowserSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
public class BotBrowserController {

    private final BotBrowserAccessService accessService;
    private final BotBrowserSessionService sessionService;

    @GetMapping("/{botId}/browser/metadata")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ResponseEntity<BrowserBotMetadataResponse> getBrowserMetadata(
            @PathVariable Long botId,
            Authentication authentication
    ) {
        AuthorizedBot bot = accessService.requireAccess(botId, authentication);
        BrowserBotMetadataResponse response = new BrowserBotMetadataResponse(
                bot.id(),
                bot.login(),
                bot.fio()
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/{botId}/browser/open")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ResponseEntity<BrowserOpenResponse> openBrowser(
            @PathVariable Long botId,
            @RequestBody(required = false) BrowserOpenRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sessionService.open(
                        botId,
                        authentication,
                        request != null && request.supportsHeartbeat()
                ));
    }

    @PostMapping("/{botId}/browser/sessions/{sessionId}/heartbeat")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ResponseEntity<Void> heartbeatBrowserSession(
            @PathVariable Long botId,
            @PathVariable String sessionId,
            Authentication authentication
    ) {
        sessionService.heartbeat(botId, sessionId, authentication);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping("/{botId}/browser/sessions/{sessionId}/close")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ResponseEntity<Void> closeBrowserSession(
            @PathVariable Long botId,
            @PathVariable String sessionId,
            Authentication authentication
    ) {
        sessionService.close(botId, sessionId, authentication);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    /**
     * Backward-compatible fallback for already deployed clients. New clients
     * close by opaque session ID so reassignment cannot redirect stop to a new
     * browser profile.
     */
    @PostMapping("/{botId}/browser/close")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ResponseEntity<Void> closeBrowser(
            @PathVariable Long botId,
            Authentication authentication
    ) {
        sessionService.closeLegacy(botId, authentication);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
