package com.hunt.otziv.gamification.controller;

import com.hunt.otziv.gamification.dto.GamificationMyProgressResponse;
import com.hunt.otziv.gamification.dto.GamificationRewardClaimRequest;
import com.hunt.otziv.gamification.dto.GamificationRewardClaimResponse;
import com.hunt.otziv.gamification.dto.GamificationRewardResponse;
import com.hunt.otziv.gamification.dto.GamificationWalletResponse;
import com.hunt.otziv.gamification.service.GamificationRewardService;
import com.hunt.otziv.gamification.service.GamificationUserProgressService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/gamification")
public class ApiGamificationController {

    private final GamificationUserProgressService userProgressService;
    private final GamificationRewardService rewardService;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public GamificationMyProgressResponse me(
            Principal principal,
            @RequestParam(value = "days", defaultValue = "7") int days
    ) {
        return userProgressService.myProgress(principal, days);
    }

    @GetMapping("/rewards")
    @PreAuthorize("isAuthenticated()")
    public List<GamificationRewardResponse> rewards(Principal principal) {
        return rewardService.catalog(principal);
    }

    @GetMapping("/wallet")
    @PreAuthorize("isAuthenticated()")
    public GamificationWalletResponse wallet(Principal principal) {
        return rewardService.wallet(principal);
    }

    @GetMapping("/reward-claims")
    @PreAuthorize("isAuthenticated()")
    public List<GamificationRewardClaimResponse> claims(Principal principal) {
        return rewardService.myClaims(principal);
    }

    @PostMapping("/rewards/{rewardId}/claim")
    @PreAuthorize("isAuthenticated()")
    public GamificationRewardClaimResponse claim(
            @PathVariable Long rewardId,
            @RequestBody(required = false) GamificationRewardClaimRequest request,
            Principal principal
    ) {
        return rewardService.claim(rewardId, request, principal);
    }
}
