package com.hunt.otziv.contractor_payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One-way latch for the completion-attribution accounting boundary. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "contractor_completion_cutover_state")
public class ContractorCompletionCutoverState {

    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "attribution_start_date", nullable = false)
    private LocalDate attributionStartDate;

    @Column(name = "locked_at", nullable = false)
    private LocalDateTime lockedAt;
}
