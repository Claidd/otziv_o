package com.hunt.otziv.p_products.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NagulResult {
    private boolean success;
    private String message;
    private LocalDateTime nextAvailableTime;

    public static NagulResult success(String message) {
        return NagulResult.builder()
                .success(true)
                .message(message)
                .build();
    }

    public static NagulResult error(String message) {
        return NagulResult.builder()
                .success(false)
                .message(message)
                .build();
    }

    public static NagulResult tooFast(String message, LocalDateTime nextAvailableTime) {
        return NagulResult.builder()
                .success(false)
                .message(message)
                .nextAvailableTime(nextAvailableTime)
                .build();
    }
}
