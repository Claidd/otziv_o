package com.hunt.otziv.mobile_push.controller;

import com.hunt.otziv.mobile_push.dto.MobilePushSendResponse;
import com.hunt.otziv.mobile_push.dto.MobilePushTestRequest;
import com.hunt.otziv.mobile_push.dto.MobilePushTokenRequest;
import com.hunt.otziv.mobile_push.dto.MobilePushTokenRevokeRequest;
import com.hunt.otziv.mobile_push.service.MobilePushTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/push-token")
@PreAuthorize("isAuthenticated()")
public class ApiMobilePushTokenController {

    private final MobilePushTokenService tokenService;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(
            HttpServletRequest servletRequest,
            Principal principal,
            @Valid @RequestBody MobilePushTokenRequest request
    ) {
        servletRequest.setAttribute("platform", request.platform());
        tokenService.register(principal, request);
    }

    @PostMapping("/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeCurrent(
            Principal principal,
            @Valid @RequestBody MobilePushTokenRevokeRequest request
    ) {
        tokenService.revokeCurrent(principal, request);
    }

    @PostMapping("/revoke-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAll(Principal principal) {
        tokenService.revokeAll(principal);
    }

    @PostMapping("/test")
    public MobilePushSendResponse sendTest(
            Principal principal,
            @RequestBody(required = false) MobilePushTestRequest request
    ) {
        return tokenService.sendTest(principal, request);
    }
}
