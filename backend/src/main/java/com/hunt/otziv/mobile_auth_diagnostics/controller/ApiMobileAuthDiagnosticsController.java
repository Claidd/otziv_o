package com.hunt.otziv.mobile_auth_diagnostics.controller;

import com.hunt.otziv.mobile_auth_diagnostics.dto.MobileAuthDiagnosticBatchRequest;
import com.hunt.otziv.mobile_auth_diagnostics.service.MobileAuthDiagnosticLogService;
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
@RequestMapping("/api/mobile/auth-diagnostics")
@PreAuthorize("isAuthenticated()")
public class ApiMobileAuthDiagnosticsController {

    private final MobileAuthDiagnosticLogService diagnosticLogService;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ingest(
            HttpServletRequest servletRequest,
            Principal principal,
            @Valid @RequestBody MobileAuthDiagnosticBatchRequest request
    ) {
        servletRequest.setAttribute("mobileDiagnosticEventCount", request.events().size());
        diagnosticLogService.logBatch(principal, servletRequest, request);
    }
}
