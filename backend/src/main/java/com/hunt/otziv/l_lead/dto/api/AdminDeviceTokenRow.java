package com.hunt.otziv.l_lead.dto.api;

import java.time.LocalDateTime;

/** Secret-free scalar projection for batching device tokens by telephone. */
public record AdminDeviceTokenRow(
        Long telephoneId,
        String token,
        LocalDateTime createdAt,
        boolean active
) {
}
