package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.Worker;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ManagerRepository extends CrudRepository<Manager, Long> {

    // найти менеджера по id
//    Optional<Manager> findById(Long id);

    // найти оператора по id

    List<Manager> findAll();

    @Query("SELECT DISTINCT m FROM Manager m LEFT JOIN FETCH m.user u LEFT JOIN FETCH m.companies c LEFT JOIN FETCH m.leads l LEFT JOIN FETCH u.operators LEFT JOIN FETCH u.marketologs LEFT JOIN FETCH u.workers LEFT JOIN FETCH u.managers")
    List<Manager> findAllManagers();

    @Query("""
    SELECT DISTINCT m
    FROM Manager m
    JOIN FETCH m.user u
    LEFT JOIN FETCH u.image
    WHERE m.id = :id
""")
    Optional<Manager> findByIdWithUser(@Param("id") Long id);

    @Query("""
    SELECT DISTINCT m
    FROM Manager m
    JOIN FETCH m.user u
    LEFT JOIN FETCH u.image
    LEFT JOIN FETCH u.workers w
    LEFT JOIN FETCH w.user
    LEFT JOIN FETCH u.operators o
    LEFT JOIN FETCH o.user
    LEFT JOIN FETCH u.marketologs mk
    LEFT JOIN FETCH mk.user
    WHERE u.id = :id
    """)
    Optional<Manager> findByUserId(Long id);

    @Query("""
    SELECT DISTINCT m
    FROM Manager m
    JOIN FETCH m.user u
    LEFT JOIN FETCH m.paymentProfile
    WHERE u.id = :userId
""")
    Optional<Manager> findByUserIdWithPaymentProfile(@Param("userId") Long userId);

    @Query("""
        SELECT m
        FROM Manager m
        JOIN FETCH m.user u
        WHERE u.id IN :userIds
    """)
    List<Manager> findAllByUserIdsForAdminList(@Param("userIds") Set<Long> userIds);

    @Query("""
    SELECT DISTINCT m
    FROM Manager m
    JOIN FETCH m.user u
    LEFT JOIN FETCH m.paymentProfile
    WHERE m.id = :managerId
""")
    Optional<Manager> findByIdWithPaymentProfile(@Param("managerId") Long managerId);

    @Query("""
    SELECT DISTINCT m
    FROM Manager m
    JOIN FETCH m.user u
    JOIN u.roles r
    LEFT JOIN FETCH m.companies c
    LEFT JOIN FETCH m.leads l
    LEFT JOIN FETCH u.operators
    LEFT JOIN FETCH u.marketologs
    LEFT JOIN FETCH u.workers
    LEFT JOIN FETCH u.managers
    WHERE m IN :managers
      AND u.active = true
      AND r.name = 'ROLE_MANAGER'
""")
    List<Manager> findAllManagersToOwner(List<Manager> managers);

//    @Query("SELECT DISTINCT m FROM Manager m LEFT JOIN FETCH m.user u LEFT JOIN FETCH u.operators LEFT JOIN FETCH u.marketologs LEFT JOIN FETCH u.workers LEFT JOIN FETCH u.managers WHERE m IN :managers")
//    List<Manager> findAllManagersWorkers(List<Manager> managers);

    @Query("""
    SELECT DISTINCT m
    FROM Manager m
    JOIN FETCH m.user u
    LEFT JOIN FETCH u.image
    LEFT JOIN FETCH u.workers w
    LEFT JOIN FETCH w.user
    LEFT JOIN FETCH u.operators o
    LEFT JOIN FETCH o.user
    LEFT JOIN FETCH u.marketologs mk
    LEFT JOIN FETCH mk.user
    JOIN u.roles r
    WHERE m IN :managers
      AND u.active = true
      AND r.name = 'ROLE_MANAGER'
""")
    List<Manager> findAllManagersWorkers(List<Manager> managers);

    @Query("""
    SELECT m
    FROM Manager m
    JOIN FETCH m.user u
    JOIN u.roles r
    LEFT JOIN FETCH u.image
    WHERE u.active = true
      AND r.name = 'ROLE_MANAGER'
""")
    List<Manager> findAllWithUserAndImage();

    @Query("""
    SELECT DISTINCT m
    FROM Manager m
    JOIN FETCH m.user u
    LEFT JOIN FETCH u.image
    ORDER BY m.id
""")
    List<Manager> findAllForReportReviewSettings();

    @Query("""
    SELECT DISTINCT m
    FROM Manager m
    JOIN FETCH m.user u
    LEFT JOIN FETCH m.paymentProfile
    ORDER BY m.id
""")
    List<Manager> findAllForPaymentProfileAssignments();

    @Query("""
    SELECT DISTINCT m
    FROM Manager m
    JOIN FETCH m.user u
    WHERE m.clientId = :clientId
""")
    List<Manager> findAllByClientIdWithUser(@Param("clientId") String clientId);

    Optional<Manager> findByAuditTelegramGroupChatId(Long auditTelegramGroupChatId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Manager m
        SET m.auditTelegramGroupChatId = :newChatId
        WHERE m.auditTelegramGroupChatId = :oldChatId
    """)
    int updateAuditTelegramGroupChatId(
            @Param("oldChatId") Long oldChatId,
            @Param("newChatId") Long newChatId
    );

    @Query("""
    SELECT m.user.id
    FROM Manager m
    WHERE m.id IN :managerIds
""")
    List<Long> findUserIdsByManagerIds(@Param("managerIds") Set<Long> managerIds);

}
