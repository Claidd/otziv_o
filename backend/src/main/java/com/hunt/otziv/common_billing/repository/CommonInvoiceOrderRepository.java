package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommonInvoiceOrderRepository extends CrudRepository<CommonInvoiceOrder, Long> {

    Optional<CommonInvoiceOrder> findByOrder_Id(Long orderId);

    @Query("""
        SELECT item
        FROM CommonInvoiceOrder item
        JOIN FETCH item.invoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        JOIN FETCH item.order order
        LEFT JOIN FETCH order.status
        LEFT JOIN FETCH order.company company
        LEFT JOIN FETCH order.filial
        LEFT JOIN FETCH order.manager orderManager
        LEFT JOIN FETCH orderManager.user
        WHERE order.id = :orderId
    """)
    Optional<CommonInvoiceOrder> findByOrderIdWithInvoice(@Param("orderId") Long orderId);

    @Query("""
        SELECT item
        FROM CommonInvoiceOrder item
        JOIN FETCH item.order order
        LEFT JOIN FETCH order.status
        LEFT JOIN FETCH order.company company
        LEFT JOIN FETCH order.filial
        LEFT JOIN FETCH order.manager orderManager
        LEFT JOIN FETCH orderManager.user
        LEFT JOIN FETCH order.worker worker
        LEFT JOIN FETCH worker.user
        WHERE item.invoice.id = :invoiceId
        ORDER BY item.id ASC
    """)
    List<CommonInvoiceOrder> findByInvoiceIdWithOrders(@Param("invoiceId") Long invoiceId);

    @Query("""
        SELECT item
        FROM CommonInvoiceOrder item
        JOIN FETCH item.order order
        LEFT JOIN FETCH order.status
        LEFT JOIN FETCH order.company company
        LEFT JOIN FETCH order.filial
        LEFT JOIN FETCH order.manager orderManager
        LEFT JOIN FETCH orderManager.user
        LEFT JOIN FETCH order.worker worker
        LEFT JOIN FETCH worker.user
        WHERE item.invoice.id IN :invoiceIds
        ORDER BY item.invoice.id ASC, item.id ASC
    """)
    List<CommonInvoiceOrder> findByInvoiceIdsWithOrders(@Param("invoiceIds") Collection<Long> invoiceIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT item
        FROM CommonInvoiceOrder item
        JOIN FETCH item.invoice invoice
        JOIN FETCH invoice.account account
        JOIN FETCH item.order order
        LEFT JOIN FETCH order.status
        LEFT JOIN FETCH order.company company
        LEFT JOIN FETCH order.filial
        WHERE company.id = :companyId
          AND account.id <> :targetAccountId
          AND invoice.status IN :statuses
          AND item.paid = FALSE
          AND item.unpaid = FALSE
        ORDER BY invoice.id ASC, item.id ASC
    """)
    List<CommonInvoiceOrder> findMovableOpenItemsForCompany(
            @Param("companyId") Long companyId,
            @Param("targetAccountId") Long targetAccountId,
            @Param("statuses") Collection<CommonInvoiceStatus> statuses
    );

    @Query("""
        SELECT item.order.id
        FROM CommonInvoiceOrder item
        JOIN item.invoice invoice
        WHERE item.order.id IN :orderIds
          AND invoice.status IN :statuses
    """)
    List<Long> findLinkedOrderIds(
            @Param("orderIds") Collection<Long> orderIds,
            @Param("statuses") Collection<com.hunt.otziv.common_billing.model.CommonInvoiceStatus> statuses
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM CommonInvoiceOrder item
        WHERE item.order.id = :orderId
    """)
    int deleteByOrderId(@Param("orderId") Long orderId);
}
