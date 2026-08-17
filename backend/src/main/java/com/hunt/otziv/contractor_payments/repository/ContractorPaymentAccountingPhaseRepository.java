package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingPhase;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorPaymentAccountingPhaseRepository
        extends JpaRepository<ContractorPaymentAccountingPhase, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM ContractorPaymentAccountingPhase state WHERE state.id = :id")
    Optional<ContractorPaymentAccountingPhase> findByIdForUpdate(@Param("id") Integer id);

    /**
     * Fail-closed reconciliation gate executed while the singleton phase row
     * is locked. A zero result proves that every typed task ledger exposure
     * has one matching contractor allocation exposure before SHADOW becomes
     * historical.
     */
    @Query(value = """
        WITH source_balance AS (
            SELECT entry.task_id,
                   entry.source_kind,
                   entry.source_id,
                   entry.source_generation,
                   SUM(entry.reserved_delta_kopecks) AS pending_kopecks,
                   SUM(entry.confirmed_delta_kopecks) AS confirmed_kopecks,
                   MAX(CASE
                       WHEN entry.verified = FALSE
                        AND entry.confirmed_delta_kopecks > 0 THEN 1 ELSE 0
                   END) AS unverified_confirmation
            FROM manual_payment_task_ledger_entries entry
            GROUP BY entry.task_id, entry.source_kind,
                     entry.source_id, entry.source_generation
        ),
        task_ledger AS (
            SELECT balance.task_id,
                   SUM(GREATEST(0, balance.pending_kopecks)
                       + GREATEST(0, balance.confirmed_kopecks)) AS exposure_kopecks,
                   MAX(CASE
                       WHEN balance.pending_kopecks < 0
                         OR balance.confirmed_kopecks < 0
                         OR (balance.confirmed_kopecks > 0
                             AND balance.unverified_confirmation > 0)
                       THEN 1 ELSE 0
                   END) AS invalid_ledger
            FROM source_balance balance
            GROUP BY balance.task_id
        ),
        task_allocation AS (
            SELECT allocation.manual_payment_task_id AS task_id,
                   SUM(
                       GREATEST(0, allocation.confirmed_kopecks - allocation.returned_kopecks)
                       + CASE
                           WHEN allocation.status IN
                                ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                           THEN GREATEST(
                               0,
                               allocation.amount_kopecks
                                   - GREATEST(0, allocation.confirmed_kopecks
                                       - allocation.returned_kopecks)
                           )
                           ELSE 0
                         END
                   ) AS exposure_kopecks,
                   MAX(CASE
                       WHEN allocation.returned_kopecks > allocation.confirmed_kopecks
                         OR allocation.mode = 'LIVE'
                       THEN 1 ELSE 0
                   END) AS invalid_allocation
            FROM contractor_payment_allocations allocation
            WHERE allocation.manual_payment_task_id IS NOT NULL
            GROUP BY allocation.manual_payment_task_id
        )
        SELECT COUNT(*)
        FROM (
            SELECT task.id AS anomaly_id
            FROM manual_payment_tasks task
            LEFT JOIN task_ledger ledger ON ledger.task_id = task.id
            LEFT JOIN task_allocation allocation ON allocation.task_id = task.id
            WHERE (
                    task.accounting_target_kind = 'UNRESOLVED'
                    AND (COALESCE(ledger.exposure_kopecks, 0) <> 0
                         OR COALESCE(ledger.invalid_ledger, 0) <> 0)
                  )
               OR (
                    task.accounting_target_kind IN ('SPECIALIST', 'MANAGER')
                    AND (COALESCE(ledger.invalid_ledger, 0) <> 0
                         OR COALESCE(allocation.invalid_allocation, 0) <> 0
                         OR COALESCE(ledger.exposure_kopecks, 0)
                            <> COALESCE(allocation.exposure_kopecks, 0))
                  )

            UNION ALL

            SELECT allocation.id AS anomaly_id
            FROM contractor_payment_allocations allocation
            LEFT JOIN manual_payment_tasks task
              ON task.id = allocation.manual_payment_task_id
            WHERE allocation.manual_payment_task_id IS NOT NULL
              AND (task.id IS NULL
                   OR allocation.returned_kopecks > allocation.confirmed_kopecks
                   OR allocation.mode = 'LIVE'
                   OR (
                       (
                           GREATEST(
                               0,
                               allocation.confirmed_kopecks
                                   - allocation.returned_kopecks
                           )
                           + CASE
                               WHEN allocation.status IN
                                    ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                               THEN GREATEST(
                                   0,
                                   allocation.amount_kopecks
                                       - GREATEST(
                                           0,
                                           allocation.confirmed_kopecks
                                               - allocation.returned_kopecks
                                       )
                               )
                               ELSE 0
                             END
                       ) > 0
                       AND (task.accounting_target_kind
                                NOT IN ('SPECIALIST', 'MANAGER')
                            OR task.accounting_target_profile_id
                                <> allocation.recipient_profile_id)
                   ))

            UNION ALL

            SELECT attribution.id AS anomaly_id
            FROM contractor_actual_payment_attributions attribution
            LEFT JOIN manual_payment_tasks task
              ON task.id = attribution.actual_manual_payment_task_id
            WHERE attribution.actual_cash_destination_kind = 'MANUAL_PAYMENT_TASK'
              AND attribution.actual_manual_payment_task_target_kind
                    IN ('SPECIALIST', 'MANAGER')
              AND (task.id IS NULL
                   OR (
                       SELECT COUNT(*)
                       FROM contractor_payment_allocations exact_allocation
                       WHERE (exact_allocation.id = attribution.original_allocation_id
                              OR (exact_allocation.source_type = 'ACTUAL_PAYMENT'
                                  AND exact_allocation.source_id = attribution.id))
                         AND exact_allocation.manual_payment_task_id
                                = attribution.actual_manual_payment_task_id
                         AND exact_allocation.recipient_profile_id
                                = attribution.actual_recipient_profile_id
                         AND exact_allocation.mode = attribution.accounting_mode
                         AND exact_allocation.amount_kopecks = attribution.amount_kopecks
                         AND exact_allocation.confirmed_kopecks >= attribution.amount_kopecks
                         AND exact_allocation.returned_kopecks
                                <= exact_allocation.confirmed_kopecks
                   ) <> 1)

            UNION ALL

            SELECT allocation.id AS anomaly_id
            FROM contractor_payment_allocations allocation
            LEFT JOIN contractor_actual_payment_attributions attribution
              ON attribution.id = allocation.source_id
             AND allocation.source_type = 'ACTUAL_PAYMENT'
            WHERE allocation.source_type = 'ACTUAL_PAYMENT'
              AND allocation.manual_payment_task_id IS NOT NULL
              AND (attribution.id IS NULL
                   OR attribution.actual_cash_destination_kind
                        <> 'MANUAL_PAYMENT_TASK'
                   OR attribution.actual_manual_payment_task_id
                        <> allocation.manual_payment_task_id)

            UNION ALL

            SELECT allocation.id AS anomaly_id
            FROM contractor_payment_allocations allocation
            WHERE allocation.manual_payment_task_id IS NOT NULL
              AND (allocation.confirmed_kopecks > 0
                   OR allocation.returned_kopecks > 0)
              AND (SELECT COUNT(*)
                       FROM contractor_actual_payment_attributions attribution
                       WHERE attribution.actual_cash_destination_kind
                                = 'MANUAL_PAYMENT_TASK'
                         AND attribution.actual_manual_payment_task_id
                                = allocation.manual_payment_task_id
                         AND attribution.actual_recipient_profile_id
                                = allocation.recipient_profile_id
                         AND attribution.accounting_mode = allocation.mode
                         AND (attribution.original_allocation_id = allocation.id
                              OR (allocation.source_type = 'ACTUAL_PAYMENT'
                                  AND allocation.source_id = attribution.id))) <> 1
        ) anomalies
    """, nativeQuery = true)
    long countManualTaskPromotionAnomalies();
}
