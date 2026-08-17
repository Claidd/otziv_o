package com.hunt.otziv.config.api;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/** ResponseStatusException with a stable, non-sensitive API error code. */
public final class CodedResponseStatusException extends ResponseStatusException {

    private final String code;

    public CodedResponseStatusException(HttpStatusCode status, String code, String reason) {
        super(status, reason);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
