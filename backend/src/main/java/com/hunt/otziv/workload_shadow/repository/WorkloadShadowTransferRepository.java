package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadShadowTransferCaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Set-based persistence boundary for transfer simulation cases, candidates and events.
 */
public interface WorkloadShadowTransferRepository
        extends Repository<WorkloadShadowTransferCaseEntity, Long> {

    @Query(value = """
            SELECT current.worker_id AS workerId,
                   current.manager_id AS managerId,
                   current.failure_days AS failureDays,
                   current.rating AS rating,
                   manager.audit_telegram_group_chat_id AS managerGroupChatId
            FROM workload_shadow_worker_current current
            JOIN managers manager ON manager.manager_id = current.manager_id
            WHERE current.failure_days > :allowedFailureDays
              AND current.diagnostic_status = 'OK'
            """, nativeQuery = true)
    List<SourceWorkerProjection> findSourceWorkers(
            @Param("allowedFailureDays") int allowedFailureDays
    );

    @Query(value = """
            SELECT current.worker_id AS workerId,
                   current.manager_id AS managerId,
                   current.rating AS rating,
                   current.hundred_percent_days AS hundredPercentDays,
                   current.failure_days AS failureDays,
                   current.estimated_remaining_minutes AS estimatedRemainingMinutes,
                   current.accepts_company_transfers AS acceptsCompanyTransfers,
                   current.recipient_eligible AS recipientEligible,
                   current.worker_group_connected AS workerGroupConnected
            FROM workload_shadow_worker_current current
            """, nativeQuery = true)
    List<RecipientProjection> findRecipients();

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_transfer_cases
            SET active = FALSE,
                resolved_at = :observedAt
            WHERE active = TRUE
            """, nativeQuery = true)
    int deactivateTransferCases(@Param("observedAt") LocalDateTime observedAt);

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_transfer_cases (
                case_key, manager_id, source_worker_id, company_id, company_title,
                failure_number, transfer_percent, selection_rank, problem_units,
                estimated_minutes, active_order_count, new_unit_count, correction_count,
                nagul_count, publish_count, recovery_count, bad_count,
                graph_warning_count, graph_error_count,
                    graph_warning_codes, graph_error_codes,
                    candidate_count, staffing_required, fallback_worker_id, fallback_review_id,
                status, active, run_id, first_detected_at, last_seen_at, resolved_at
            )
            SELECT case_row.case_key,
                   case_row.manager_id,
                   case_row.source_worker_id,
                   case_row.company_id,
                   case_row.company_title,
                   case_row.failure_number,
                   case_row.transfer_percent,
                   case_row.selection_rank,
                   case_row.problem_units,
                   case_row.estimated_minutes,
                   case_row.active_order_count,
                   case_row.new_unit_count,
                   case_row.correction_count,
                   case_row.nagul_count,
                   case_row.publish_count,
                   case_row.recovery_count,
                   case_row.bad_count,
                   case_row.graph_warning_count,
                   case_row.graph_error_count,
                   case_row.graph_warning_codes,
                   case_row.graph_error_codes,
                   case_row.candidate_count,
                   case_row.staffing_required,
                   case_row.fallback_worker_id,
                    case_row.fallback_review_id,
                    case_row.case_status,
                   TRUE,
                   :runId,
                   :observedAt,
                   :observedAt,
                   NULL
            FROM JSON_TABLE(
                :casesJson,
                '$[*]' COLUMNS (
                    case_key VARCHAR(160)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.caseKey',
                    manager_id BIGINT PATH '$.managerId',
                    source_worker_id BIGINT PATH '$.sourceWorkerId',
                    company_id BIGINT PATH '$.companyId',
                    company_title VARCHAR(500)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.companyTitle' NULL ON EMPTY,
                    failure_number INT PATH '$.failureNumber',
                    transfer_percent INT PATH '$.transferPercent',
                    selection_rank INT PATH '$.selectionRank',
                    problem_units BIGINT PATH '$.problemUnits',
                    estimated_minutes BIGINT PATH '$.estimatedMinutes',
                    active_order_count BIGINT PATH '$.activeOrderCount',
                    new_unit_count BIGINT PATH '$.newUnitCount',
                    correction_count BIGINT PATH '$.correctionCount',
                    nagul_count BIGINT PATH '$.nagulCount',
                    publish_count BIGINT PATH '$.publishCount',
                    recovery_count BIGINT PATH '$.recoveryCount',
                    bad_count BIGINT PATH '$.badCount',
                    graph_warning_count INT PATH '$.graphWarningCount',
                    graph_error_count INT PATH '$.graphErrorCount',
                    graph_warning_codes VARCHAR(1000)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.graphWarningCodes',
                    graph_error_codes VARCHAR(1000)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.graphErrorCodes',
                    candidate_count INT PATH '$.candidateCount',
                    case_status VARCHAR(32)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.caseStatus',
                    staffing_required TINYINT PATH '$.staffingRequired',
                    fallback_worker_id BIGINT PATH '$.fallbackWorkerId' NULL ON EMPTY,
                    fallback_review_id BIGINT PATH '$.fallbackReviewId' NULL ON EMPTY
                )
            ) case_row
            ON DUPLICATE KEY UPDATE
                manager_id = VALUES(manager_id),
                source_worker_id = VALUES(source_worker_id),
                company_id = VALUES(company_id),
                company_title = VALUES(company_title),
                failure_number = VALUES(failure_number),
                transfer_percent = VALUES(transfer_percent),
                selection_rank = VALUES(selection_rank),
                problem_units = VALUES(problem_units),
                estimated_minutes = VALUES(estimated_minutes),
                active_order_count = VALUES(active_order_count),
                new_unit_count = VALUES(new_unit_count),
                correction_count = VALUES(correction_count),
                nagul_count = VALUES(nagul_count),
                publish_count = VALUES(publish_count),
                recovery_count = VALUES(recovery_count),
                bad_count = VALUES(bad_count),
                graph_warning_count = VALUES(graph_warning_count),
                graph_error_count = VALUES(graph_error_count),
                graph_warning_codes = VALUES(graph_warning_codes),
                graph_error_codes = VALUES(graph_error_codes),
                candidate_count = VALUES(candidate_count),
                staffing_required = VALUES(staffing_required),
                fallback_worker_id = VALUES(fallback_worker_id),
                fallback_review_id = VALUES(fallback_review_id),
                status = VALUES(status),
                active = TRUE,
                run_id = VALUES(run_id),
                last_seen_at = VALUES(last_seen_at),
                resolved_at = NULL
            """, nativeQuery = true)
    int upsertTransferCases(
            @Param("casesJson") String casesJson,
            @Param("runId") long runId,
            @Param("observedAt") LocalDateTime observedAt
    );

    @Modifying
    @Query(value = """
            DELETE candidate
            FROM workload_shadow_transfer_candidates candidate
            JOIN workload_shadow_transfer_cases transfer_case
              ON transfer_case.workload_shadow_transfer_case_id = candidate.transfer_case_id
            JOIN JSON_TABLE(
                :casesJson,
                '$[*]' COLUMNS (
                    case_key VARCHAR(160)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.caseKey'
                )
            ) case_row ON case_row.case_key = transfer_case.case_key
            LEFT JOIN JSON_TABLE(
                :candidatesJson,
                '$[*]' COLUMNS (
                    case_key VARCHAR(160)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.caseKey',
                    worker_id BIGINT PATH '$.workerId'
                )
            ) candidate_row
              ON candidate_row.case_key = transfer_case.case_key
             AND candidate_row.worker_id = candidate.worker_id
            WHERE candidate_row.worker_id IS NULL
            """, nativeQuery = true)
    int deleteStaleCandidates(
            @Param("casesJson") String casesJson,
            @Param("candidatesJson") String candidatesJson
    );

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_transfer_candidates (
                transfer_case_id, worker_id, sequence_number, rating,
                hundred_percent_days, failure_days, current_estimated_minutes,
                worker_group_connected, simulated_offer_status
            )
            SELECT transfer_case.workload_shadow_transfer_case_id,
                   candidate_row.worker_id,
                   candidate_row.sequence_number,
                   candidate_row.rating,
                   candidate_row.hundred_percent_days,
                   candidate_row.failure_days,
                   candidate_row.current_estimated_minutes,
                   candidate_row.worker_group_connected,
                   'WAITING'
            FROM JSON_TABLE(
                :candidatesJson,
                '$[*]' COLUMNS (
                    case_key VARCHAR(160)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.caseKey',
                    worker_id BIGINT PATH '$.workerId',
                    sequence_number INT PATH '$.sequenceNumber',
                    rating DECIMAL(5,2) PATH '$.rating',
                    hundred_percent_days INT PATH '$.hundredPercentDays',
                    failure_days INT PATH '$.failureDays',
                    current_estimated_minutes BIGINT PATH '$.currentEstimatedMinutes',
                    worker_group_connected TINYINT PATH '$.workerGroupConnected'
                )
            ) candidate_row
            JOIN workload_shadow_transfer_cases transfer_case
              ON transfer_case.case_key = candidate_row.case_key
             AND transfer_case.active = TRUE
            ON DUPLICATE KEY UPDATE
                sequence_number = VALUES(sequence_number),
                rating = VALUES(rating),
                hundred_percent_days = VALUES(hundred_percent_days),
                failure_days = VALUES(failure_days),
                current_estimated_minutes = VALUES(current_estimated_minutes),
                worker_group_connected = VALUES(worker_group_connected),
                simulated_offer_status = 'WAITING'
            """, nativeQuery = true)
    int upsertCandidates(@Param("candidatesJson") String candidatesJson);

    @Modifying
    @Query(value = """
            DELETE candidate
            FROM workload_shadow_transfer_candidates candidate
            JOIN workload_shadow_transfer_cases transfer_case
              ON transfer_case.workload_shadow_transfer_case_id = candidate.transfer_case_id
            WHERE transfer_case.active = FALSE
            """, nativeQuery = true)
    int deleteInactiveCandidates();

    @Modifying
    @Query(value = """
            INSERT INTO workload_shadow_events (
                deduplication_key, severity, event_type, manager_id, worker_id,
                company_id, transfer_case_id, title, message,
                target_group_type, target_group_chat_id, delivery_status,
                first_seen_at, last_seen_at, next_attempt_at, active
            )
            SELECT event_row.deduplication_key,
                   event_row.severity,
                   event_row.event_type,
                   event_row.manager_id,
                   event_row.worker_id,
                   event_row.company_id,
                   transfer_case.workload_shadow_transfer_case_id,
                   event_row.title,
                   event_row.message,
                   'MANAGER_AUDIT',
                   event_row.target_group_chat_id,
                   event_row.delivery_status,
                   :observedAt,
                   :observedAt,
                   event_row.next_attempt_at,
                   TRUE
            FROM JSON_TABLE(
                :eventsJson,
                '$[*]' COLUMNS (
                    deduplication_key VARCHAR(190)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.deduplicationKey',
                    severity VARCHAR(16)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.severity',
                    event_type VARCHAR(48)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.eventType',
                    manager_id BIGINT PATH '$.managerId',
                    worker_id BIGINT PATH '$.workerId',
                    company_id BIGINT PATH '$.companyId',
                    case_key VARCHAR(160)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.caseKey',
                    title VARCHAR(220)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.title',
                    message VARCHAR(2000)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.message',
                    target_group_chat_id BIGINT PATH '$.targetGroupChatId' NULL ON EMPTY,
                    delivery_status VARCHAR(32)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                        PATH '$.deliveryStatus',
                    next_attempt_at DATETIME(6) PATH '$.nextAttemptAt' NULL ON EMPTY
                )
            ) event_row
            JOIN workload_shadow_transfer_cases transfer_case
              ON transfer_case.case_key = event_row.case_key
             AND transfer_case.active = TRUE
            ON DUPLICATE KEY UPDATE
                severity = VALUES(severity),
                manager_id = VALUES(manager_id),
                worker_id = VALUES(worker_id),
                company_id = VALUES(company_id),
                transfer_case_id = VALUES(transfer_case_id),
                title = VALUES(title),
                message = VALUES(message),
                target_group_type = 'MANAGER_AUDIT',
                target_group_chat_id = VALUES(target_group_chat_id),
                delivery_attempts = CASE
                    WHEN VALUES(target_group_chat_id) IS NULL
                      OR VALUES(target_group_chat_id) >= 0
                        THEN 0
                    WHEN workload_shadow_events.active = FALSE
                        THEN 0
                    WHEN workload_shadow_events.delivery_status IN ('PROCESSING', 'RETRY', 'PENDING')
                        THEN workload_shadow_events.delivery_attempts
                    WHEN workload_shadow_events.delivery_status = 'DEAD'
                     AND COALESCE(workload_shadow_events.last_error_code, '') <> 'MISSING_GROUP_BINDING'
                        THEN workload_shadow_events.delivery_attempts
                    WHEN workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN workload_shadow_events.delivery_attempts
                    ELSE 0
                END,
                next_attempt_at = CASE
                    WHEN VALUES(target_group_chat_id) IS NULL
                      OR VALUES(target_group_chat_id) >= 0
                        THEN NULL
                    WHEN workload_shadow_events.active = FALSE
                        THEN VALUES(next_attempt_at)
                    WHEN workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'RETRY'
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'DEAD'
                     AND COALESCE(workload_shadow_events.last_error_code, '') <> 'MISSING_GROUP_BINDING'
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN workload_shadow_events.next_attempt_at
                    WHEN workload_shadow_events.delivery_status = 'PENDING'
                        THEN CASE
                            WHEN workload_shadow_events.next_attempt_at IS NULL
                              OR VALUES(next_attempt_at) IS NULL
                                THEN NULL
                            ELSE LEAST(
                                workload_shadow_events.next_attempt_at,
                                VALUES(next_attempt_at)
                            )
                        END
                    ELSE VALUES(next_attempt_at)
                END,
                delivery_status = CASE
                    WHEN VALUES(target_group_chat_id) IS NULL
                      OR VALUES(target_group_chat_id) >= 0
                        THEN 'MISSING_GROUP_BINDING'
                    WHEN workload_shadow_events.active = FALSE
                        THEN VALUES(delivery_status)
                    WHEN workload_shadow_events.delivery_status = 'PROCESSING'
                        THEN 'PROCESSING'
                    WHEN workload_shadow_events.delivery_status = 'RETRY'
                        THEN 'RETRY'
                    WHEN workload_shadow_events.delivery_status = 'DEAD'
                     AND COALESCE(workload_shadow_events.last_error_code, '') <> 'MISSING_GROUP_BINDING'
                        THEN 'DEAD'
                    WHEN workload_shadow_events.delivery_status = 'SENT'
                     AND workload_shadow_events.delivered_at >= :cooldownStart
                        THEN 'SENT'
                    WHEN workload_shadow_events.delivery_status = 'PENDING'
                        THEN 'PENDING'
                    ELSE VALUES(delivery_status)
                END,
                occurrence_count = workload_shadow_events.occurrence_count + 1,
                last_seen_at = VALUES(last_seen_at),
                active = TRUE,
                resolved_at = NULL
            """, nativeQuery = true)
    int upsertEvents(
            @Param("eventsJson") String eventsJson,
            @Param("observedAt") LocalDateTime observedAt,
            @Param("cooldownStart") LocalDateTime cooldownStart
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_events
            SET active = FALSE,
                resolved_at = :observedAt
            WHERE active = TRUE
              AND event_type IN (
                  'TRANSFER_RECOMMENDATION',
                  'STAFFING_REQUIRED',
                  'EMERGENCY_FALLBACK',
                  'TRANSFER_GRAPH_WARNING'
              )
              AND last_seen_at < :observedAt
            """, nativeQuery = true)
    int deactivateUnseenEvents(@Param("observedAt") LocalDateTime observedAt);

    @Modifying
    @Query(value = """
            DELETE FROM workload_shadow_transfer_cases
            WHERE active = FALSE
              AND resolved_at IS NOT NULL
              AND resolved_at < :cutoff
            ORDER BY resolved_at, workload_shadow_transfer_case_id
            LIMIT :batchSize
            """, nativeQuery = true)
    int deleteInactiveResolvedCases(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("batchSize") int batchSize
    );

    interface SourceWorkerProjection {
        Long getWorkerId();

        Long getManagerId();

        Integer getFailureDays();

        BigDecimal getRating();

        Long getManagerGroupChatId();
    }

    interface RecipientProjection {
        Long getWorkerId();

        Long getManagerId();

        BigDecimal getRating();

        Integer getHundredPercentDays();

        Integer getFailureDays();

        Long getEstimatedRemainingMinutes();

        Object getAcceptsCompanyTransfers();

        Object getRecipientEligible();

        Object getWorkerGroupConnected();
    }
}
