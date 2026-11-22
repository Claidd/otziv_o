package com.hunt.otziv.archive.controller;

import com.hunt.otziv.archive.dto.ArchiveRestoreResult;
import com.hunt.otziv.archive.dto.ArchiveRestoreSpecialistRequiredResponse;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderDetailsResponse;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderListItem;
import com.hunt.otziv.archive.exception.ArchiveRestoreConflictException;
import com.hunt.otziv.archive.exception.ArchiveRestoreSpecialistRequiredException;
import com.hunt.otziv.archive.service.ManagerArchiveService;
import com.hunt.otziv.manager.dto.api.PageResponse;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
            @RequestParam(required = false) Long targetWorkerId,
            @RequestParam(defaultValue = "false") boolean specialistChoice,
            Principal principal,
            Authentication authentication
    ) {
        try {
            // specialistChoice: the client can show the specialist list (the site does; the released Android app does
            // not yet). Without it the order keeps its archived specialist, as before, so the app is not blocked.
            return managerArchiveService.restoreOrder(orderId, targetStatus, targetWorkerId,
                    specialistChoice || targetWorkerId != null, confirm, principal, authentication);
        } catch (ArchiveRestoreSpecialistRequiredException exception) {
            // Answered by specialistRequired() with the list to choose from, not as a bare conflict.
            throw exception;
        } catch (ArchiveRestoreConflictException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, exception.getMessage(), exception);
        }
    }

    /**
     * The restore window asks for a new specialist in place, so the conflict carries a stable
     * code and the specialists the server will accept, on top of the usual message.
     */
    @ExceptionHandler(ArchiveRestoreSpecialistRequiredException.class)
    public ResponseEntity<ArchiveRestoreSpecialistRequiredResponse> specialistRequired(
            ArchiveRestoreSpecialistRequiredException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ArchiveRestoreSpecialistRequiredResponse(
                exception.getMessage(),
                ArchiveRestoreSpecialistRequiredException.CODE,
                exception.workers()
        ));
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/recovery-tasks")
    public ManagerArchiveOrderDetailsResponse createReviewRecoveryTask(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestParam(required = false) Long workerId,
            Principal principal,
            Authentication authentication
    ) {
        return managerArchiveService.createReviewRecoveryTask(orderId, reviewId, workerId, principal, authentication);
    }

    @PostMapping("/orders/{orderId}/recovery-tasks/worker")
    public ManagerArchiveOrderDetailsResponse reassignReviewRecoveryTasks(
            @PathVariable Long orderId,
            @RequestParam Long workerId,
            @RequestParam(required = false) Long taskId,
            Principal principal,
            Authentication authentication
    ) {
        return managerArchiveService.reassignReviewRecoveryTasks(orderId, workerId, taskId, principal, authentication);
    }
}
