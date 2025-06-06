package com.hunt.otziv.p_products.repository;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.util.Pair;
import org.springframework.lang.NonNullApi;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {
    @Override
    List<Order> findAll();

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.details LEFT JOIN FETCH o.status LEFT JOIN FETCH o.filial LEFT JOIN FETCH o.company c LEFT JOIN FETCH c.categoryCompany LEFT JOIN FETCH c.subCategory LEFT JOIN FETCH c.status LEFT JOIN FETCH o.worker w LEFT JOIN FETCH w.user LEFT JOIN FETCH o.manager m JOIN FETCH m.user WHERE o.id IN :orderId  ORDER BY o.changed DESC")
    List<Order> findAll(List<Long> orderId);


    @Query("SELECT o.id FROM Order o ORDER BY o.changed")// взять все заказы по id
    List<Long> findAllIdToAdmin();
    @Query("SELECT o.id FROM Order o WHERE o.manager = :manager ORDER BY o.changed")// взять все заказы по id + manager
    List<Long> findAllIdToManager(Manager manager);
    @Query("SELECT o.id FROM Order o WHERE o.worker = :worker ORDER BY o.changed")// взять все заказы по id + worker
    List<Long> findAllIdToWorker(Worker worker);
    @Query("SELECT o.id FROM Order o WHERE o.manager IN :managers ORDER BY o.changed")// взять все заказы по id + managers
    List<Long> findAllIdToOwner(List<Manager> managers);


    @Query("SELECT o.id FROM Order o WHERE o.status.title = :status ORDER BY o.changed")// взять все заказы по id + status
    List<Long> findAllIdByStatus(String status);
    @Query("SELECT o.id FROM Order o WHERE o.manager = :manager AND o.status.title = :status ORDER BY o.changed")// взять все заказы по id + manager + status
    List<Long> findAllIdByManagerAndStatus(Manager manager, String status);
    @Query("SELECT o.id FROM Order o WHERE o.worker = :worker AND o.status.title = :status ORDER BY o.changed")// взять все заказы по id + worker + status
    List<Long> findAllIdByWorkerAndStatus(Worker worker, String status);
    @Query("SELECT o.id FROM Order o WHERE o.manager IN :managers AND o.status.title = :status ORDER BY o.changed")
    List<Long> findAllIdByOwnerAndStatus(List<Manager> managers, String status);
    @Query("SELECT o.id FROM Order o WHERE o.manager IN :managers AND o.status.title = :status ORDER BY o.changed")
    List<Long> findAllIdByOwnerAndStatus(Set<Manager> managers, String status);



    @Query("SELECT o.id FROM Order o WHERE LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) ORDER BY o.changed")// взять все заказы по id + поиск
    List<Long> findAllIdByKeyWord(String keyword, String keyword2); //// взять все заказы по id + keyword
    @Query("SELECT o.id FROM Order o WHERE o.manager = :manager AND LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) ORDER BY o.changed")// взять все заказы по id + поиск
    List<Long> findAllIdByByManagerAndKeyWord(Manager manager, String keyword, String keyword2); //// взять все заказы по id + manager + keyword
    @Query("SELECT o.id FROM Order o WHERE o.worker = :worker AND LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) ORDER BY o.changed")// взять все заказы по id + поиск
    List<Long> findAllIdByByWorkerAndKeyWord(Worker worker, String keyword, String keyword2); //// взять все заказы по id + worker + keyword
    @Query("SELECT o.id FROM Order o WHERE (o.manager IN :managers AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))) ORDER BY o.changed")
    List<Long> findAllIdByOwnerAndKeyWord(List<Manager> managers, String keyword, String keyword2);



    @Query("SELECT o.id FROM Order o WHERE (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status) OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2) ORDER BY o.changed")// взять все заказы по id + поиск
    List<Long> findAllIdByKeyWordAndStatus(String keyword, String status, String keyword2, String status2); //взять все заказы по id  + keyword + status
    @Query("SELECT o.id FROM Order o WHERE o.manager = :manager AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status) OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2) ORDER BY o.changed")// взять все заказы по id + поиск
    List<Long> findAllIdByManagerAndKeyWordAndStatus(Manager manager, String keyword, String status, String keyword2, String status2); // взять все заказы по id + manager  + keyword + status
    @Query("SELECT o.id FROM Order o WHERE o.worker = :worker AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status) OR (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2) ORDER BY o.changed")// взять все заказы по id + поиск
    List<Long> findAllIdByWorkerAndKeyWordAndStatus(Worker worker, String keyword, String status, String keyword2, String status2); // взять все заказы по id + worker + keyword + status
    @Query("SELECT o.id FROM Order o WHERE (o.manager IN :managers AND (LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND o.status.title = :status)) OR (o.manager IN :managers AND (LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND o.status.title = :status2)) ORDER BY o.changed")
    List<Long> findAllIdByOwnerAndKeyWordAndStatus(List<Manager> managers, String keyword, String status, String keyword2, String status2); // взять все заказы по id + managers + keyword + status


    // Список всех отзывов компании
    @Query("SELECT o.id FROM Order o WHERE o.company.id = :companyId ORDER BY o.changed")// взять все заказы по id + поиск
    List<Long> findAllIdByCompanyId(long companyId); //// взять все заказы по id + keyword
    @Query("SELECT o.id FROM Order o WHERE o.company.id = :companyId AND LOWER(o.company.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(o.company.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) ORDER BY o.changed")// взять все заказы по id + поиск
    List<Long> findAllIdByCompanyIdAndKeyWord(long companyId, String keyword, String keyword2); //// взять все заказы по id + keyword
    ///
    ///

//    @Query("SELECT o.id FROM Order o WHERE o.worker = :worker AND o.status.title = :status ORDER BY o.changed")// взять все заказы по id + поиск
//    List<Long> findAllIdByWorkerAndKeyWordAndStatusForTelegram(Worker worker, String status); // найти все заказы работника со статусом "новый" для телеграм

    @Query("SELECT COUNT(o.id) FROM Order o WHERE o.worker = :worker AND o.status.title = :status")
    int countByWorkerAndStatus(Worker worker, String status);


    List<Order> findAllByManager(Manager manager);
    List<Order> findAllByWorker(Worker worker);
    List<Order> findAllByManagerAndCompanyTitleContainingIgnoreCaseOrManagerAndCompanyTelephoneContainingIgnoreCase(
            Manager manager, String keyword1, Manager manager2, String keyword2); //взять все заказы по названию и телефону компании определенного менеджера
    List<Order> findAllByWorkerAndCompanyTitleContainingIgnoreCaseOrWorkerAndCompanyTelephoneContainingIgnoreCase(
            Worker worker, String keyword1, Worker worker2, String keyword2); //взять все заказы по названию и телефону компании определенного менеджера

//    @Query("SELECT w.user.fio, " +
//            "COUNT(CASE WHEN o.status.title  = :statusNew THEN 1 END), " +
//            "COUNT(CASE WHEN o.status.title  = :statusCorrect THEN 1 END) " +
//            "FROM Order o " +
//            "LEFT JOIN o.details " +
//            "LEFT JOIN o.status " +
//            "LEFT JOIN o.filial " +
//            "LEFT JOIN o.company c " +
//            "LEFT JOIN c.categoryCompany " +
//            "LEFT JOIN c.subCategory " +
//            "LEFT JOIN c.status " +
//            "LEFT JOIN o.worker w " +
//            "LEFT JOIN w.user " +
//            "LEFT JOIN o.manager m " +
//            "JOIN m.user " +
//            "GROUP BY w.user.fio " +
//            "ORDER BY MAX(o.changed) DESC")
//    List<Object[]> findAllIdByNewOrderAllStatus(String statusNew,
//                                                String statusCorrect);

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
    List<Object[]> findAllIdByNewOrderAllStatus(String statusNew, String statusCorrect);




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
    List<Object[]> getAllOrdersToMonth(String status, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    @Query("""

            SELECT\s
    u.fio AS fio,\s
    o.status.title AS status,\s
    COUNT(o.id) AS count,
    'worker' AS role
FROM Order o\s
LEFT JOIN o.worker w\s
LEFT JOIN w.user u\s
WHERE o.complete = false\s
AND o.changed BETWEEN :firstDayOfMonth AND :lastDayOfMonth\s
AND o.status.title IN (:statuses)
GROUP BY u.fio, o.status.title

UNION ALL

SELECT\s
    m_user.fio AS fio,\s
    o.status.title AS status,\s
    COUNT(o.id) AS count,
    'manager' AS role
FROM Order o\s
LEFT JOIN o.manager m\s
LEFT JOIN m.user m_user\s
WHERE o.complete = false\s
AND o.changed BETWEEN :firstDayOfMonth AND :lastDayOfMonth\s
AND o.status.title IN (:statuses)
GROUP BY m_user.fio, o.status.title
""")
    List<Object[]> getOrdersByStatusForUsers(List<String> statuses,
                                             LocalDate firstDayOfMonth,
                                             LocalDate lastDayOfMonth);


//    @Query("""
//    SELECT
//        COALESCE(m_user.fio, u.fio) AS fio,
//        o.status.title AS status,
//        COUNT(o.id) AS count,
//        CASE
//            WHEN m_user.fio IS NOT NULL THEN 'manager'
//            ELSE 'worker'
//        END AS role
//    FROM Order o
//    LEFT JOIN o.worker w
//    LEFT JOIN w.user u
//    LEFT JOIN o.manager m
//    LEFT JOIN m.user m_user
//    WHERE o.complete = false
//    AND o.changed BETWEEN :firstDayOfMonth AND :lastDayOfMonth
//    AND o.status.title IN (:statuses)
//    GROUP BY fio, o.status.title, role
//""")
//    List<Object[]> getOrdersByStatusForUsers(List<String> statuses,
//                                             LocalDate firstDayOfMonth,
//                                             LocalDate lastDayOfMonth);




//    @Modifying
//    @Query("DELETE FROM Order o WHERE o.details = :orderId")
//    void deleteOrderById(UUID orderId);

//    @Modifying
//    @Query("DELETE FROM Order o WHERE o.details IN :orderDetails")
//    void deleteOrderById(List<OrderDetails> orderDetails);

}
