package com.hunt.otziv.p_products.repository;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.util.Pair;
import org.springframework.lang.NonNullApi;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {

    @Override
    List<Order> findAll();

    boolean existsByIdAndManager_IdIn(Long id, Collection<Long> managerIds);

    boolean existsByIdAndWorker_Id(Long id, Long workerId);

    @Query("SELECT c.id FROM Order o LEFT JOIN o.company c WHERE o.id = :orderId")
    Optional<Long> findCompanyIdByOrderId(@Param("orderId") Long orderId);

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.details d
        LEFT JOIN FETCH d.product
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH o.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.categoryCompany
        LEFT JOIN FETCH c.subCategory
        LEFT JOIN FETCH c.status
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH w.user
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        WHERE o.id = :orderId
    """)
    Optional<Order> findByIdForOrderDto(@Param("orderId") Long orderId);

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.details d
        LEFT JOIN FETCH d.product
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH o.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.categoryCompany
        LEFT JOIN FETCH c.subCategory
        LEFT JOIN FETCH c.status
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH w.user
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        WHERE o.id = :orderId
    """)
    Optional<Order> findByIdForMutation(@Param("orderId") Long orderId);

    @Query("""
        SELECT o.id AS id,
               c.id AS companyId,
               o.statusChangedAt AS statusChangedAt
        FROM Order o
        LEFT JOIN o.company c
        JOIN o.status s
        WHERE o.complete = false
          AND o.statusChangedAt IS NOT NULL
          AND o.statusChangedAt <= :cutoff
          AND s.title IN :statuses
        ORDER BY o.statusChangedAt ASC, o.id ASC
    """)
    List<ClientMessageCandidate> findClientMessageCandidates(@Param("statuses") Collection<String> statuses,
                                                             @Param("cutoff") LocalDateTime cutoff,
                                                             Pageable pageable);

    @Query("""
        SELECT o.id AS id,
               c.id AS companyId,
               o.waitingForClientChangedAt AS statusChangedAt
        FROM Order o
        LEFT JOIN o.company c
        JOIN o.status s
        WHERE o.complete = false
          AND o.waitingForClient = true
          AND o.waitingForClientChangedAt IS NOT NULL
          AND o.waitingForClientChangedAt <= :cutoff
          AND s.title IN :statuses
        ORDER BY o.waitingForClientChangedAt ASC, o.id ASC
    """)
    List<ClientMessageCandidate> findClientTextWaitingMessageCandidates(@Param("statuses") Collection<String> statuses,
                                                                        @Param("cutoff") LocalDateTime cutoff,
                                                                        Pageable pageable);

    interface ClientMessageCandidate {
        Long getId();
        Long getCompanyId();
        LocalDateTime getStatusChangedAt();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :orderId")
    Optional<Order> findByIdForCounterUpdate(@Param("orderId") Long orderId);

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.workers cw
        LEFT JOIN FETCH cw.user
        WHERE o.id = :orderId
    """)
    Optional<Order> findByIdWithCompanyWorkers(@Param("orderId") Long orderId);

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.filial cf
        LEFT JOIN FETCH cf.city
        WHERE o.id = :orderId
    """)
    Optional<Order> findByIdWithCompanyFilials(@Param("orderId") Long orderId);

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.details
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH o.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.categoryCompany
        LEFT JOIN FETCH c.subCategory
        LEFT JOIN FETCH c.status
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH w.user
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        WHERE o.id IN :orderId
        ORDER BY o.changed DESC, o.id DESC
    """)
    List<Order> findAll(@Param("orderId") List<Long> orderId);

    @Query("""
        SELECT
            o.id,
            c.id,
            d.id,
            c.title,
            c.commentsCompany,
            f.title,
            f.url,
            city.title,
            s.title,
            o.sum,
            c.urlChat,
            c.telegramGroupChatId,
            c.maxGroupChatId,
            c.telephone,
            m.payText,
            o.amount,
            o.counter,
            o.waitingForClient,
            wu.fio,
            cat.categoryTitle,
            sub.subCategoryTitle,
            o.created,
            o.changed,
            o.payDay,
            o.zametka,
            CASE
                WHEN c.id IS NOT NULL
                 AND o.id = (
                    SELECT MIN(companyOrder.id)
                    FROM Order companyOrder
                    WHERE companyOrder.company.id = c.id
                )
                THEN true
                ELSE false
            END,
            c.groupId
        FROM Order o
        LEFT JOIN o.details d
        LEFT JOIN o.status s
        LEFT JOIN o.filial f
        LEFT JOIN f.city city
        LEFT JOIN o.company c
        LEFT JOIN c.categoryCompany cat
        LEFT JOIN c.subCategory sub
        LEFT JOIN o.worker w
        LEFT JOIN w.user wu
        LEFT JOIN o.manager m
        WHERE o.id IN :orderId
        ORDER BY o.changed DESC, o.id DESC
    """)
    List<Object[]> findOrderListRows(@Param("orderId") List<Long> orderId);

    @Query("""
        SELECT o.worker.id, COUNT(o.id)
        FROM Order o
        WHERE o.worker.id IN :workerIds
          AND o.status.title = :status
        GROUP BY o.worker.id
    """)
    List<Object[]> countByWorkerIdsAndStatus(@Param("workerIds") List<Long> workerIds,
                                             @Param("status") String status);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.worker.id IN :workerIds
          AND o.complete = false
          AND o.changed IS NOT NULL
          AND o.changed <= :cutoff
          AND s.title IN :statuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> countManagerControlWorkerStaleOrdersByStatus(@Param("workerIds") Collection<Long> workerIds,
                                                                @Param("statuses") Collection<String> statuses,
                                                                @Param("cutoff") LocalDate cutoff);

    @Query("""
        SELECT o
        FROM Order o
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.filial
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH w.user
        WHERE o.worker.id IN :workerIds
          AND o.complete = false
          AND o.changed IS NOT NULL
          AND o.changed <= :cutoff
          AND o.status.title = :status
        ORDER BY o.changed ASC, o.id ASC
    """)
    List<Order> findManagerControlWorkerStaleOrders(@Param("workerIds") Collection<Long> workerIds,
                                                    @Param("status") String status,
                                                    @Param("cutoff") LocalDate cutoff,
                                                    Pageable pageable);

    @Query("""
        SELECT o
        FROM Order o
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.filial
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH w.user
        WHERE o.worker.id IN :workerIds
          AND o.complete = false
          AND o.changed IS NOT NULL
          AND o.changed <= :cutoff
          AND o.status.title = :status
        ORDER BY o.changed ASC, o.id ASC
    """)
    List<Order> findManagerControlWorkerStaleOrders(@Param("workerIds") Collection<Long> workerIds,
                                                    @Param("status") String status,
                                                    @Param("cutoff") LocalDate cutoff);

    @Query("""
        SELECT o
        FROM Order o
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.filial
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH w.user
        WHERE o.worker.id IN :workerIds
          AND o.complete = false
          AND o.changed IS NOT NULL
          AND o.status.title = 'Новый'
          AND (o.changed <= :cutoff OR o.waitingForClient = true)
        ORDER BY o.changed ASC, o.id ASC
    """)
    List<Order> findManagerControlWorkerNewOrdersForControl(@Param("workerIds") Collection<Long> workerIds,
                                                            @Param("cutoff") LocalDate cutoff);

    @Query("SELECT o.id FROM Order o ORDER BY o.changed, o.id")
    List<Long> findAllIdToAdmin();

    @Query(
            value = "SELECT o.id FROM Order o",
            countQuery = "SELECT COUNT(o.id) FROM Order o"
    )
    Page<Long> findPageIdToAdmin(Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.status.title IN :liveStatuses
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.status.title IN :liveStatuses
            """
    )
    Page<Long> findPageIdToAdminLive(@Param("liveStatuses") Collection<String> liveStatuses,
                                     Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.manager = :manager ORDER BY o.changed, o.id")
    List<Long> findAllIdToManager(@Param("manager") Manager manager);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.manager = :manager",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.manager = :manager"
    )
    Page<Long> findPageIdToManager(@Param("manager") Manager manager,
                                   Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.manager = :manager
                  AND o.status.title IN :liveStatuses
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.manager = :manager
                  AND o.status.title IN :liveStatuses
            """
    )
    Page<Long> findPageIdToManagerLive(@Param("manager") Manager manager,
                                       @Param("liveStatuses") Collection<String> liveStatuses,
                                       Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.worker = :worker ORDER BY o.changed, o.id")
    List<Long> findAllIdToWorker(@Param("worker") Worker worker);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.worker = :worker
                ORDER BY o.changed ASC, o.id ASC
            """,
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.worker = :worker"
    )
    Page<Long> findPageIdToWorkerForBoard(@Param("worker") Worker worker,
                                          Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.worker = :worker
                  AND o.status.title IN :liveStatuses
                ORDER BY o.changed ASC, o.id ASC
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.worker = :worker
                  AND o.status.title IN :liveStatuses
            """
    )
    Page<Long> findPageIdToWorkerForBoardLive(@Param("worker") Worker worker,
                                              @Param("liveStatuses") Collection<String> liveStatuses,
                                              Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.worker = :worker
                  AND o.status.title IN :liveStatuses
                ORDER BY
                  CASE WHEN :sortDirection = 'desc' THEN o.changed END ASC,
                  CASE WHEN :sortDirection = 'asc' THEN o.changed END DESC,
                  CASE WHEN :sortDirection = 'desc' THEN o.id END ASC,
                  CASE WHEN :sortDirection = 'asc' THEN o.id END DESC
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.worker = :worker
                  AND o.status.title IN :liveStatuses
            """
    )
    Page<Long> findPageIdToWorkerForBoardLiveSorted(@Param("worker") Worker worker,
                                                    @Param("liveStatuses") Collection<String> liveStatuses,
                                                    @Param("sortDirection") String sortDirection,
                                                    Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.manager IN :managers ORDER BY o.changed, o.id")
    List<Long> findAllIdToOwner(@Param("managers") List<Manager> managers);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.manager IN :managers",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.manager IN :managers"
    )
    Page<Long> findPageIdToOwner(@Param("managers") List<Manager> managers,
                                 Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.manager IN :managers
                  AND o.status.title IN :liveStatuses
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.manager IN :managers
                  AND o.status.title IN :liveStatuses
            """
    )
    Page<Long> findPageIdToOwnerLive(@Param("managers") List<Manager> managers,
                                     @Param("liveStatuses") Collection<String> liveStatuses,
                                     Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.status.title = :status ORDER BY o.changed, o.id")
    List<Long> findAllIdByStatus(@Param("status") String status);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.status.title = :status",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.status.title = :status"
    )
    Page<Long> findPageIdByStatus(@Param("status") String status,
                                  Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.manager = :manager AND o.status.title = :status ORDER BY o.changed, o.id")
    List<Long> findAllIdByManagerAndStatus(@Param("manager") Manager manager,
                                           @Param("status") String status);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.manager = :manager AND o.status.title = :status",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.manager = :manager AND o.status.title = :status"
    )
    Page<Long> findPageIdByManagerAndStatus(@Param("manager") Manager manager,
                                            @Param("status") String status,
                                            Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.worker = :worker AND o.status.title = :status ORDER BY o.changed, o.id")
    List<Long> findAllIdByWorkerAndStatus(@Param("worker") Worker worker,
                                          @Param("status") String status);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.worker = :worker AND o.status.title = :status",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.worker = :worker AND o.status.title = :status"
    )
    Page<Long> findPageIdByWorkerAndStatus(@Param("worker") Worker worker,
                                           @Param("status") String status,
                                           Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.manager IN :managers AND o.status.title = :status ORDER BY o.changed, o.id")
    List<Long> findAllIdByOwnerAndStatus(@Param("managers") List<Manager> managers,
                                         @Param("status") String status);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.manager IN :managers AND o.status.title = :status",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.manager IN :managers AND o.status.title = :status"
    )
    Page<Long> findPageIdByOwnerAndStatus(@Param("managers") List<Manager> managers,
                                          @Param("status") String status,
                                          Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.manager IN :managers AND o.status.title = :status ORDER BY o.changed, o.id")
    List<Long> findAllIdByOwnerAndStatus(@Param("managers") Set<Manager> managers,
                                         @Param("status") String status);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
        ORDER BY o.changed, o.id
    """)
    List<Long> findAllIdByKeyWord(@Param("keyword") String keyword,
                                  @Param("keyword2") String keyword2);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(o.id) = :keyword
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(o.id) = :keyword
            """
    )
    Page<Long> findPageIdByKeyWord(@Param("keyword") String keyword,
                                   @Param("keyword2") String keyword2,
                                   Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.status.title IN :liveStatuses
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR LOWER(COALESCE(o.status.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR STR(o.id) = :keyword)
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.status.title IN :liveStatuses
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR LOWER(COALESCE(o.status.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR STR(o.id) = :keyword)
            """
    )
    Page<Long> findPageIdByKeyWordLive(@Param("keyword") String keyword,
                                       @Param("keyword2") String keyword2,
                                       @Param("liveStatuses") Collection<String> liveStatuses,
                                       Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE o.manager = :manager
          AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
        ORDER BY o.changed, o.id
    """)
    List<Long> findAllIdByByManagerAndKeyWord(@Param("manager") Manager manager,
                                              @Param("keyword") String keyword,
                                              @Param("keyword2") String keyword2);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.manager = :manager
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(o.id) = :keyword)
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.manager = :manager
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(o.id) = :keyword)
            """
    )
    Page<Long> findPageIdByByManagerAndKeyWord(@Param("manager") Manager manager,
                                               @Param("keyword") String keyword,
                                               @Param("keyword2") String keyword2,
                                               Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.manager = :manager
                  AND o.status.title IN :liveStatuses
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR LOWER(COALESCE(o.status.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR STR(o.id) = :keyword)
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.manager = :manager
                  AND o.status.title IN :liveStatuses
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR LOWER(COALESCE(o.status.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR STR(o.id) = :keyword)
            """
    )
    Page<Long> findPageIdByByManagerAndKeyWordLive(@Param("manager") Manager manager,
                                                   @Param("keyword") String keyword,
                                                   @Param("keyword2") String keyword2,
                                                   @Param("liveStatuses") Collection<String> liveStatuses,
                                                   Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE o.worker = :worker
          AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
        ORDER BY o.changed, o.id
    """)
    List<Long> findAllIdByByWorkerAndKeyWord(@Param("worker") Worker worker,
                                             @Param("keyword") String keyword,
                                             @Param("keyword2") String keyword2);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.worker = :worker
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(o.id) = :keyword)
                ORDER BY o.changed ASC, o.id ASC
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.worker = :worker
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(o.id) = :keyword)
            """
    )
    Page<Long> findPageIdByByWorkerAndKeyWordForBoard(@Param("worker") Worker worker,
                                                      @Param("keyword") String keyword,
                                                      @Param("keyword2") String keyword2,
                                                      Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.worker = :worker
                  AND o.status.title IN :liveStatuses
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(o.id) = :keyword)
                ORDER BY o.changed ASC, o.id ASC
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.worker = :worker
                  AND o.status.title IN :liveStatuses
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(o.id) = :keyword)
            """
    )
    Page<Long> findPageIdByByWorkerAndKeyWordForBoardLive(@Param("worker") Worker worker,
                                                          @Param("keyword") String keyword,
                                                          @Param("keyword2") String keyword2,
                                                          @Param("liveStatuses") Collection<String> liveStatuses,
                                                          Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.worker = :worker
                  AND o.status.title IN :liveStatuses
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(o.id) = :keyword)
                ORDER BY
                  CASE WHEN :sortDirection = 'desc' THEN o.changed END ASC,
                  CASE WHEN :sortDirection = 'asc' THEN o.changed END DESC,
                  CASE WHEN :sortDirection = 'desc' THEN o.id END ASC,
                  CASE WHEN :sortDirection = 'asc' THEN o.id END DESC
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.worker = :worker
                  AND o.status.title IN :liveStatuses
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(o.id) = :keyword)
            """
    )
    Page<Long> findPageIdByByWorkerAndKeyWordForBoardLiveSorted(@Param("worker") Worker worker,
                                                                @Param("keyword") String keyword,
                                                                @Param("keyword2") String keyword2,
                                                                @Param("liveStatuses") Collection<String> liveStatuses,
                                                                @Param("sortDirection") String sortDirection,
                                                                Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE (o.manager IN :managers
          AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))))
        ORDER BY o.changed, o.id
    """)
    List<Long> findAllIdByOwnerAndKeyWord(@Param("managers") List<Manager> managers,
                                          @Param("keyword") String keyword,
                                          @Param("keyword2") String keyword2);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE (o.manager IN :managers
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                    OR STR(o.id) = :keyword))
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE (o.manager IN :managers
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                    OR STR(o.id) = :keyword))
            """
    )
    Page<Long> findPageIdByOwnerAndKeyWord(@Param("managers") List<Manager> managers,
                                           @Param("keyword") String keyword,
                                           @Param("keyword2") String keyword2,
                                           Pageable pageable);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.manager IN :managers
                  AND o.status.title IN :liveStatuses
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR LOWER(COALESCE(o.status.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR STR(o.id) = :keyword)
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.manager IN :managers
                  AND o.status.title IN :liveStatuses
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR LOWER(COALESCE(o.status.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR STR(o.id) = :keyword)
            """
    )
    Page<Long> findPageIdByOwnerAndKeyWordLive(@Param("managers") List<Manager> managers,
                                               @Param("keyword") String keyword,
                                               @Param("keyword2") String keyword2,
                                               @Param("liveStatuses") Collection<String> liveStatuses,
                                               Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
           OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2)
        ORDER BY o.changed, o.id
    """)
    List<Long> findAllIdByKeyWordAndStatus(@Param("keyword") String keyword,
                                           @Param("status") String status,
                                           @Param("keyword2") String keyword2,
                                           @Param("status2") String status2);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2)
                   OR (STR(o.id) = :keyword AND o.status.title = :status)
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2)
                   OR (STR(o.id) = :keyword AND o.status.title = :status)
            """
    )
    Page<Long> findPageIdByKeyWordAndStatus(@Param("keyword") String keyword,
                                            @Param("status") String status,
                                            @Param("keyword2") String keyword2,
                                            @Param("status2") String status2,
                                            Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE o.manager = :manager
          AND ((LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
           OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2))
        ORDER BY o.changed, o.id
    """)
    List<Long> findAllIdByManagerAndKeyWordAndStatus(@Param("manager") Manager manager,
                                                     @Param("keyword") String keyword,
                                                     @Param("status") String status,
                                                     @Param("keyword2") String keyword2,
                                                     @Param("status2") String status2);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.manager = :manager
                  AND ((LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2)
                   OR (STR(o.id) = :keyword AND o.status.title = :status))
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.manager = :manager
                  AND ((LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2)
                   OR (STR(o.id) = :keyword AND o.status.title = :status))
            """
    )
    Page<Long> findPageIdByManagerAndKeyWordAndStatus(@Param("manager") Manager manager,
                                                      @Param("keyword") String keyword,
                                                      @Param("status") String status,
                                                      @Param("keyword2") String keyword2,
                                                      @Param("status2") String status2,
                                                      Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE o.worker = :worker
          AND ((LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
           OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2))
        ORDER BY o.changed, o.id
    """)
    List<Long> findAllIdByWorkerAndKeyWordAndStatus(@Param("worker") Worker worker,
                                                    @Param("keyword") String keyword,
                                                    @Param("status") String status,
                                                    @Param("keyword2") String keyword2,
                                                    @Param("status2") String status2);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.worker = :worker
                  AND ((LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2)
                   OR (STR(o.id) = :keyword AND o.status.title = :status))
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.worker = :worker
                  AND ((LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2)
                   OR (STR(o.id) = :keyword AND o.status.title = :status))
            """
    )
    Page<Long> findPageIdByWorkerAndKeyWordAndStatus(@Param("worker") Worker worker,
                                                     @Param("keyword") String keyword,
                                                     @Param("status") String status,
                                                     @Param("keyword2") String keyword2,
                                                     @Param("status2") String status2,
                                                     Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE (o.manager IN :managers AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status))
           OR (o.manager IN :managers AND (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2))
        ORDER BY o.changed, o.id
    """)
    List<Long> findAllIdByOwnerAndKeyWordAndStatus(@Param("managers") List<Manager> managers,
                                                   @Param("keyword") String keyword,
                                                   @Param("status") String status,
                                                   @Param("keyword2") String keyword2,
                                                   @Param("status2") String status2);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE (o.manager IN :managers AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status))
                   OR (o.manager IN :managers AND (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2))
                   OR (o.manager IN :managers AND (STR(o.id) = :keyword AND o.status.title = :status))
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE (o.manager IN :managers AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status))
                   OR (o.manager IN :managers AND (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2))
                   OR (o.manager IN :managers AND (STR(o.id) = :keyword AND o.status.title = :status))
            """
    )
    Page<Long> findPageIdByOwnerAndKeyWordAndStatus(@Param("managers") List<Manager> managers,
                                                    @Param("keyword") String keyword,
                                                    @Param("status") String status,
                                                    @Param("keyword2") String keyword2,
                                                    @Param("status2") String status2,
                                                    Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.company.id = :companyId ORDER BY o.changed, o.id")
    List<Long> findAllIdByCompanyId(@Param("companyId") long companyId);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.company.id = :companyId",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.company.id = :companyId"
    )
    Page<Long> findPageIdByCompanyId(@Param("companyId") long companyId,
                                     Pageable pageable);

    @Query("""
        SELECT CASE WHEN COUNT(o.id) > 0 THEN true ELSE false END
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.company.id = :companyId
          AND o.complete = false
          AND COALESCE(s.title, '') NOT IN :inactiveStatuses
    """)
    boolean existsActiveOrderByCompanyId(@Param("companyId") Long companyId,
                                         @Param("inactiveStatuses") Set<String> inactiveStatuses);

    @Query("""
        SELECT CASE WHEN COUNT(o.id) > 0 THEN true ELSE false END
        FROM Order o
        LEFT JOIN o.status s
        LEFT JOIN o.worker w
        WHERE o.company.id = :companyId
          AND ((:filialId IS NULL AND o.filial IS NULL) OR (:filialId IS NOT NULL AND o.filial.id = :filialId))
          AND (:excludedOrderId IS NULL OR o.id <> :excludedOrderId)
          AND (:workerId IS NULL OR w.id = :workerId)
          AND o.complete = false
          AND COALESCE(s.title, '') NOT IN :inactiveStatuses
    """)
    boolean existsActiveOrderByCompanyIdAndFilialId(@Param("companyId") Long companyId,
                                                    @Param("filialId") Long filialId,
                                                    @Param("workerId") Long workerId,
                                                    @Param("excludedOrderId") Long excludedOrderId,
                                                    @Param("inactiveStatuses") Set<String> inactiveStatuses);

    @Query("""
        SELECT CASE WHEN COUNT(DISTINCT o.id) > 0 THEN true ELSE false END
        FROM Order o
        LEFT JOIN o.status s
        LEFT JOIN o.worker w
        LEFT JOIN o.details d
        LEFT JOIN d.reviews r
        WHERE o.company.id = :companyId
          AND (:excludedOrderId IS NULL OR o.id <> :excludedOrderId)
          AND (:workerId IS NULL OR w.id = :workerId)
          AND o.complete = false
          AND COALESCE(s.title, '') NOT IN :inactiveStatuses
          AND (o.filial.id IN :filialIds OR r.filial.id IN :filialIds)
    """)
    boolean existsActiveOrderByCompanyIdAndAnyFilialId(@Param("companyId") Long companyId,
                                                       @Param("filialIds") Collection<Long> filialIds,
                                                       @Param("workerId") Long workerId,
                                                       @Param("excludedOrderId") Long excludedOrderId,
                                                       @Param("inactiveStatuses") Set<String> inactiveStatuses);

    @Query("""
        SELECT o
        FROM Order o
        LEFT JOIN FETCH o.status s
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH o.filial f
        LEFT JOIN FETCH f.city
        WHERE o.company.id = :companyId
          AND ((:filialId IS NULL AND o.filial IS NULL) OR (:filialId IS NOT NULL AND o.filial.id = :filialId))
          AND (:excludedOrderId IS NULL OR o.id <> :excludedOrderId)
          AND (:workerId IS NULL OR w.id = :workerId)
          AND o.complete = false
          AND COALESCE(s.title, '') NOT IN :inactiveStatuses
        ORDER BY o.id DESC
    """)
    List<Order> findActiveOrdersByCompanyIdAndFilialId(@Param("companyId") Long companyId,
                                                       @Param("filialId") Long filialId,
                                                       @Param("workerId") Long workerId,
                                                       @Param("excludedOrderId") Long excludedOrderId,
                                                       @Param("inactiveStatuses") Set<String> inactiveStatuses,
                                                       Pageable pageable);

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.status s
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH o.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN o.details d
        LEFT JOIN d.reviews r
        WHERE o.company.id = :companyId
          AND (:excludedOrderId IS NULL OR o.id <> :excludedOrderId)
          AND (:workerId IS NULL OR w.id = :workerId)
          AND o.complete = false
          AND COALESCE(s.title, '') NOT IN :inactiveStatuses
          AND (o.filial.id IN :filialIds OR r.filial.id IN :filialIds)
        ORDER BY o.id DESC
    """)
    List<Order> findActiveOrdersByCompanyIdAndAnyFilialId(@Param("companyId") Long companyId,
                                                          @Param("filialIds") Collection<Long> filialIds,
                                                          @Param("workerId") Long workerId,
                                                          @Param("excludedOrderId") Long excludedOrderId,
                                                          @Param("inactiveStatuses") Set<String> inactiveStatuses,
                                                          Pageable pageable);

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.status s
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH o.filial f
        LEFT JOIN FETCH f.city
        WHERE c.id = :companyId
          AND o.complete = false
          AND s.title IN :statuses
        ORDER BY o.id ASC
    """)
    List<Order> findCommonBillingBackfillOrders(@Param("companyId") Long companyId,
                                                @Param("statuses") Collection<String> statuses);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE o.company.id = :companyId
          AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
        ORDER BY o.changed, o.id
    """)
    List<Long> findAllIdByCompanyIdAndKeyWord(@Param("companyId") long companyId,
                                              @Param("keyword") String keyword,
                                              @Param("keyword2") String keyword2);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.company.id = :companyId
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR LOWER(COALESCE(o.status.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR STR(o.id) = :keyword)
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.company.id = :companyId
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR LOWER(COALESCE(o.status.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR STR(o.id) = :keyword)
            """
    )
    Page<Long> findPageIdByCompanyIdAndKeyWord(@Param("companyId") long companyId,
                                               @Param("keyword") String keyword,
                                               @Param("keyword2") String keyword2,
                                               Pageable pageable);

    @Query("SELECT COUNT(o.id) FROM Order o WHERE o.status.title = :status")
    int countByStatusTitle(@Param("status") String status);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByStatus();

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE s.title IN :liveStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByStatusLive(@Param("liveStatuses") Collection<String> liveStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.waitingForClient = false
          AND s.title IN :liveStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByActionableStatus(@Param("liveStatuses") Collection<String> liveStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id), MIN(o.changed)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.complete = false
          AND o.changed IS NOT NULL
          AND o.changed <= :cutoff
          AND COALESCE(s.title, '') NOT IN :excludedStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> summarizeOverdueOrders(@Param("cutoff") LocalDate cutoff,
                                          @Param("excludedStatuses") Set<String> excludedStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id), MIN(o.changed)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.complete = false
          AND o.changed IS NOT NULL
          AND o.changed <= :cutoff
          AND o.manager = :manager
          AND COALESCE(s.title, '') NOT IN :excludedStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> summarizeOverdueOrdersByManager(@Param("manager") Manager manager,
                                                   @Param("cutoff") LocalDate cutoff,
                                                   @Param("excludedStatuses") Set<String> excludedStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id), MIN(o.changed)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.complete = false
          AND o.changed IS NOT NULL
          AND o.changed <= :cutoff
          AND o.manager = :manager
          AND COALESCE(s.title, '') NOT IN :excludedStatuses
          AND NOT EXISTS (
              SELECT item.id
              FROM CommonInvoiceOrder item
              JOIN item.invoice invoice
              WHERE item.order = o
                AND invoice.status IN :commonInvoiceStatuses
          )
          AND (
              COALESCE(s.title, '') NOT IN :paymentAutomationStatuses
              OR (
                  o.company.urlChat IS NOT NULL
                  AND TRIM(o.company.urlChat) <> ''
                  AND (
                      (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                      AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                      OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                      AND o.company.telegramGroupChatId IS NULL
                      OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                      AND o.company.maxGroupChatId IS NULL
                  )
              )
              OR NOT EXISTS (
                  SELECT state.id
                  FROM ScheduledClientMessageState state
                  WHERE state.orderId = o.id
                    AND state.scenario IN :paymentScenarios
                    AND state.consecutiveFailures = 0
                    AND (
                        state.lastErrorCode IS NULL
                        OR TRIM(state.lastErrorCode) = ''
                        OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                    )
                    AND (
                        state.sentCount > 0
                        OR state.lastSuccessAt IS NOT NULL
                      OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                  )
              )
          )
          AND (
              COALESCE(s.title, '') NOT IN :reviewCheckAutomationStatuses
              OR (
                  o.company.urlChat IS NOT NULL
                  AND TRIM(o.company.urlChat) <> ''
                  AND (
                      (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                      AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                      OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                      AND o.company.telegramGroupChatId IS NULL
                      OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                      AND o.company.maxGroupChatId IS NULL
                  )
              )
              OR NOT EXISTS (
                  SELECT state.id
                  FROM ScheduledClientMessageState state
                  WHERE state.orderId = o.id
                    AND state.scenario IN :reviewCheckScenarios
                    AND state.consecutiveFailures = 0
                    AND (
                        state.lastErrorCode IS NULL
                        OR TRIM(state.lastErrorCode) = ''
                        OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                    )
                    AND (
                        state.sentCount > 0
                        OR state.lastSuccessAt IS NOT NULL
                      OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                  )
              )
          )
          AND (
              COALESCE(s.title, '') NOT IN :deliveryRetryAutomationStatuses
              OR (
                  o.company.urlChat IS NOT NULL
                  AND TRIM(o.company.urlChat) <> ''
                  AND (
                      (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                      AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                      OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                      AND o.company.telegramGroupChatId IS NULL
                      OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                      AND o.company.maxGroupChatId IS NULL
                  )
              )
              OR NOT EXISTS (
                  SELECT state.id
                  FROM ScheduledClientMessageState state
                  WHERE state.orderId = o.id
                    AND state.scenario IN :deliveryRetryScenarios
                    AND state.consecutiveFailures = 0
                    AND (
                        state.lastErrorCode IS NULL
                        OR TRIM(state.lastErrorCode) = ''
                        OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                    )
                    AND (
                        state.sentCount > 0
                        OR state.lastSuccessAt IS NOT NULL
                        OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                    )
              )
          )
          AND (
              o.waitingForClient = false
              OR COALESCE(s.title, '') NOT IN :clientTextAutomationStatuses
              OR (
                  o.company.urlChat IS NOT NULL
                  AND TRIM(o.company.urlChat) <> ''
                  AND (
                      (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                      AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                      OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                      AND o.company.telegramGroupChatId IS NULL
                      OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                      AND o.company.maxGroupChatId IS NULL
                  )
              )
              OR NOT EXISTS (
                  SELECT state.id
                  FROM ScheduledClientMessageState state
                  WHERE state.orderId = o.id
                    AND state.scenario IN :clientTextScenarios
                    AND state.consecutiveFailures = 0
                    AND (
                        state.lastErrorCode IS NULL
                        OR TRIM(state.lastErrorCode) = ''
                        OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                    )
                    AND (
                        state.sentCount > 0
                        OR state.lastSuccessAt IS NOT NULL
                        OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                    )
              )
          )
        GROUP BY s.id, s.title
    """)
    List<Object[]> summarizeManagerControlOverdueOrdersByManager(
            @Param("manager") Manager manager,
            @Param("cutoff") LocalDate cutoff,
            @Param("excludedStatuses") Set<String> excludedStatuses,
            @Param("commonInvoiceStatuses") Set<CommonInvoiceStatus> commonInvoiceStatuses,
            @Param("paymentAutomationStatuses") Set<String> paymentAutomationStatuses,
            @Param("paymentScenarios") Set<ClientMessageScenario> paymentScenarios,
            @Param("reviewCheckAutomationStatuses") Set<String> reviewCheckAutomationStatuses,
            @Param("reviewCheckScenarios") Set<ClientMessageScenario> reviewCheckScenarios,
            @Param("deliveryRetryAutomationStatuses") Set<String> deliveryRetryAutomationStatuses,
            @Param("deliveryRetryScenarios") Set<ClientMessageScenario> deliveryRetryScenarios,
            @Param("clientTextAutomationStatuses") Set<String> clientTextAutomationStatuses,
            @Param("clientTextScenarios") Set<ClientMessageScenario> clientTextScenarios,
            @Param("activeStatus") ScheduledMessageStateStatus activeStatus,
            @Param("doneStatus") ScheduledMessageStateStatus doneStatus
    );

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                LEFT JOIN o.status s
                WHERE o.complete = false
                  AND o.changed IS NOT NULL
                  AND o.changed <= :cutoff
                  AND o.manager = :manager
                  AND COALESCE(s.title, '') NOT IN :excludedStatuses
                  AND (:status = 'Все' OR COALESCE(s.title, '') = :status)
                  AND NOT EXISTS (
                      SELECT item.id
                      FROM CommonInvoiceOrder item
                      JOIN item.invoice invoice
                      WHERE item.order = o
                        AND invoice.status IN :commonInvoiceStatuses
                  )
                  AND (
                      :keyword = ''
                      OR LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                      OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                      OR STR(o.id) = :keyword
                  )
                  AND (
                      COALESCE(s.title, '') NOT IN :paymentAutomationStatuses
                      OR (
                          o.company.urlChat IS NOT NULL
                          AND TRIM(o.company.urlChat) <> ''
                          AND (
                              (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                              AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                              AND o.company.telegramGroupChatId IS NULL
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                              AND o.company.maxGroupChatId IS NULL
                          )
                      )
                      OR NOT EXISTS (
                          SELECT state.id
                          FROM ScheduledClientMessageState state
                          WHERE state.orderId = o.id
                            AND state.scenario IN :paymentScenarios
                            AND state.consecutiveFailures = 0
                            AND (
                                state.lastErrorCode IS NULL
                                OR TRIM(state.lastErrorCode) = ''
                                OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                                OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                                OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                            )
                            AND (
                                state.sentCount > 0
                                OR state.lastSuccessAt IS NOT NULL
                              OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                          )
                      )
                  )
                  AND (
                      COALESCE(s.title, '') NOT IN :reviewCheckAutomationStatuses
                      OR (
                          o.company.urlChat IS NOT NULL
                          AND TRIM(o.company.urlChat) <> ''
                          AND (
                              (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                              AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                              AND o.company.telegramGroupChatId IS NULL
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                              AND o.company.maxGroupChatId IS NULL
                          )
                      )
                      OR NOT EXISTS (
                          SELECT state.id
                          FROM ScheduledClientMessageState state
                          WHERE state.orderId = o.id
                            AND state.scenario IN :reviewCheckScenarios
                            AND state.consecutiveFailures = 0
                            AND (
                                state.lastErrorCode IS NULL
                                OR TRIM(state.lastErrorCode) = ''
                                OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                                OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                                OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                            )
                            AND (
                                state.sentCount > 0
                                OR state.lastSuccessAt IS NOT NULL
                              OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                          )
                      )
                  )
                  AND (
                      COALESCE(s.title, '') NOT IN :deliveryRetryAutomationStatuses
                      OR (
                          o.company.urlChat IS NOT NULL
                          AND TRIM(o.company.urlChat) <> ''
                          AND (
                              (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                              AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                              AND o.company.telegramGroupChatId IS NULL
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                              AND o.company.maxGroupChatId IS NULL
                          )
                      )
                      OR NOT EXISTS (
                          SELECT state.id
                          FROM ScheduledClientMessageState state
                          WHERE state.orderId = o.id
                            AND state.scenario IN :deliveryRetryScenarios
                            AND state.consecutiveFailures = 0
                            AND (
                                state.lastErrorCode IS NULL
                                OR TRIM(state.lastErrorCode) = ''
                                OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                                OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                                OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                            )
                            AND (
                                state.sentCount > 0
                                OR state.lastSuccessAt IS NOT NULL
                                OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                            )
                      )
                  )
                  AND (
                      o.waitingForClient = false
                      OR COALESCE(s.title, '') NOT IN :clientTextAutomationStatuses
                      OR (
                          o.company.urlChat IS NOT NULL
                          AND TRIM(o.company.urlChat) <> ''
                          AND (
                              (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                              AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                              AND o.company.telegramGroupChatId IS NULL
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                              AND o.company.maxGroupChatId IS NULL
                          )
                      )
                      OR NOT EXISTS (
                          SELECT state.id
                          FROM ScheduledClientMessageState state
                          WHERE state.orderId = o.id
                            AND state.scenario IN :clientTextScenarios
                            AND state.consecutiveFailures = 0
                            AND (
                                state.lastErrorCode IS NULL
                                OR TRIM(state.lastErrorCode) = ''
                                OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                                OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                                OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                            )
                            AND (
                                state.sentCount > 0
                                OR state.lastSuccessAt IS NOT NULL
                                OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                            )
                      )
                  )
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                LEFT JOIN o.status s
                WHERE o.complete = false
                  AND o.changed IS NOT NULL
                  AND o.changed <= :cutoff
                  AND o.manager = :manager
                  AND COALESCE(s.title, '') NOT IN :excludedStatuses
                  AND (:status = 'Все' OR COALESCE(s.title, '') = :status)
                  AND NOT EXISTS (
                      SELECT item.id
                      FROM CommonInvoiceOrder item
                      JOIN item.invoice invoice
                      WHERE item.order = o
                        AND invoice.status IN :commonInvoiceStatuses
                  )
                  AND (
                      :keyword = ''
                      OR LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                      OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                      OR STR(o.id) = :keyword
                  )
                  AND (
                      COALESCE(s.title, '') NOT IN :paymentAutomationStatuses
                      OR (
                          o.company.urlChat IS NOT NULL
                          AND TRIM(o.company.urlChat) <> ''
                          AND (
                              (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                              AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                              AND o.company.telegramGroupChatId IS NULL
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                              AND o.company.maxGroupChatId IS NULL
                          )
                      )
                      OR NOT EXISTS (
                          SELECT state.id
                          FROM ScheduledClientMessageState state
                          WHERE state.orderId = o.id
                            AND state.scenario IN :paymentScenarios
                            AND state.consecutiveFailures = 0
                            AND (
                                state.lastErrorCode IS NULL
                                OR TRIM(state.lastErrorCode) = ''
                                OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                                OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                                OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                            )
                            AND (
                                state.sentCount > 0
                                OR state.lastSuccessAt IS NOT NULL
                              OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                          )
                      )
                  )
                  AND (
                      COALESCE(s.title, '') NOT IN :reviewCheckAutomationStatuses
                      OR (
                          o.company.urlChat IS NOT NULL
                          AND TRIM(o.company.urlChat) <> ''
                          AND (
                              (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                              AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                              AND o.company.telegramGroupChatId IS NULL
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                              AND o.company.maxGroupChatId IS NULL
                          )
                      )
                      OR NOT EXISTS (
                          SELECT state.id
                          FROM ScheduledClientMessageState state
                          WHERE state.orderId = o.id
                            AND state.scenario IN :reviewCheckScenarios
                            AND state.consecutiveFailures = 0
                            AND (
                                state.lastErrorCode IS NULL
                                OR TRIM(state.lastErrorCode) = ''
                                OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                                OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                                OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                            )
                            AND (
                                state.sentCount > 0
                                OR state.lastSuccessAt IS NOT NULL
                              OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                          )
                      )
                  )
                  AND (
                      COALESCE(s.title, '') NOT IN :deliveryRetryAutomationStatuses
                      OR (
                          o.company.urlChat IS NOT NULL
                          AND TRIM(o.company.urlChat) <> ''
                          AND (
                              (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                              AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                              AND o.company.telegramGroupChatId IS NULL
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                              AND o.company.maxGroupChatId IS NULL
                          )
                      )
                      OR NOT EXISTS (
                          SELECT state.id
                          FROM ScheduledClientMessageState state
                          WHERE state.orderId = o.id
                            AND state.scenario IN :deliveryRetryScenarios
                            AND state.consecutiveFailures = 0
                            AND (
                                state.lastErrorCode IS NULL
                                OR TRIM(state.lastErrorCode) = ''
                                OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                                OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                                OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                            )
                            AND (
                                state.sentCount > 0
                                OR state.lastSuccessAt IS NOT NULL
                                OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                            )
                      )
                  )
                  AND (
                      o.waitingForClient = false
                      OR COALESCE(s.title, '') NOT IN :clientTextAutomationStatuses
                      OR (
                          o.company.urlChat IS NOT NULL
                          AND TRIM(o.company.urlChat) <> ''
                          AND (
                              (LOWER(TRIM(o.company.urlChat)) LIKE 'chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://chat.whatsapp.com/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://chat.whatsapp.com/%')
                              AND (o.company.groupId IS NULL OR TRIM(o.company.groupId) = '')
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 't.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://t.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.me/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://telegram.dog/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'tg://resolve?%')
                              AND o.company.telegramGroupChatId IS NULL
                              OR (LOWER(TRIM(o.company.urlChat)) LIKE 'max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'https://web.max.ru/%' OR LOWER(TRIM(o.company.urlChat)) LIKE 'http://web.max.ru/%')
                              AND o.company.maxGroupChatId IS NULL
                          )
                      )
                      OR NOT EXISTS (
                          SELECT state.id
                          FROM ScheduledClientMessageState state
                          WHERE state.orderId = o.id
                            AND state.scenario IN :clientTextScenarios
                            AND state.consecutiveFailures = 0
                            AND (
                                state.lastErrorCode IS NULL
                                OR TRIM(state.lastErrorCode) = ''
                                OR LOWER(state.lastErrorCode) LIKE '%dry_run%'
                                OR LOWER(state.lastErrorCode) LIKE '%order_status_changed%'
                                OR LOWER(state.lastErrorCode) LIKE '%status_change%'
                        OR LOWER(state.lastErrorCode) LIKE '%order_closed%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_received%'
                        OR LOWER(state.lastErrorCode) LIKE '%client_text_cycle_changed%'
                        OR LOWER(state.lastErrorCode) LIKE '%common_billing_linked%'
                            )
                            AND (
                                state.sentCount > 0
                                OR state.lastSuccessAt IS NOT NULL
                                OR (state.status = :activeStatus AND state.nextAttemptAt IS NOT NULL)
                        OR state.status = :doneStatus
                            )
                      )
                  )
            """
    )
    Page<Long> findPageIdForManagerControlOverdueByManager(
            @Param("manager") Manager manager,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("keyword2") String keyword2,
            @Param("cutoff") LocalDate cutoff,
            @Param("excludedStatuses") Set<String> excludedStatuses,
            @Param("commonInvoiceStatuses") Set<CommonInvoiceStatus> commonInvoiceStatuses,
            @Param("paymentAutomationStatuses") Set<String> paymentAutomationStatuses,
            @Param("paymentScenarios") Set<ClientMessageScenario> paymentScenarios,
            @Param("reviewCheckAutomationStatuses") Set<String> reviewCheckAutomationStatuses,
            @Param("reviewCheckScenarios") Set<ClientMessageScenario> reviewCheckScenarios,
            @Param("deliveryRetryAutomationStatuses") Set<String> deliveryRetryAutomationStatuses,
            @Param("deliveryRetryScenarios") Set<ClientMessageScenario> deliveryRetryScenarios,
            @Param("clientTextAutomationStatuses") Set<String> clientTextAutomationStatuses,
            @Param("clientTextScenarios") Set<ClientMessageScenario> clientTextScenarios,
            @Param("activeStatus") ScheduledMessageStateStatus activeStatus,
            @Param("doneStatus") ScheduledMessageStateStatus doneStatus,
            Pageable pageable
    );

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id), MIN(o.changed)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.complete = false
          AND o.changed IS NOT NULL
          AND o.changed <= :cutoff
          AND o.manager IN :managers
          AND COALESCE(s.title, '') NOT IN :excludedStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> summarizeOverdueOrdersByManagers(@Param("managers") Set<Manager> managers,
                                                    @Param("cutoff") LocalDate cutoff,
                                                    @Param("excludedStatuses") Set<String> excludedStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id), MIN(o.changed)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.complete = false
          AND o.changed IS NOT NULL
          AND o.changed <= :cutoff
          AND o.worker = :worker
          AND COALESCE(s.title, '') NOT IN :excludedStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> summarizeOverdueOrdersByWorker(@Param("worker") Worker worker,
                                                  @Param("cutoff") LocalDate cutoff,
                                                  @Param("excludedStatuses") Set<String> excludedStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.manager = :manager
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByStatusAndManager(@Param("manager") Manager manager);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.manager = :manager
          AND s.title IN :liveStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByStatusAndManagerLive(@Param("manager") Manager manager,
                                                      @Param("liveStatuses") Collection<String> liveStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.manager = :manager
          AND o.waitingForClient = false
          AND s.title IN :liveStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByActionableStatusAndManager(@Param("manager") Manager manager,
                                                            @Param("liveStatuses") Collection<String> liveStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.manager IN :managers
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByStatusAndManagers(@Param("managers") Set<Manager> managers);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.manager IN :managers
          AND s.title IN :liveStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByStatusAndManagersLive(@Param("managers") Set<Manager> managers,
                                                       @Param("liveStatuses") Collection<String> liveStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.manager IN :managers
          AND o.waitingForClient = false
          AND s.title IN :liveStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByActionableStatusAndManagers(@Param("managers") Set<Manager> managers,
                                                             @Param("liveStatuses") Collection<String> liveStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.worker = :worker
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByStatusAndWorker(@Param("worker") Worker worker);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.worker = :worker
          AND s.title IN :liveStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByStatusAndWorkerLive(@Param("worker") Worker worker,
                                                     @Param("liveStatuses") Collection<String> liveStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.worker = :worker
          AND o.waitingForClient = false
          AND s.title IN :liveStatuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByActionableStatusAndWorker(@Param("worker") Worker worker,
                                                           @Param("liveStatuses") Collection<String> liveStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.worker = :worker
          AND o.waitingForClient = false
          AND o.changed IS NOT NULL
          AND o.changed <= :cutoff
          AND COALESCE(s.title, '') IN :statuses
        GROUP BY s.id, s.title
    """)
    List<Object[]> countGroupedByActionableStatusAndWorkerChangedOnOrBefore(@Param("worker") Worker worker,
                                                                            @Param("statuses") Set<String> statuses,
                                                                            @Param("cutoff") LocalDate cutoff);

    @Query("SELECT COUNT(o.id) FROM Order o")
    int countAllOrders();

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH w.user
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.status
        WHERE o.complete = true
          AND o.payDay BETWEEN :from AND :to
    """)
    List<Order> findPaidForGamificationBackfill(@Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    @Query("SELECT COUNT(o.id) FROM Order o WHERE o.manager = :manager")
    int countByManager(@Param("manager") Manager manager);

    @Query("SELECT COUNT(o.id) FROM Order o WHERE o.manager IN :managers")
    int countByManagers(@Param("managers") Set<Manager> managers);

    @Query("SELECT COUNT(o.id) FROM Order o WHERE o.worker = :worker")
    int countByWorker(@Param("worker") Worker worker);

    @Query("SELECT COUNT(o.id) FROM Order o WHERE o.manager = :manager AND o.status.title = :status")
    int countByManagerAndStatusTitle(@Param("manager") Manager manager,
                                     @Param("status") String status);

    @Query("SELECT COUNT(o.id) FROM Order o WHERE o.manager IN :managers AND o.status.title = :status")
    int countByManagersAndStatusTitle(@Param("managers") Set<Manager> managers,
                                      @Param("status") String status);

    @Query("SELECT COUNT(o.id) FROM Order o WHERE o.worker = :worker AND o.status.title = :status")
    int countByWorkerAndStatus(@Param("worker") Worker worker,
                               @Param("status") String status);

    @Query("""
        SELECT o.worker.id, COUNT(o.id)
        FROM Order o
        WHERE o.worker.id IN :workerIds
          AND o.status.title = :status
        GROUP BY o.worker.id
    """)
    List<Object[]> countByWorkerIdsAndStatus(@Param("workerIds") Collection<Long> workerIds,
                                             @Param("status") String status);

    @Query("""
        SELECT
            'operator' AS type,
            wu.fio AS fio,
            COUNT(CASE WHEN o.status.title = :statusNew THEN 1 ELSE NULL END) AS newOrders,
            COUNT(CASE WHEN o.status.title = :statusCorrect THEN 1 ELSE NULL END) AS correctOrders
        FROM Order o
        LEFT JOIN o.worker w
        LEFT JOIN w.user wu
        WHERE wu.fio IS NOT NULL
          AND o.status.title IN :statuses
        GROUP BY wu.fio

        UNION ALL

        SELECT
            'manager' AS type,
            COALESCE(mu.fio, 'Без менеджера') AS fio,
            COUNT(CASE WHEN o.status.title = :statusNew THEN 1 ELSE NULL END) AS newOrders,
            COUNT(CASE WHEN o.status.title = :statusCorrect THEN 1 ELSE NULL END) AS correctOrders
        FROM Order o
        LEFT JOIN o.manager m
        LEFT JOIN m.user mu
        WHERE mu.fio IS NOT NULL
          AND o.status.title IN :statuses
        GROUP BY mu.fio
    """)
    List<Object[]> findAllIdByNewOrderAllStatus(@Param("statusNew") String statusNew,
                                                @Param("statusCorrect") String statusCorrect,
                                                @Param("statuses") Collection<String> statuses);

    @Query("""
        SELECT
            w.user.fio AS workerFio,
            COUNT(CASE WHEN o.status.title = :status THEN 1 END) AS workerOrderCount,
            m_user.fio AS managerFio,
            COUNT(CASE WHEN o.status.title = :status THEN 1 END) AS managerOrderCount
        FROM Order o
        LEFT JOIN o.worker w
        LEFT JOIN w.user
        LEFT JOIN o.manager m
        JOIN m.user m_user
        WHERE o.complete = true
          AND o.payDay BETWEEN :firstDayOfMonth AND :lastDayOfMonth
        GROUP BY w.user.fio, m_user.fio
    """)
    List<Object[]> getAllOrdersToMonth(@Param("status") String status,
                                       @Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                       @Param("lastDayOfMonth") LocalDate lastDayOfMonth);

    @Query("""
        SELECT
            u.fio AS fio,
            o.status.title AS status,
            COUNT(o.id) AS count,
            'worker' AS role
        FROM Order o
        LEFT JOIN o.worker w
        LEFT JOIN w.user u
        WHERE o.complete = false
          AND o.changed BETWEEN :firstDayOfMonth AND :lastDayOfMonth
          AND o.status.title IN (:statuses)
        GROUP BY u.fio, o.status.title

        UNION ALL

        SELECT
            m_user.fio AS fio,
            o.status.title AS status,
            COUNT(o.id) AS count,
            'manager' AS role
        FROM Order o
        LEFT JOIN o.manager m
        LEFT JOIN m.user m_user
        WHERE o.complete = false
          AND o.changed BETWEEN :firstDayOfMonth AND :lastDayOfMonth
          AND o.status.title IN (:statuses)
        GROUP BY m_user.fio, o.status.title
    """)
    List<Object[]> getOrdersByStatusForUsers(@Param("statuses") List<String> statuses,
                                             @Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                             @Param("lastDayOfMonth") LocalDate lastDayOfMonth);
}
