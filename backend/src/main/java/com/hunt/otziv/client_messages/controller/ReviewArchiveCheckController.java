package com.hunt.otziv.client_messages.controller;

import com.hunt.otziv.client_messages.api.ReviewReminderClosure;
import com.hunt.otziv.client_messages.service.ReviewArchiveCheckMonitor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/client-messages/archive-checks")
@PreAuthorize("hasAnyRole('OWNER','ADMIN')")
public class ReviewArchiveCheckController {
    private final ReviewArchiveCheckMonitor monitor;
    private final ReviewReminderClosure closure;
    @GetMapping public List<ReviewArchiveCheckMonitor.Row> snapshot() {return monitor.snapshot();}
    @PostMapping("/{stateId}/check") public ReviewReminderClosure.Decision check(@PathVariable long stateId) {
        return closure.archiveAfterReminders(stateId);
    }
}
