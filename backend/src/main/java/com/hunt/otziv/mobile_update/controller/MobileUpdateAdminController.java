package com.hunt.otziv.mobile_update.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/mobile-update")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class MobileUpdateAdminController {

    @PostMapping
    public void publish() {
        // The old installed APKs read this channel. A replacement signer must
        // never be served here because Android cannot install it over them.
        throw new ResponseStatusException(
                HttpStatus.GONE,
                "Публикация в прежнем канале закрыта после смены ключа подписи. Новое приложение: /android-new/"
        );
    }
}
