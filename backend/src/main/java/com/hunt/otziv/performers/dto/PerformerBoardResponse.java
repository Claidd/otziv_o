package com.hunt.otziv.performers.dto;

import java.util.List;

public record PerformerBoardResponse(
        List<PerformerAssignmentResponse> offers,
        List<PerformerAssignmentResponse> active,
        List<PerformerAssignmentResponse> waitingPublication,
        List<PerformerAssignmentResponse> published,
        List<PerformerAssignmentResponse> paid
) {
}
