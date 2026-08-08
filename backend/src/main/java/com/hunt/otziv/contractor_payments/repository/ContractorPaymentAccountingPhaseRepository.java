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
}
