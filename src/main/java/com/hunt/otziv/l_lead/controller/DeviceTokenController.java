package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.services.serv.DeviceTokenService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/telephone")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping("/device-token")
    public ResponseEntity<?> createToken(@RequestParam Long telephoneId, HttpServletResponse response) {
        try {
            deviceTokenService.createDeviceToken(telephoneId, response);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            e.printStackTrace(); // ← покажет, что именно произошло
            return ResponseEntity.badRequest().body("Не удалось найти телефон");
        }
    }
}
