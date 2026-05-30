package com.hunt.otziv.archive.controller;

import com.hunt.otziv.archive.dto.ArchiveRestoreResult;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderDetailsResponse;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderListItem;
import com.hunt.otziv.archive.exception.ArchiveRestoreConflictException;
import com.hunt.otziv.archive.service.ManagerArchiveService;
import com.hunt.otziv.manager.dto.api.PageResponse;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/manager/archive")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
public class ApiManagerArchiveController {

    private final ManagerArchiveService managerArchiveService;

    @GetMapping("/orders")
    public PageResponse<ManagerArchiveOrderListItem> findOrders(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "all") String mode,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "desc") String sortDirection,
            Principal principal,
            Authentication authentication
    ) {
        return managerArchiveService.findOrders(keyword, mode, pageNumber, pageSize, sortDirection, principal, authentication);
    }

    @GetMapping("/orders/{orderId}")
    public ManagerArchiveOrderDetailsResponse getOrder(
            @PathVariable Long orderId,
            Principal principal,
            Authentication authentication
    ) {
        return managerArchiveService.getOrder(orderId, principal, authentication);
    }

    @PostMapping("/orders/{orderId}/restore")
    public ArchiveRestoreResult restoreOrder(
            @PathVariable Long orderId,
            @RequestParam(required = false) String targetStatus,
            @RequestParam(defaultValue = "false") boolean confirm,
            Principal principal,
            Authentication authentication
    ) {
        try {
            return managerArchiveService.restoreOrder(orderId, targetStatus, confirm, principal, authentication);
        } catch (ArchiveRestoreConflictException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, exception.getMessage(), exception);
        }
    }
}
