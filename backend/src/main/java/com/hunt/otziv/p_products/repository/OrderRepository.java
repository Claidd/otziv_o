package com.hunt.otziv.p_products.repository;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.util.Pair;
import org.springframework.lang.NonNullApi;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {

    @Override
    List<Order> findAll();

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.details
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH o.filial
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.categoryCompany
        LEFT JOIN FETCH c.subCategory
        LEFT JOIN FETCH c.status
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH w.user
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        WHERE o.id IN :orderId
        ORDER BY o.changed DESC
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
            s.title,
            o.sum,
            c.urlChat,
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
            END
        FROM Order o
        LEFT JOIN o.details d
        LEFT JOIN o.status s
        LEFT JOIN o.filial f
        LEFT JOIN o.company c
        LEFT JOIN c.categoryCompany cat
        LEFT JOIN c.subCategory sub
        LEFT JOIN o.worker w
        LEFT JOIN w.user wu
        LEFT JOIN o.manager m
        WHERE o.id IN :orderId
        ORDER BY o.changed DESC
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

    @Query("SELECT o.id FROM Order o ORDER BY o.changed")
    List<Long> findAllIdToAdmin();

    @Query(
            value = "SELECT o.id FROM Order o",
            countQuery = "SELECT COUNT(o.id) FROM Order o"
    )
    Page<Long> findPageIdToAdmin(Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.manager = :manager ORDER BY o.changed")
    List<Long> findAllIdToManager(@Param("manager") Manager manager);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.manager = :manager",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.manager = :manager"
    )
    Page<Long> findPageIdToManager(@Param("manager") Manager manager,
                                   Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.worker = :worker ORDER BY o.changed")
    List<Long> findAllIdToWorker(@Param("worker") Worker worker);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE o.worker = :worker
                ORDER BY o.waitingForClient ASC, CASE WHEN o.status.title = 'Публикация' THEN 0 ELSE 1 END, o.changed DESC
            """,
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.worker = :worker"
    )
    Page<Long> findPageIdToWorkerForBoard(@Param("worker") Worker worker,
                                          Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.manager IN :managers ORDER BY o.changed")
    List<Long> findAllIdToOwner(@Param("managers") List<Manager> managers);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.manager IN :managers",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.manager IN :managers"
    )
    Page<Long> findPageIdToOwner(@Param("managers") List<Manager> managers,
                                 Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.status.title = :status ORDER BY o.changed")
    List<Long> findAllIdByStatus(@Param("status") String status);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.status.title = :status",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.status.title = :status"
    )
    Page<Long> findPageIdByStatus(@Param("status") String status,
                                  Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.manager = :manager AND o.status.title = :status ORDER BY o.changed")
    List<Long> findAllIdByManagerAndStatus(@Param("manager") Manager manager,
                                           @Param("status") String status);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.manager = :manager AND o.status.title = :status",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.manager = :manager AND o.status.title = :status"
    )
    Page<Long> findPageIdByManagerAndStatus(@Param("manager") Manager manager,
                                            @Param("status") String status,
                                            Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.worker = :worker AND o.status.title = :status ORDER BY o.changed")
    List<Long> findAllIdByWorkerAndStatus(@Param("worker") Worker worker,
                                          @Param("status") String status);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.worker = :worker AND o.status.title = :status",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.worker = :worker AND o.status.title = :status"
    )
    Page<Long> findPageIdByWorkerAndStatus(@Param("worker") Worker worker,
                                           @Param("status") String status,
                                           Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.manager IN :managers AND o.status.title = :status ORDER BY o.changed")
    List<Long> findAllIdByOwnerAndStatus(@Param("managers") List<Manager> managers,
                                         @Param("status") String status);

    @Query(
            value = "SELECT o.id FROM Order o WHERE o.manager IN :managers AND o.status.title = :status",
            countQuery = "SELECT COUNT(o.id) FROM Order o WHERE o.manager IN :managers AND o.status.title = :status"
    )
    Page<Long> findPageIdByOwnerAndStatus(@Param("managers") List<Manager> managers,
                                          @Param("status") String status,
                                          Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.manager IN :managers AND o.status.title = :status ORDER BY o.changed")
    List<Long> findAllIdByOwnerAndStatus(@Param("managers") Set<Manager> managers,
                                         @Param("status") String status);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
        ORDER BY o.changed
    """)
    List<Long> findAllIdByKeyWord(@Param("keyword") String keyword,
                                  @Param("keyword2") String keyword2);

    @Query(
            value = """
                SELECT o.id
                FROM Order o
                WHERE LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
            """
    )
    Page<Long> findPageIdByKeyWord(@Param("keyword") String keyword,
                                   @Param("keyword2") String keyword2,
                                   Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE o.manager = :manager
          AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
        ORDER BY o.changed
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
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.manager = :manager
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
            """
    )
    Page<Long> findPageIdByByManagerAndKeyWord(@Param("manager") Manager manager,
                                               @Param("keyword") String keyword,
                                               @Param("keyword2") String keyword2,
                                               Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE o.worker = :worker
          AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
        ORDER BY o.changed
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
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
                ORDER BY o.waitingForClient ASC, CASE WHEN o.status.title = 'Публикация' THEN 0 ELSE 1 END, o.changed DESC
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.worker = :worker
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
            """
    )
    Page<Long> findPageIdByByWorkerAndKeyWordForBoard(@Param("worker") Worker worker,
                                                      @Param("keyword") String keyword,
                                                      @Param("keyword2") String keyword2,
                                                      Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE (o.manager IN :managers
          AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))))
        ORDER BY o.changed
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
                    OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))))
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE (o.manager IN :managers
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))))
            """
    )
    Page<Long> findPageIdByOwnerAndKeyWord(@Param("managers") List<Manager> managers,
                                           @Param("keyword") String keyword,
                                           @Param("keyword2") String keyword2,
                                           Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
           OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2)
        ORDER BY o.changed
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
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2)
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
        ORDER BY o.changed
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
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2))
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.manager = :manager
                  AND ((LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2))
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
        ORDER BY o.changed
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
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2))
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.worker = :worker
                  AND ((LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)
                   OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2))
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
        ORDER BY o.changed
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
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE (o.manager IN :managers AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status))
                   OR (o.manager IN :managers AND (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2))
            """
    )
    Page<Long> findPageIdByOwnerAndKeyWordAndStatus(@Param("managers") List<Manager> managers,
                                                    @Param("keyword") String keyword,
                                                    @Param("status") String status,
                                                    @Param("keyword2") String keyword2,
                                                    @Param("status2") String status2,
                                                    Pageable pageable);

    @Query("SELECT o.id FROM Order o WHERE o.company.id = :companyId ORDER BY o.changed")
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
        WHERE o.company.id = :companyId
          AND ((:filialId IS NULL AND o.filial IS NULL) OR (:filialId IS NOT NULL AND o.filial.id = :filialId))
          AND (:excludedOrderId IS NULL OR o.id <> :excludedOrderId)
          AND o.complete = false
          AND COALESCE(s.title, '') NOT IN :inactiveStatuses
    """)
    boolean existsActiveOrderByCompanyIdAndFilialId(@Param("companyId") Long companyId,
                                                    @Param("filialId") Long filialId,
                                                    @Param("excludedOrderId") Long excludedOrderId,
                                                    @Param("inactiveStatuses") Set<String> inactiveStatuses);

    @Query("""
        SELECT o
        FROM Order o
        LEFT JOIN FETCH o.status s
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.filial
        WHERE o.company.id = :companyId
          AND ((:filialId IS NULL AND o.filial IS NULL) OR (:filialId IS NOT NULL AND o.filial.id = :filialId))
          AND (:excludedOrderId IS NULL OR o.id <> :excludedOrderId)
          AND o.complete = false
          AND COALESCE(s.title, '') NOT IN :inactiveStatuses
        ORDER BY o.id DESC
    """)
    List<Order> findActiveOrdersByCompanyIdAndFilialId(@Param("companyId") Long companyId,
                                                       @Param("filialId") Long filialId,
                                                       @Param("excludedOrderId") Long excludedOrderId,
                                                       @Param("inactiveStatuses") Set<String> inactiveStatuses,
                                                       Pageable pageable);

    @Query("""
        SELECT o.id
        FROM Order o
        WHERE o.company.id = :companyId
          AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
        ORDER BY o.changed
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
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
            """,
            countQuery = """
                SELECT COUNT(o.id)
                FROM Order o
                WHERE o.company.id = :companyId
                  AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
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
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatus();

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.waitingForClient = false
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByActionableStatus();

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id), MIN(o.changed)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.complete = false
          AND o.changed IS NOT NULL
          AND o.changed <= :cutoff
          AND COALESCE(s.title, '') NOT IN :excludedStatuses
        GROUP BY s.title
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
        GROUP BY s.title
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
          AND o.manager IN :managers
          AND COALESCE(s.title, '') NOT IN :excludedStatuses
        GROUP BY s.title
    """)
    List<Object[]> summarizeOverdueOrdersByManagers(@Param("managers") Set<Manager> managers,
                                                    @Param("cutoff") LocalDate cutoff,
                                                    @Param("excludedStatuses") Set<String> excludedStatuses);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.manager = :manager
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatusAndManager(@Param("manager") Manager manager);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.manager = :manager
          AND o.waitingForClient = false
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByActionableStatusAndManager(@Param("manager") Manager manager);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.manager IN :managers
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatusAndManagers(@Param("managers") Set<Manager> managers);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.manager IN :managers
          AND o.waitingForClient = false
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByActionableStatusAndManagers(@Param("managers") Set<Manager> managers);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.worker = :worker
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatusAndWorker(@Param("worker") Worker worker);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(o.id)
        FROM Order o
        LEFT JOIN o.status s
        WHERE o.worker = :worker
          AND o.waitingForClient = false
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByActionableStatusAndWorker(@Param("worker") Worker worker);

    @Query("SELECT COUNT(o.id) FROM Order o")
    int countAllOrders();

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
        GROUP BY mu.fio
    """)
    List<Object[]> findAllIdByNewOrderAllStatus(@Param("statusNew") String statusNew,
                                                @Param("statusCorrect") String statusCorrect);

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
