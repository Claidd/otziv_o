package com.hunt.otziv.u_users.dto;

import java.util.Set;

public record UserAssignmentsResponse(
        Long userId,
        Set<Long> managerIds,
        Set<Long> workerIds,
        Set<Long> operatorIds,
        Set<Long> marketologIds
) {
}
