package com.hunt.otziv.whatsapp.service.service;

public interface WhatsAppService {
    String sendMessage(String clientId, String phone, String message);
}
