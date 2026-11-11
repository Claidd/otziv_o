package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Collection;
import java.util.Set;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorPaymentProfileRepository extends JpaRepository<ContractorPaymentProfile, Long> {

    @Query("""
            SELECT DISTINCT p.id FROM ContractorPaymentProfile p JOIN p.user u JOIN u.managers m
            WHERE m.id IN :managerIds AND u.active = true AND p.enabled = true AND p.role = :role
            ORDER BY p.id
            """)
    List<Long> findTeamProfileIds(@Param("managerIds") Collection<Long> managerIds, @Param("role") ContractorRole role);

    /**
     * The managers' whole active team, whatever the profile switch says. A switched-off profile only
     * stops new requisites from being issued; money the person already received must stay attributable.
     */
    @Query("""
            SELECT DISTINCT p.id FROM ContractorPaymentProfile p JOIN p.user u JOIN u.managers m
            WHERE m.id IN :managerIds AND u.active = true AND p.role = :role
            ORDER BY p.id
            """)
    List<Long> findActiveTeamProfileIds(@Param("managerIds") Collection<Long> managerIds,
                                        @Param("role") ContractorRole role);

    /**
     * Active people who performed any order of the same companies as the given orders, or are still
     * attached to those companies: a client may pay a former performer out of habit.
     */
    @Query("""
            SELECT DISTINCT p.id FROM ContractorPaymentProfile p JOIN p.user u
            WHERE u.active = true AND p.role = :role AND (
                u.id IN (
                    SELECT ow.user.id FROM Order o JOIN o.worker ow
                    WHERE o.company.id IN (SELECT so.company.id FROM Order so WHERE so.id IN :orderIds)
                )
                OR u.id IN (
                    SELECT cw.user.id FROM Company c JOIN c.workers cw
                    WHERE c.id IN (SELECT so.company.id FROM Order so WHERE so.id IN :orderIds)
                )
            )
            ORDER BY p.id
            """)
    List<Long> findCompanyPerformerProfileIds(@Param("orderIds") Collection<Long> orderIds,
                                              @Param("role") ContractorRole role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ContractorPaymentProfile p WHERE p.id = :id")
    Optional<ContractorPaymentProfile> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ContractorPaymentProfile p WHERE p.id IN :ids ORDER BY p.id")
    List<ContractorPaymentProfile> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    @Query("""
        SELECT p
        FROM ContractorPaymentProfile p
        JOIN FETCH p.user
        WHERE p.user.id = :userId
        ORDER BY p.role
    """)
    List<ContractorPaymentProfile> findAllByUserId(@Param("userId") Long userId);

    @Query("""
        SELECT p
        FROM ContractorPaymentProfile p
        JOIN FETCH p.user
        WHERE p.enabled = true
        ORDER BY p.role, p.user.fio, p.id
    """)
    List<ContractorPaymentProfile> findAllEnabledWithUser();

    @Query("""
        SELECT p
        FROM ContractorPaymentProfile p
        JOIN FETCH p.user
        ORDER BY p.role, p.user.fio, p.id
    """)
    List<ContractorPaymentProfile> findAllWithUser();

    @Query("""
        SELECT p
        FROM ContractorPaymentProfile p
        JOIN FETCH p.user
        WHERE p.enabled = true AND p.user.id IN :userIds
        ORDER BY p.role, p.user.fio, p.id
    """)
    List<ContractorPaymentProfile> findAllEnabledByUserIds(@Param("userIds") Set<Long> userIds);

    @Query("""
        SELECT p
        FROM ContractorPaymentProfile p
        JOIN FETCH p.user
        WHERE p.user.id IN :userIds
        ORDER BY p.role, p.user.fio, p.id
    """)
    List<ContractorPaymentProfile> findAllByUserIds(@Param("userIds") Set<Long> userIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT p
        FROM ContractorPaymentProfile p
        WHERE p.user.id = :userId
        ORDER BY p.role
    """)
    List<ContractorPaymentProfile> findAllByUserIdForUpdate(@Param("userId") Long userId);

    @Query("""
        SELECT p
        FROM ContractorPaymentProfile p
        JOIN FETCH p.user
        WHERE p.user.id = :userId AND p.role = :role
    """)
    Optional<ContractorPaymentProfile> findByUserIdAndRole(@Param("userId") Long userId,
                                                           @Param("role") ContractorRole role);

    /**
     * Non-locking discovery used before a routing transaction acquires all
     * candidate mutexes in canonical order. Returning only the id avoids
     * putting a potentially stale profile entity into the persistence context.
     */
    @Query("""
        SELECT p.id
        FROM ContractorPaymentProfile p
        WHERE p.user.id = :userId AND p.role = :role
    """)
    Optional<Long> findIdByUserIdAndRole(@Param("userId") Long userId,
                                         @Param("role") ContractorRole role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT p
        FROM ContractorPaymentProfile p
        WHERE p.user.id = :userId AND p.role = :role
    """)
    Optional<ContractorPaymentProfile> findByUserIdAndRoleForUpdate(@Param("userId") Long userId,
                                                                    @Param("role") ContractorRole role);

    /** Profile id and employee name of every working employee; local payment sandbox only. */
    @Query("""
        SELECT p.id, u.fio
        FROM ContractorPaymentProfile p
        JOIN p.user u
        WHERE u.active = true
        ORDER BY p.id
    """)
    List<Object[]> findWorkingEmployeeProfileNames();

    @Query("""
        SELECT p.id
        FROM ContractorPaymentProfile p
        WHERE p.enabled = true
          AND p.trackingStartedAt > :monthStart
        ORDER BY p.id
    """)
    List<Long> findEnabledIdsRequiringCurrentMonthSync(
            @Param("monthStart") LocalDateTime monthStart,
            Pageable pageable
    );
}
