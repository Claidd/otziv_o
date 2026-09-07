package com.hunt.otziv.monitoring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="otziv.monitoring.enabled",havingValue="true")
public class MonitoringRuntimeController {
    private final MonitoringRuntimeService service;
    public MonitoringRuntimeController(MonitoringRuntimeService service) {this.service=service;}
    @GetMapping(MonitoringSecurityConfiguration.PATH)
    public MonitoringRuntimeService.Snapshot snapshot() {return service.snapshot();}
}
