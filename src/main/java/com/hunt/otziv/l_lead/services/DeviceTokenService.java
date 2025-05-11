package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.dto.TelephoneIDAndTimeDTO;
import com.hunt.otziv.l_lead.dto.TextPhoneDTO;
import jakarta.servlet.http.HttpServletResponse;

public interface DeviceTokenService {
    String createDeviceToken(Long telephoneId, HttpServletResponse response);

    TelephoneIDAndTimeDTO getTelephoneIdByToken(String token);

    TextPhoneDTO getText(String token);
}
