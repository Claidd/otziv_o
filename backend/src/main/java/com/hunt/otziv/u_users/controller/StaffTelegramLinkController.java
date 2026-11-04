package com.hunt.otziv.u_users.controller;

import com.hunt.otziv.u_users.service.StaffTelegramLinkService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Issues a one-time deep link that binds the caller's private Telegram chat to the caller's account. */
@RestController
@RequestMapping("/api/me/telegram-link")
@RequiredArgsConstructor
public class StaffTelegramLinkController {

    private final StaffTelegramLinkService linkService;

    @PostMapping
    public ResponseEntity<StaffTelegramLinkService.IssuedLink> issue(Principal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(linkService.issue(principal.getName()));
    }
}
