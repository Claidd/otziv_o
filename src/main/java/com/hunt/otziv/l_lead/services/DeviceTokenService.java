package com.hunt.otziv.l_lead.services;

import jakarta.servlet.http.HttpServletResponse;

public interface DeviceTokenService {
    String createDeviceToken(Long telephoneId, HttpServletResponse response);

    Long getTelephoneIdByToken(String token);
}
