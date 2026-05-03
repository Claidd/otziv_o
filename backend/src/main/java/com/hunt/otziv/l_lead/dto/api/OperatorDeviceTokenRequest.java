package com.hunt.otziv.l_lead.dto.api;

import jakarta.validation.constraints.NotNull;

public record OperatorDeviceTokenRequest(
        @NotNull Long telephoneId
) {
}
