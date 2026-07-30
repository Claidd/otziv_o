package com.hunt.otziv.workload_shadow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkloadTransferGraphSnapshotService {

    private final ObjectMapper objectMapper;

    public Snapshot snapshot(Object value) {
        String json = json(value);
        return new Snapshot(json, fingerprint(json));
    }

    public String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Не удалось зафиксировать граф передачи нагрузки",
                    exception
            );
        }
    }

    public String fingerprint(String json) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }

    public record Snapshot(String json, String fingerprint) {
    }
}
