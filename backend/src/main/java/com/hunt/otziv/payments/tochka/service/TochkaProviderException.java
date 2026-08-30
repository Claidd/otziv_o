package com.hunt.otziv.payments.tochka.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public class TochkaProviderException extends ResponseStatusException {

    private final boolean outcomeUnknown;

    public TochkaProviderException(String reason, boolean outcomeUnknown, Throwable cause) {
        this(HttpStatus.BAD_GATEWAY, reason, outcomeUnknown, cause);
    }

    public TochkaProviderException(
            HttpStatusCode status,
            String reason,
            boolean outcomeUnknown,
            Throwable cause
    ) {
        super(status, reason, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }
}
