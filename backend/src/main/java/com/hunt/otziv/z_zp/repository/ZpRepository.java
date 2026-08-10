package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.r_review.model.Amount;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.z_zp.dto.ZpStatRow;
import com.hunt.otziv.z_zp.dto.ZpStatView;
import com.hunt.otziv.z_zp.model.Zp;
import jakarta.validation.constraints.NotNull;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public interface ZpRepository extends CrudRepository<Zp, Long>  {
    @NotNull
    List<Zp> findAll();

    List<Zp> findByOrderIdAndActiveTrue(Long orderId);

    /** Durable per-source mutex used by the ledger repair on every node. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT z FROM Zp z WHERE z.id = :id")
    Optional<Zp> findByIdForContractorLedgerUpdate(@Param("id") Long id);

    boolean existsByOrderIdAndSourceAndActiveTrue(Long orderId, String source);

    boolean existsByOrderIdAndSourceAndContractorRoleAndActiveTrue(
            Long orderId,
            String source,
            ContractorRole contractorRole
    );

    Optional<Zp> findFirstByOrderIdAndSourceAndContractorRoleAndProfessionId(
            Long orderId,
            String source,
            ContractorRole contractorRole,
            Long professionId
    );

    List<Zp> findByOrderIdAndSourceAndActiveTrue(Long orderId, String source);

    @Query("""
        SELECT z
        FROM Zp z
        WHERE z.userId = :userId
          AND z.contractorRole = :role
          AND z.id > :startZpId
        ORDER BY z.created, z.id
    """)
    List<Zp> findContractorRewards(@Param("userId") Long userId,
                                   @Param("role") ContractorRole role,
                                   @Param("startZpId") long startZpId);

    @Query("""
        SELECT z
        FROM Zp z
        WHERE z.contractorRole = :role
          AND z.id > :startZpId
        ORDER BY z.id
    """)
    List<Zp> findContractorRewardsByRoleAfterId(@Param("role") ContractorRole role,
                                                @Param("startZpId") long startZpId);

    @Query("""
        SELECT z
        FROM Zp z
        WHERE z.contractorRole = :role
          AND z.updatedAt >= :updatedSince
          AND z.id <= :maxZpId
        ORDER BY z.id
    """)
    List<Zp> findChangedContractorRewardsByRole(@Param("role") ContractorRole role,
                                                @Param("updatedSince") LocalDateTime updatedSince,
                                                @Param("maxZpId") long maxZpId);

    @Query("""
        SELECT z
        FROM Zp z
        WHERE z.userId = :userId
          AND z.contractorRole = :role
          AND z.updatedAt >= :updatedSince
          AND z.id <= :maxZpId
        ORDER BY z.id
    """)
    List<Zp> findChangedContractorRewards(@Param("userId") Long userId,
                                          @Param("role") ContractorRole role,
                                          @Param("updatedSince") LocalDateTime updatedSince,
                                          @Param("maxZpId") long maxZpId);

    @Query("""
        SELECT z
        FROM Zp z
        WHERE z.contractorRole IS NOT NULL
          AND NOT EXISTS (
              SELECT repair.sourceZpId
              FROM ContractorRewardRepairClaim repair
              WHERE repair.sourceZpId = z.id
                AND (
                    (repair.leaseUntil IS NOT NULL AND repair.leaseUntil >= :now)
                    OR (repair.nextRetryAt IS NOT NULL AND repair.nextRetryAt > :now)
                )
          )
          AND (
              NOT EXISTS (
                  SELECT marker.id
                  FROM ContractorRewardSyncMarker marker
                  WHERE marker.sourceZpId = z.id
              )
              OR EXISTS (
                  SELECT marker.id
                  FROM ContractorRewardSyncMarker marker
                  WHERE marker.sourceZpId = z.id
                    AND (
                        marker.sourceActive <> z.active
                        OR marker.sourceUpdatedAt IS NULL
                        OR (z.updatedAt IS NOT NULL AND marker.sourceUpdatedAt < z.updatedAt)
                    )
              )
          )
        ORDER BY z.id
    """)
    List<Zp> findContractorRewardsNeedingGlobalRepair(@Param("now") LocalDateTime now,
                                                       Pageable pageable);

    @Query("SELECT COALESCE(MAX(z.id), 0) FROM Zp z")
    long findCurrentMaxId();

    /**
     * Legacy order rewards created before contractor accounting did not carry
     * an explicit contractor role. The permanent profession link is the
     * reliable owner snapshot for those rows, even if the order was later
     * transferred to another specialist or manager.
     */
    @Query(value = """
        SELECT z.zp_id
        FROM zp z
        INNER JOIN workers w
                ON w.worker_id = z.zp_profession
               AND w.user_id = z.zp_user
        WHERE z.zp_user = :userId
          AND z.zp_contractor_role IS NULL
          AND z.zp_order IS NOT NULL
          AND z.zp_order > 0
          AND z.zp_date >= :startDate
          AND z.zp_date < :endDate
        ORDER BY z.zp_id
    """, nativeQuery = true)
    List<Long> findLegacySpecialistRewardIdsInPeriod(@Param("userId") Long userId,
                                                      @Param("startDate") LocalDate startDate,
                                                      @Param("endDate") LocalDate endDate);

    @Query(value = """
        SELECT z.zp_id
        FROM zp z
        INNER JOIN managers m
                ON m.manager_id = z.zp_profession
               AND m.user_id = z.zp_user
        WHERE z.zp_user = :userId
          AND z.zp_contractor_role IS NULL
          AND z.zp_order IS NOT NULL
          AND z.zp_order > 0
          AND z.zp_date >= :startDate
          AND z.zp_date < :endDate
        ORDER BY z.zp_id
    """, nativeQuery = true)
    List<Long> findLegacyManagerRewardIdsInPeriod(@Param("userId") Long userId,
                                                   @Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate);

    @Query("SELECT z FROM Zp z WHERE z.userId = :userId AND z.created >= :startDate AND z.created < :endDate")
    List<Zp> getAllWorkerZpInPeriod(@Param("userId") Long userId,
                                    @Param("startDate") LocalDate startDate,
                                    @Param("endDate") LocalDate endDate);

    @Query("SELECT zp FROM Zp zp WHERE zp.userId = :userId AND zp.created >= :firstDayOfMonth AND zp.created <= :lastDayOfMonth")
    List<Zp> getAllWorkerZp(@Param("userId") Long userId,
                            @Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                            @Param("lastDayOfMonth") LocalDate lastDayOfMonth);


//    @Query("SELECT z FROM Zp z WHERE YEAR(z.created) = YEAR(:localDate) AND MONTH(z.created) = MONTH(:localDate)")
//    List<Zp> findAllToDate(LocalDate localDate);

    @Query("SELECT z FROM Zp z WHERE z.created >= :startDate AND z.created < :endDate")
    List<Zp> findAllToDate(@Param("startDate") LocalDate startDate,
                           @Param("endDate") LocalDate endDate);

    @Query("""
        SELECT new com.hunt.otziv.z_zp.dto.ZpStatRow(z.created, z.sum, z.amount)
        FROM Zp z
        WHERE z.created >= :startDate AND z.created < :endDate
    """)
    List<ZpStatRow> findStatRowsToDate(@Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate);

    @Query("SELECT z FROM Zp z WHERE z.created >= :startDate AND z.created < :endDate AND z.userId IN :peopleId")
    List<Zp> findAllToDateByOwner(@Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate,
                                  @Param("peopleId") Set<Long> peopleId);

    @Query("""
        SELECT new com.hunt.otziv.z_zp.dto.ZpStatRow(z.created, z.sum, z.amount)
        FROM Zp z
        WHERE z.created >= :startDate AND z.created < :endDate AND z.userId IN :peopleId
    """)
    List<ZpStatRow> findStatRowsToDateByOwner(@Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate,
                                              @Param("peopleId") Set<Long> peopleId);

    @Query("SELECT z FROM Zp z WHERE z.userId IN :peopleId")
    List<Zp> findAllByOwner(@Param("peopleId") Set<Long> peopleId);


    @Query("SELECT z FROM Zp z WHERE z.created >= :startDate AND z.created < :endDate AND z.userId = :userId")
    List<Zp> findAllToDateByUser(@Param("startDate") LocalDate startDate,
                                 @Param("endDate") LocalDate endDate,
                                 @Param("userId") Long userId);

    @Query("SELECT SUM(z.sum) FROM Zp z WHERE z.userId = :userId AND z.created = :created")
    BigDecimal sumByUserAndCreated(@Param("userId") Long userId,
                                   @Param("created") LocalDate created);


//    @Query("SELECT z.fio, SUM(z.sum) as totalSum FROM Zp z WHERE z.created BETWEEN :startDate AND :endDate GROUP BY z.fio ORDER BY totalSum DESC")
//    List<Object[]> findAllToDateToMap(LocalDate startDate, LocalDate endDate);


    @Query(value = """
        SELECT
            u.fio AS fio,
            COALESCE(z.total_sum, 0) AS totalSum,
            MIN(r.name) AS role,
            COALESCE(z.total_orders, 0) AS totalOrders,
            COALESCE(z.total_amount, 0) AS totalAmount
        FROM users u
        LEFT JOIN (
            SELECT
                zp_user,
                SUM(zp_sum) AS total_sum,
                COUNT(DISTINCT zp_id) AS total_orders,
                SUM(zp_amount) AS total_amount
            FROM zp
            WHERE zp_date BETWEEN :startDate AND :endDate
            GROUP BY zp_user
        ) z ON z.zp_user = u.id
        LEFT JOIN users_roles ur ON ur.user_id = u.id
        LEFT JOIN roles r ON r.id = ur.role_id
        WHERE u.active = true
        GROUP BY u.id, u.fio, z.total_sum, z.total_orders, z.total_amount
        ORDER BY totalSum DESC
    """, nativeQuery = true)
    List<Object[]> findAllUsersWithZpToDate(@Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate);





//    @Query("""
//    SELECT u.fio,
//           COALESCE(SUM(z.sum), 0) AS totalSum,
//           (SELECT MIN(r.name)
//            FROM User u2
//            JOIN u2.roles r
//            WHERE u2.id = u.id) AS role
//    FROM User u
//    LEFT JOIN Zp z ON u.id = z.userId AND z.created BETWEEN :startDate AND :endDate
//    GROUP BY u.fio, u.id
//    ORDER BY totalSum DESC
//""")
//    List<Object[]> findAllUsersWithZpToDate(LocalDate startDate, LocalDate endDate);






}
