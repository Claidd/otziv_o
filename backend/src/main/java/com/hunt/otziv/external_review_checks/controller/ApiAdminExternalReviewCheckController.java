package com.hunt.otziv.external_review_checks.controller;

import com.hunt.otziv.external_review_checks.dto.ExternalReviewCheckEnabledUpdateRequest;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewCheckStatusResponse;
import com.hunt.otziv.external_review_checks.service.ExternalReviewCheckRuntimeSwitch;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/external-review-checks")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
@RequiredArgsConstructor
public class ApiAdminExternalReviewCheckController {

    private final ExternalReviewCheckRuntimeSwitch runtimeSwitch;

    @GetMapping("/status")
    public ExternalReviewCheckStatusResponse status() {
        return response(runtimeSwitch.status());
    }

    @PutMapping("/status/enabled")
    public ExternalReviewCheckStatusResponse setEnabled(
            @RequestBody ExternalReviewCheckEnabledUpdateRequest request
    ) {
        if (request == null || request.enabled() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Не передано состояние внешней проверки отзывов"
            );
        }
        return response(runtimeSwitch.setOperatorEnabled(request.enabled()));
    }

    private ExternalReviewCheckStatusResponse response(ExternalReviewCheckRuntimeSwitch.Status status) {
        return new ExternalReviewCheckStatusResponse(
                status.enabled(),
                status.hardEnabled(),
                status.operatorEnabled()
        );
    }
}
