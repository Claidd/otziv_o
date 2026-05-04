package com.hunt.otziv.manager.dto.api;

import java.math.BigDecimal;

public record OrderProductResponse(
        Long id,
        String label,
        BigDecimal price,
        boolean photo
) {
}
