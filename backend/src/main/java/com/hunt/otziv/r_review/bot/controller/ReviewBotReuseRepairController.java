package com.hunt.otziv.r_review.bot.controller;

import com.hunt.otziv.r_review.bot.service.ReviewBotReuseRepairService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/reviews/{reviewId}/account-reuse")
@PreAuthorize("hasRole('ADMIN')")
public class ReviewBotReuseRepairController {
    private final ReviewBotReuseRepairService service;

    @GetMapping
    public ReviewBotReuseRepairService.Preview preview(@PathVariable Long reviewId) {
        return service.preview(reviewId);
    }

    @PostMapping("/repair")
    public ReviewBotReuseRepairService.Preview repair(@PathVariable Long reviewId, @RequestBody RepairRequest request) {
        return service.repair(reviewId, request.expectedBotId(), request.expectedVersion());
    }

    public record RepairRequest(Long expectedBotId, long expectedVersion) { }
}
