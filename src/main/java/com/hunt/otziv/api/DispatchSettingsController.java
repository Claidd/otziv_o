package com.hunt.otziv.api;

import com.hunt.otziv.l_lead.model.DispatchSettings;
import com.hunt.otziv.l_lead.repository.DispatchSettingsRepository;
import com.hunt.otziv.l_lead.utils.DynamicDispatchScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/dispatch-settings")
@RequiredArgsConstructor
public class DispatchSettingsController {

    private final DispatchSettingsRepository repository;

    @GetMapping("/cron")
    public ResponseEntity<String> getCron() {
        return ResponseEntity.ok(
                repository.findById(1L).map(DispatchSettings::getCronExpression).orElse("0 0 14 * * *")
        );
    }

//    @PostMapping("/dispatch-now")
//    public ResponseEntity<String> runNow() {
//        dynamicDispatchScheduler.scheduleInitialTask();
//        return ResponseEntity.ok("Запуск запланирован");
//    }
}

