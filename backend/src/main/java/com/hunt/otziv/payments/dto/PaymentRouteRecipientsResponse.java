package com.hunt.otziv.payments.dto;

import java.util.List;

/**
 * People a manager may name when a card number is issued for an order invoice. A person with
 * {@code blockReason} cannot be chosen; {@code overrunKopecks} shows how far the invoice would
 * exceed the available balance of a person who can.
 */
public record PaymentRouteRecipientsResponse(
        Long orderId,
        Long paymentLinkId,
        long amountKopecks,
        List<Recipient> recipients
) {
    public record Recipient(
            Long profileId,
            Long userId,
            String role,
            String name,
            boolean orderSpecialist,
            boolean manager,
            boolean teamMember,
            boolean workedWithClient,
            long availableKopecks,
            long overrunKopecks,
            boolean current,
            String blockReason
    ) {
    }
}
