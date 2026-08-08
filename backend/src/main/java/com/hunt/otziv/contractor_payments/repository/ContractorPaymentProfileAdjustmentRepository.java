package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfileAdjustment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractorPaymentProfileAdjustmentRepository
        extends JpaRepository<ContractorPaymentProfileAdjustment, Long> {

    List<ContractorPaymentProfileAdjustment> findAllByProfileIdOrderByEffectiveAtDescIdDesc(Long profileId);

    boolean existsByProfileId(Long profileId);
}
