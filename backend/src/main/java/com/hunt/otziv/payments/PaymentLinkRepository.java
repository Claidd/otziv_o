package com.hunt.otziv.payments;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentLinkRepository extends CrudRepository<PaymentLink, Long> {

    Optional<PaymentLink> findByToken(String token);

    List<PaymentLink> findTop100ByOrderByCreatedAtDesc();

    Optional<PaymentLink> findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
            Long orderId,
            Collection<PaymentLinkStatus> statuses,
            LocalDateTime now
    );

    @Query("""
        SELECT link
        FROM PaymentLink link
        LEFT JOIN FETCH link.paymentProfile
        LEFT JOIN FETCH link.order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        WHERE link.token = :token
    """)
    Optional<PaymentLink> findByTokenWithOrder(@Param("token") String token);

    @Query("""
        SELECT link
        FROM PaymentLink link
        LEFT JOIN FETCH link.paymentProfile
        LEFT JOIN FETCH link.order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        WHERE link.id = :id
    """)
    Optional<PaymentLink> findByIdWithOrder(@Param("id") Long id);

    @Query("""
        SELECT link
        FROM PaymentLink link
        LEFT JOIN FETCH link.paymentProfile
        LEFT JOIN FETCH link.order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.filial
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        WHERE link.tbankOrderId = :orderId
    """)
    Optional<PaymentLink> findByTbankOrderIdWithOrder(@Param("orderId") String orderId);

    @Query("""
        SELECT link
        FROM PaymentLink link
        LEFT JOIN FETCH link.paymentProfile
        LEFT JOIN FETCH link.order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.filial
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        WHERE link.tbankPaymentId = :paymentId
    """)
    Optional<PaymentLink> findByTbankPaymentIdWithOrder(@Param("paymentId") String paymentId);
}
