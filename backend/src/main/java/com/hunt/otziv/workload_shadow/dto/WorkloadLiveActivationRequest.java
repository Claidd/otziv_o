package com.hunt.otziv.workload_shadow.dto;

public record WorkloadLiveActivationRequest(
        String mode,
        String confirmation,
        Long revision
) {
}
