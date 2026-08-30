package com.hunt.otziv.payments.tochka.service;

public class TochkaWebhookVerificationException extends RuntimeException {

    public TochkaWebhookVerificationException(String message) {
        super(message);
    }

    public TochkaWebhookVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
