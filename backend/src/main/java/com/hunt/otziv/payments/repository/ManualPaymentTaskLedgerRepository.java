package com.hunt.otziv.payments.repository;

import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerEntry;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManualPaymentTaskLedgerRepository extends JpaRepository<ManualPaymentTaskLedgerEntry, Long> {

    @Query("""
        SELECT entry
        FROM ManualPaymentTaskLedgerEntry entry
        LEFT JOIN FETCH entry.accountingTargetProfile profile
        LEFT JOIN FETCH profile.user
        JOIN FETCH entry.task task
        WHERE entry.reservationKey = :reservationKey
    """)
    Optional<ManualPaymentTaskLedgerEntry> findReservation(@Param("reservationKey") String reservationKey);

    List<ManualPaymentTaskLedgerEntry> findAllByOperationKeyOrderByOperationSequence(String operationKey);

    List<ManualPaymentTaskLedgerEntry> findAllByTaskIdOrderById(Long taskId);

    @Query("""
        SELECT entry
        FROM ManualPaymentTaskLedgerEntry entry
        LEFT JOIN FETCH entry.accountingTargetProfile profile
        LEFT JOIN FETCH profile.user
        JOIN FETCH entry.task task
        WHERE entry.sourceKind = :sourceKind
          AND entry.sourceId = :sourceId
          AND entry.sourceGeneration = :sourceGeneration
        ORDER BY entry.id DESC
    """)
    List<ManualPaymentTaskLedgerEntry> findSourceHistoryNewestFirst(
            @Param("sourceKind") ManualPaymentTaskLedgerSourceKind sourceKind,
            @Param("sourceId") long sourceId,
            @Param("sourceGeneration") String sourceGeneration
    );

    @Query("""
        SELECT entry
        FROM ManualPaymentTaskLedgerEntry entry
        LEFT JOIN FETCH entry.accountingTargetProfile profile
        LEFT JOIN FETCH profile.user
        JOIN FETCH entry.task task
        WHERE task.id = :taskId
          AND entry.taskGeneration = :taskGeneration
          AND entry.sourceKind = :sourceKind
          AND entry.sourceId = :sourceId
        ORDER BY entry.id DESC
    """)
    List<ManualPaymentTaskLedgerEntry> findArchivedSourceHistoryNewestFirst(
            @Param("taskId") Long taskId,
            @Param("taskGeneration") long taskGeneration,
            @Param("sourceKind") ManualPaymentTaskLedgerSourceKind sourceKind,
            @Param("sourceId") long sourceId
    );

    @Query("""
        SELECT entry
        FROM ManualPaymentTaskLedgerEntry entry
        LEFT JOIN FETCH entry.accountingTargetProfile profile
        LEFT JOIN FETCH profile.user
        JOIN FETCH entry.task task
        WHERE task.id = :taskId
          AND entry.sourceKind = :sourceKind
          AND entry.sourceId = :sourceId
        ORDER BY entry.id DESC
    """)
    List<ManualPaymentTaskLedgerEntry> findTaskSourceHistoryNewestFirst(
            @Param("taskId") Long taskId,
            @Param("sourceKind") ManualPaymentTaskLedgerSourceKind sourceKind,
            @Param("sourceId") long sourceId
    );

    @Query("""
        SELECT DISTINCT task.id
        FROM ManualPaymentTaskLedgerEntry entry
        JOIN entry.task task
        WHERE entry.sourceKind = :sourceKind
          AND entry.sourceId = :sourceId
        ORDER BY task.id
    """)
    List<Long> findTaskIdsBySource(
            @Param("sourceKind") ManualPaymentTaskLedgerSourceKind sourceKind,
            @Param("sourceId") long sourceId
    );

    @Query("""
        SELECT entry.reservedDeltaKopecks AS reservedDeltaKopecks,
               entry.confirmedDeltaKopecks AS confirmedDeltaKopecks,
               entry.redirectedAmountKopecks AS redirectedAmountKopecks,
               entry.eventType AS eventType,
               entry.verified AS verified
        FROM ManualPaymentTaskLedgerEntry entry
        WHERE entry.task.id = :taskId
        ORDER BY entry.id
    """)
    List<ManualPaymentTaskLedgerDeltaProjection> findDeltasByTaskId(@Param("taskId") Long taskId);

    @Query("""
        SELECT entry
        FROM ManualPaymentTaskLedgerEntry entry
        LEFT JOIN FETCH entry.accountingTargetProfile profile
        LEFT JOIN FETCH profile.user
        WHERE entry.task.id = :taskId
          AND entry.eventType = com.hunt.otziv.payments.model.ManualPaymentTaskLedgerEventType.RESERVED
          AND entry.accountingTargetKind = com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind.UNRESOLVED
          AND (SELECT COALESCE(SUM(delta.reservedDeltaKopecks), 0)
               FROM ManualPaymentTaskLedgerEntry delta
               WHERE delta.sourceKind = entry.sourceKind
                 AND delta.sourceId = entry.sourceId
                 AND delta.sourceGeneration = entry.sourceGeneration) > 0
        ORDER BY entry.id
    """)
    List<ManualPaymentTaskLedgerEntry> findPendingUnresolvedReservations(@Param("taskId") Long taskId);
}
