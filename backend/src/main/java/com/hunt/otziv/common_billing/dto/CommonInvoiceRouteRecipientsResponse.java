package com.hunt.otziv.common_billing.dto;

import java.util.List;

/**
 * People a manager may name when a card number is issued for a common invoice. A person with
 * {@code blockReason} cannot be chosen; {@code overrunKopecks} shows how far the invoice would
 * exceed the available balance of a person who can.
 */
public record CommonInvoiceRouteRecipientsResponse(
        Long invoiceId,
        long amountKopecks,
        List<Recipient> recipients,
        /** The owner's card, where the bank profile lets the owner take card transfers; null elsewhere. */
        OwnerCard ownerCard
) {
    public CommonInvoiceRouteRecipientsResponse(Long invoiceId, long amountKopecks, List<Recipient> recipients) {
        this(invoiceId, amountKopecks, recipients, null);
    }

    /**
     * {@code remainingKopecks} is what may still be issued on the card this month. With {@code blockReason}
     * the card cannot be named: the month's limit does not hold this invoice, or the client already has it.
     */
    public record OwnerCard(String name, long remainingKopecks, boolean current, String blockReason) {
    }

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
