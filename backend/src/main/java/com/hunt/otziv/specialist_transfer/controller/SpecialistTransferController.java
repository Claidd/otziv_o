package com.hunt.otziv.specialist_transfer.controller;

import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferAuditResponse;
import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferPreview;
import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferRequest;
import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferResult;
import com.hunt.otziv.specialist_transfer.service.SpecialistTransferService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/specialist-transfers")
public class SpecialistTransferController {

    private final SpecialistTransferService specialistTransferService;

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public SpecialistTransferPreview preview(
            @RequestBody SpecialistTransferRequest request,
            Authentication authentication
    ) {
        return specialistTransferService.preview(request, authentication);
    }

    @PostMapping("/apply")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public SpecialistTransferResult apply(
            @RequestBody SpecialistTransferRequest request,
            Authentication authentication
    ) {
        return specialistTransferService.apply(request, authentication);
    }

    @GetMapping("/recent")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public List<SpecialistTransferAuditResponse> recent(Authentication authentication) {
        return specialistTransferService.recent(authentication);
    }
}
