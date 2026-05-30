package com.hunt.otziv.payments.repository;

import com.hunt.otziv.payments.model.PaymentProfile;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentProfileRepository extends CrudRepository<PaymentProfile, Long> {

    List<PaymentProfile> findAllByOrderByDefaultProfileDescNameAsc();

    Optional<PaymentProfile> findByCode(String code);

    Optional<PaymentProfile> findByTerminalKey(String terminalKey);

    Optional<PaymentProfile> findFirstByDefaultProfileTrueOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT profile FROM PaymentProfile profile WHERE profile.id = :id")
    Optional<PaymentProfile> findByIdForUpdate(@Param("id") Long id);
}
