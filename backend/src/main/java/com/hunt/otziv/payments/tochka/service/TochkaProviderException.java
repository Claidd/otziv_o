package com.hunt.otziv.payments.tochka.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class TochkaProviderException extends ResponseStatusException {

    private final boolean outcomeUnknown;

    public TochkaProviderException(String reason, boolean outcomeUnknown, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, reason, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }
}
