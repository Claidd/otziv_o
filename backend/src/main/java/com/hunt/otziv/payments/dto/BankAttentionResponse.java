package com.hunt.otziv.payments.dto;

import java.time.LocalDateTime;
import java.util.List;

/** «Требует решения»: every waiting payment matter, oldest first, and whether the list was cut at its limit. */
public record BankAttentionResponse(
        List<BankAttentionItemResponse> items,
        boolean truncated,
        LocalDateTime checkedAt
) {
}
