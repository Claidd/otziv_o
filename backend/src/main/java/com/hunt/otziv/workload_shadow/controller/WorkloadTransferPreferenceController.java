package com.hunt.otziv.workload_shadow.controller;

import com.hunt.otziv.workload_shadow.dto.WorkloadTransferPreferenceRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadTransferPreferenceResponse;
import com.hunt.otziv.workload_shadow.service.WorkloadTransferPreferenceService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workload-shadow/preferences/me")
@PreAuthorize("hasRole('WORKER')")
public class WorkloadTransferPreferenceController {

    private final WorkloadTransferPreferenceService preferenceService;

    @GetMapping
    public WorkloadTransferPreferenceResponse current(Principal principal) {
        return preferenceService.current(principal == null ? null : principal.getName());
    }

    @PutMapping
    public WorkloadTransferPreferenceResponse update(
            Principal principal,
            @RequestBody WorkloadTransferPreferenceRequest request
    ) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Настройка получения компаний не передана"
            );
        }
        return preferenceService.update(
                principal == null ? null : principal.getName(),
                request.acceptsCompanyTransfers()
        );
    }
}
