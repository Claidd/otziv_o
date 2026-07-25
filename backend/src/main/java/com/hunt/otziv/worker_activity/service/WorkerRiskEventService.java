package com.hunt.otziv.worker_activity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.worker_activity.model.WorkerRiskEvent;
import com.hunt.otziv.worker_activity.model.WorkerRiskEventType;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.repository.WorkerRiskEventRepository;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerRiskEventService {

    private final WorkerRiskEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(
            WorkerRiskIncident incident,
            WorkerRiskEventType type,
            Long actorUserId,
            String actorRole,
            String source,
            Map<String, ?> payload
    ) {
        if (incident == null || incident.getId() == null || type == null) {
            return;
        }
        WorkerRiskEvent event = new WorkerRiskEvent();
        event.setIncident(incident);
        event.setEventType(type);
        event.setActorUserId(actorUserId);
        event.setActorRole(clean(actorRole, "SYSTEM"));
        event.setSource(clean(source, "system"));
        event.setPayloadJson(json(payload));
        event.setCreatedAt(LocalDateTime.now());
        repository.save(event);
    }

    private String json(Map<String, ?> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            log.warn("Не удалось сериализовать событие риска: {}", exception.getMessage());
            return "{\"serializationError\":true}";
        }
    }

    private String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
