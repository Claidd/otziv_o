package com.hunt.otziv.config.jwt.service;

import java.io.IOException;

public class BodyTooLargeException extends IOException {
    public BodyTooLargeException(String message) {
        super(message);
    }
}
