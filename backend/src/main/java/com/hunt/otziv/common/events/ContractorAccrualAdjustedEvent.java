package com.hunt.otziv.common.events;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.context.ApplicationEvent;

/** Requests a durable analytics refresh for the month of a committed ledger change. */
public final class ContractorAccrualAdjustedEvent extends ApplicationEvent {

    private final LocalDate effectiveOn;

    public ContractorAccrualAdjustedEvent(Object source, LocalDate effectiveOn) {
        super(source);
        this.effectiveOn = Objects.requireNonNull(effectiveOn, "effectiveOn");
    }

    public LocalDate effectiveOn() {
        return effectiveOn;
    }
}
