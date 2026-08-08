package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorPaymentProfileRepository extends JpaRepository<ContractorPaymentProfile, Long> {

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
}
