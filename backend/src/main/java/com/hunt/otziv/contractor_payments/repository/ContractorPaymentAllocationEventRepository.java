package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationEventType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocationEvent;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorPaymentAllocationEventRepository
        extends JpaRepository<ContractorPaymentAllocationEvent, Long> {

    boolean existsByAllocationIdAndExternalRef(Long allocationId, String externalRef);

    List<ContractorPaymentAllocationEvent> findAllByAllocationIdOrderByEffectiveAtAscIdAsc(Long allocationId);

    List<ContractorPaymentAllocationEvent> findAllByAllocationIdInOrderByEffectiveAtAscIdAsc(
            Collection<Long> allocationIds
    );

    @Query("""
        SELECT COALESCE(SUM(e.amountKopecks), 0)
        FROM ContractorPaymentAllocationEvent e
        WHERE e.allocation.recipientProfile.id = :profileId
          AND e.allocation.mode = :mode
          AND e.eventType IN :types
    """)
    long sumByProfileAndModeAndTypeIn(@Param("profileId") Long profileId,
                                      @Param("mode") ContractorAllocationMode mode,
                                      @Param("types") Collection<ContractorAllocationEventType> types);

    @Query("""
        SELECT COALESCE(SUM(e.amountKopecks), 0)
        FROM ContractorPaymentAllocationEvent e
        WHERE e.allocation.recipientProfile.id = :profileId
          AND e.allocation.mode = :mode
          AND e.eventType IN :types
          AND e.effectiveAt >= :from
          AND e.effectiveAt < :to
    """)
    long sumByProfileAndModeAndTypeInAndPeriod(
            @Param("profileId") Long profileId,
            @Param("mode") ContractorAllocationMode mode,
            @Param("types") Collection<ContractorAllocationEventType> types,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );
}
