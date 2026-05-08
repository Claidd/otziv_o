package com.hunt.otziv.whatsapp.exception;

public class WhatsAppConfigurationException extends RuntimeException {

    private final String code;

    public WhatsAppConfigurationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
