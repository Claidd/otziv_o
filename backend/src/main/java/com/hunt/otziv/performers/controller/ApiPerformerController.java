package com.hunt.otziv.performers.controller;

import com.hunt.otziv.performers.dto.PerformerAssignmentResponse;
import com.hunt.otziv.performers.dto.PerformerBoardResponse;
import com.hunt.otziv.performers.dto.PerformerProblemRequest;
import com.hunt.otziv.performers.dto.PerformerPublishRequest;
import com.hunt.otziv.performers.service.PerformerAssignmentService;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/performer")
public class ApiPerformerController {

    private final PerformerAssignmentService assignmentService;

    @GetMapping("/board")
    public PerformerBoardResponse board(Principal principal) {
        return assignmentService.board(principal.getName());
    }

    @PostMapping("/offers/{offerId}/accept")
    public PerformerAssignmentResponse acceptOffer(@PathVariable Long offerId, Principal principal) {
        return assignmentService.acceptOffer(offerId, principal.getName());
    }

    @PostMapping("/offers/{offerId}/decline")
    public void declineOffer(@PathVariable Long offerId, Principal principal) {
        assignmentService.declineOffer(offerId, principal.getName(), "Отказ из кабинета");
    }

    @PostMapping("/assignments/{assignmentId}/walked")
    public PerformerAssignmentResponse walked(@PathVariable Long assignmentId, Principal principal) {
        return assignmentService.markWalked(assignmentId, principal.getName());
    }

    @PostMapping("/assignments/{assignmentId}/published")
    public PerformerAssignmentResponse published(
            @PathVariable Long assignmentId,
            @Valid @RequestBody PerformerPublishRequest request,
            Principal principal
    ) {
        return assignmentService.markPublished(assignmentId, principal.getName(), request);
    }

    @PostMapping("/assignments/{assignmentId}/problem")
    public PerformerAssignmentResponse problem(
            @PathVariable Long assignmentId,
            @Valid @RequestBody PerformerProblemRequest request,
            Principal principal
    ) {
        return assignmentService.reportProblem(assignmentId, principal.getName(), request);
    }
}
