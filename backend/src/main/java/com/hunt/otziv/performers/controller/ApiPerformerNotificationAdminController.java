package com.hunt.otziv.performers.controller;

import com.hunt.otziv.performers.service.PerformerNotificationService;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/performers/notifications")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiPerformerNotificationAdminController {
    private final PerformerNotificationService notifications;

    @GetMapping
    public List<NotificationStatus> unresolved(@RequestParam(defaultValue = "50") int limit) {
        return notifications.unresolved(limit).stream().map(item -> new NotificationStatus(item.id(),
                item.assignmentId(), item.offerId(), item.type(), item.status(), item.attempts(),
                item.messageId(), item.outcome(), item.updatedAt())).toList();
    }

    @PostMapping("/{id}/resolve")
    public void resolve(@PathVariable Long id, @RequestBody Resolution request, Principal principal) {
        notifications.resolve(id, request.action(), request.messageId(), request.reason(),
                principal == null ? null : principal.getName());
    }

    public record Resolution(String action, Integer messageId, String reason) {}
    public record NotificationStatus(Long id, Long assignmentId, Long offerId, String type, String status,
                                     int attempts, Integer messageId, String outcome, LocalDateTime updatedAt) {}
}
