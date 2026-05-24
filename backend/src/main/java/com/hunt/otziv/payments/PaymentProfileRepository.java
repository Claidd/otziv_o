package com.hunt.otziv.payments;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentProfileRepository extends CrudRepository<PaymentProfile, Long> {

    List<PaymentProfile> findAllByOrderByDefaultProfileDescNameAsc();

    Optional<PaymentProfile> findByCode(String code);

    Optional<PaymentProfile> findByTerminalKey(String terminalKey);

    Optional<PaymentProfile> findFirstByDefaultProfileTrueOrderByIdAsc();
}
