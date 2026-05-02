package com.hunt.otziv.u_users.dto;

import java.util.List;

public record AssignmentOptionsResponse(
        List<AssignmentOptionResponse> managers,
        List<AssignmentOptionResponse> workers,
        List<AssignmentOptionResponse> operators,
        List<AssignmentOptionResponse> marketologs
) {
}
