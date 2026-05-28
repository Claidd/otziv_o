package com.hunt.otziv.payments;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ManualPaymentTaskRepository extends CrudRepository<ManualPaymentTask, Long> {

    @Query("""
        SELECT task
        FROM ManualPaymentTask task
        JOIN FETCH task.manager manager
        JOIN FETCH manager.user user
        JOIN FETCH task.paymentProfile profile
        WHERE user.id = :userId
        ORDER BY task.createdAt DESC, task.id DESC
    """)
    List<ManualPaymentTask> findAllByManagerUserId(@Param("userId") Long userId);

    @Query("""
        SELECT task
        FROM ManualPaymentTask task
        JOIN FETCH task.manager manager
        JOIN FETCH manager.user user
        JOIN FETCH task.paymentProfile profile
        ORDER BY task.createdAt DESC, task.id DESC
    """)
    List<ManualPaymentTask> findAllForManagement();

    @Query("""
        SELECT task
        FROM ManualPaymentTask task
        JOIN FETCH task.manager manager
        JOIN FETCH manager.user user
        JOIN FETCH task.paymentProfile profile
        WHERE task.id = :id
    """)
    Optional<ManualPaymentTask> findByIdWithDetails(@Param("id") Long id);

    @Query("""
        SELECT task
        FROM ManualPaymentTask task
        JOIN FETCH task.manager manager
        JOIN FETCH manager.user user
        JOIN FETCH task.paymentProfile profile
        WHERE manager.id = :managerId
          AND profile.id = :profileId
          AND task.status = :status
        ORDER BY task.createdAt ASC, task.id ASC
    """)
    List<ManualPaymentTask> findActiveForRouting(
            @Param("managerId") Long managerId,
            @Param("profileId") Long profileId,
            @Param("status") ManualPaymentTaskStatus status
    );
}
