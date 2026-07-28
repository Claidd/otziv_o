package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.u_users.model.Worker;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WorkloadTransferPreferenceRepository
        extends Repository<Worker, Long> {

    @Query(value = """
            SELECT w.worker_id AS workerId,
                   w.accepts_company_transfers AS acceptsCompanyTransfers,
                   w.company_transfer_preference_changed_at AS changedAt
            FROM workers w
            JOIN users user_account
              ON user_account.id = w.user_id
            WHERE user_account.username = :username
            ORDER BY w.worker_id
            LIMIT 1
            """, nativeQuery = true)
    Optional<PreferenceProjection> findByUsername(
            @Param("username") String username
    );

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE workers w
            JOIN users user_account
              ON user_account.id = w.user_id
            SET w.accepts_company_transfers = :accepts,
                w.company_transfer_preference_changed_at = :changedAt
            WHERE w.worker_id = :workerId
              AND user_account.username = :username
            """, nativeQuery = true)
    int updatePreference(
            @Param("workerId") long workerId,
            @Param("username") String username,
            @Param("accepts") boolean accepts,
            @Param("changedAt") LocalDateTime changedAt
    );

    interface PreferenceProjection {
        Long getWorkerId();
        Boolean getAcceptsCompanyTransfers();
        LocalDateTime getChangedAt();
    }
}
