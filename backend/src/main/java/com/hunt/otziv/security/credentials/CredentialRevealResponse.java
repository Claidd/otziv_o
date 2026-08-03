package com.hunt.otziv.security.credentials;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hunt.otziv.worker_activity.dto.WorkerCredentialPreparationResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CredentialRevealResponse(
        String value,
        WorkerCredentialPreparationResponse credentialPreparation
) {

    public CredentialRevealResponse(String value) {
        this(value, null);
    }

    public CredentialRevealResponse withCredentialPreparation(
            WorkerCredentialPreparationResponse preparation
    ) {
        return new CredentialRevealResponse(value, preparation);
    }
}
