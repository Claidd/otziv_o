package com.hunt.otziv.specialist_transfer.dto;

/**
 * A specialist whose work can be moved: attached to companies the actor may transfer. Deactivated specialists are
 * included ({@code active = false}): moving their companies and open orders away is the point of the transfer.
 */
public record SpecialistTransferSourceResponse(
        Long workerId,
        String label,
        boolean active,
        long companyCount,
        long openOrderCount
) {
}
