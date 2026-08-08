package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentRolloutState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorPaymentRolloutStateRepository
        extends JpaRepository<ContractorPaymentRolloutState, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM ContractorPaymentRolloutState state WHERE state.id = :id")
    Optional<ContractorPaymentRolloutState> findByIdForUpdate(@Param("id") Integer id);
}
