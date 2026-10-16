package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustment;
import com.hunt.otziv.contractor_payments.model.ContractorAccrualBucket;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorAccrualAdjustmentRepository extends JpaRepository<ContractorAccrualAdjustment, Long> {

    Optional<ContractorAccrualAdjustment> findByProfileIdAndIdempotencyKey(Long profileId, String idempotencyKey);

    Optional<ContractorAccrualAdjustment> findByIdAndProfileId(Long id, Long profileId);

    boolean existsByReversalOfId(Long originalId);

    List<ContractorAccrualAdjustment> findAllByProfileIdOrderByCreatedAtDescIdDesc(Long profileId, Pageable pageable);

    @Query("""
        SELECT a FROM ContractorAccrualAdjustment a
        WHERE a.profile.id = :profileId
        ORDER BY a.createdAt DESC, a.id DESC
        """)
    Slice<ContractorAccrualAdjustment> findHistoryPage(@Param("profileId") Long profileId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(a.signedAmountKopecks), 0) FROM ContractorAccrualAdjustment a WHERE a.profile.id = :profileId")
    long sumByProfileId(@Param("profileId") Long profileId);

    @Query(value = """
        SELECT COALESCE(SUM(signed_amount_kopecks), 0)
        FROM contractor_accrual_adjustments
        WHERE profile_id = :profileId
        FOR UPDATE
        """, nativeQuery = true)
    long sumForCapacityUpdate(@Param("profileId") Long profileId);

    @Query("""
        SELECT COALESCE(SUM(a.signedAmountKopecks), 0)
        FROM ContractorAccrualAdjustment a
        WHERE a.profile.id = :profileId AND a.effectiveOn >= :from AND a.effectiveOn < :to
        """)
    long sumInPeriod(@Param("profileId") Long profileId,
                     @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
        SELECT COALESCE(SUM(a.signedAmountKopecks), 0)
        FROM ContractorAccrualAdjustment a
        WHERE a.profile.id = :profileId AND a.bucket = :bucket
          AND a.effectiveOn >= :from AND a.effectiveOn < :to
        """)
    long sumBucketInPeriod(@Param("profileId") Long profileId,
                           @Param("bucket") ContractorAccrualBucket bucket,
                           @Param("from") LocalDate from, @Param("to") LocalDate to);

    interface ProfileAdjustmentTotals {
        Long getProfileId();
        long getTotal();
        long getMonth();
    }

    @Query("""
        SELECT a.profile.id AS profileId, SUM(a.signedAmountKopecks) AS total,
          SUM(CASE WHEN a.effectiveOn >= :from AND a.effectiveOn < :to
              THEN a.signedAmountKopecks ELSE 0 END) AS month
        FROM ContractorAccrualAdjustment a
        WHERE a.profile.id IN :ids
        GROUP BY a.profile.id
        """)
    List<ProfileAdjustmentTotals> sumForProfiles(@Param("ids") Collection<Long> ids,
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
