package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.whatsapp.service.LeadSenderServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/dispatch")
public class ManualDispatchController {

    private final LeadSenderServiceImpl leadSenderService;

    @PostMapping("/start")
    public ResponseEntity<String> startManualDispatch() {
        leadSenderService.startDailyDispatch();
        return ResponseEntity.ok("✅ Рассылка запущена вручную");
    }

    @GetMapping("/start")
    public ResponseEntity<String> testStart() {
        leadSenderService.startDailyDispatch();
        return ResponseEntity.ok("✅ Рассылка запущена через GET");
    }

}
