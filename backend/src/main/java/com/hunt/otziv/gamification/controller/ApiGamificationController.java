package com.hunt.otziv.gamification.controller;

import com.hunt.otziv.gamification.dto.GamificationMyProgressResponse;
import com.hunt.otziv.gamification.service.GamificationUserProgressService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/gamification")
public class ApiGamificationController {

    private final GamificationUserProgressService userProgressService;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public GamificationMyProgressResponse me(
            Principal principal,
            @RequestParam(value = "days", defaultValue = "7") int days
    ) {
        return userProgressService.myProgress(principal, days);
    }
}
