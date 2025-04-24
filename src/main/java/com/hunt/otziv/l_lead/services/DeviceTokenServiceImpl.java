package com.hunt.otziv.l_lead.services;


import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.DeviceTokenRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.l_lead.model.DeviceToken;
import com.hunt.otziv.l_lead.dto.TelephoneIDAndTimeDTO;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenServiceImpl implements DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final TelephoneRepository telephoneRepository;


    public String createDeviceToken(Long telephoneId, HttpServletResponse response) {
        Telephone tel = telephoneRepository.findById(telephoneId)
                .orElseThrow(() -> new RuntimeException("Телефон не найден"));

        String token = UUID.randomUUID().toString();

        DeviceToken deviceToken = DeviceToken.builder()
                .token(token)
                .telephone(tel)
                .createdAt(LocalDateTime.now())
                .active(true)
                .build();

        deviceTokenRepository.save(deviceToken);

        Cookie cookie = new Cookie("device_token", token);
        cookie.setHttpOnly(true); // ← временно, для JS-доступа
        cookie.setPath("/");
        cookie.setMaxAge(365 * 24 * 60 * 60); // 1 год
        response.addCookie(cookie);

        return token;
    }

    public TelephoneIDAndTimeDTO getTelephoneIdByToken(String token) {
        return deviceTokenRepository.findByToken(token)
                .map(deviceToken -> toDto(deviceToken.getTelephone()))
                .orElse(null);
    }

    private TelephoneIDAndTimeDTO toDto(Telephone telephone) {
        return TelephoneIDAndTimeDTO.builder()
                .telephoneID(telephone.getId())
                .time(telephone.getTimer())
                .build();
    }

}
