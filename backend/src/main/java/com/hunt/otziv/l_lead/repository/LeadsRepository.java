package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;


@Repository
public interface LeadsRepository extends CrudRepository<Lead, Long> {

    // Выборка всех лидов по телефону, статусу и дате (от самых старых)
    @Query("""
        SELECT l FROM Lead l
        WHERE l.telephone.id = :telephoneId
          AND l.lidStatus = :status
          AND l.createDate <= :date
        ORDER BY l.createDate ASC
    """)
    List<Lead> findByTelephoneAndStatusBeforeDateOrdered(
            @Param("telephoneId") Long telephoneId,
            @Param("status") String status,
            @Param("date") LocalDate date
    );

    @Query("SELECT COUNT(l) FROM Lead l WHERE l.telephone.id = :telephoneId AND l.lidStatus = 'Новый' AND l.createDate <= CURRENT_DATE")
    long countPendingLeadsByTelephone(@Param("telephoneId") Long telephoneId);


    @Query("SELECT l FROM Lead l WHERE l.telephone.id = :telephoneId " +
            "AND l.lidStatus = :status AND l.createDate <= :date ORDER BY l.createDate ASC")
    List<Lead> findByTelephoneAndStatusBeforeDate(@Param("telephoneId") Long telephoneId,
                                                  @Param("status") String status,
                                                  @Param("date") LocalDate date);

    @Query("SELECT COUNT(l) FROM Lead l WHERE l.telephone.id = :telephoneId " +
            "AND l.lidStatus = :status AND l.createDate <= :date")
    int countByTelephoneAndStatusBeforeDate(@Param("telephoneId") Long telephoneId,
                                            @Param("status") String status,
                                            @Param("date") LocalDate date);

    @Query("SELECT l FROM Lead l WHERE l.telephone.id = :telephoneId " +
            "AND l.lidStatus = :status AND l.createDate <= :date ORDER BY l.createDate ASC")
    Optional<Lead> findFirstByTelephoneAndStatusBeforeDate(@Param("telephoneId") Long telephoneId,
                                                           @Param("status") String status,
                                                           @Param("date") LocalDate date);


    @Query("""
        SELECT l FROM Lead l
        WHERE l.telephone.id = :telephoneId
          AND l.lidStatus = :status
          AND l.createDate <= :date
        ORDER BY l.createDate ASC
    """)
    List<Lead> findAllByTelephoneAndStatusBeforeDate(
            @Param("telephoneId") Long telephoneId,
            @Param("status") String status,
            @Param("date") LocalDate date
    );


    Optional<Lead> findByTelephoneLead(String telephoneLead);
    boolean existsByTelephoneLeadIn(Collection<String> telephoneLeads);
    Optional<Lead> findFirstByTelephoneLeadInAndIdNot(Collection<String> telephoneLeads, Long id);

    @Query("select l.telephoneLead from Lead l where l.telephoneLead in :telephoneLeads")
    List<String> findExistingTelephoneLeads(@Param("telephoneLeads") Collection<String> telephoneLeads);

    @Query("select l from Lead l where l.lidStatus = :status")
    Page<Lead> findAllByLidStatus(String status, Pageable pageable);
    long countByLidStatus(String status);

//    @Query("select l from Lead l where l.lidStatus = :status AND l.telephone.id = :telephoneId")
//    Page<Lead> findAllByLidStatusByTelephoneId(Long telephoneId, String status, Pageable pageable);

    @Query("SELECT l FROM Lead l WHERE l.telephone.id = :telephoneId AND LOWER(l.telephoneLead) LIKE LOWER(concat('%', :keyword, '%')) ORDER BY l.createDate DESC LIMIT 1")
    Optional<Lead> findTopByLidStatusAndTelephoneIdAndKeywordOrderByCreateDateDesc(Long telephoneId,
                                                                                   String keyword);

    @Query("SELECT l FROM Lead l WHERE l.lidStatus = :status AND l.telephone.id = :telephoneId ORDER BY l.createDate DESC LIMIT 1")
    Optional<Lead> findTopByLidStatusAndTelephoneIdOrderByCreateDateDesc(Long telephoneId, String status);


    @Query("select l from Lead l where l.lidStatus = :status AND l.manager = :manager")
    List<Lead> findAllByLidListStatus(String status, Manager manager);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE l.createDate >= :dateFrom
          AND l.createDate < :dateTo
          AND l.lidStatus = :status
          AND l.marketolog = :marketolog
    """)
    Long findAllByLidListStatusToMarketolog(String status, Marketolog marketolog, LocalDate dateFrom, LocalDate dateTo);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE l.createDate >= :dateFrom
          AND l.createDate < :dateTo
          AND l.marketolog = :marketolog
    """)
    Long findAllByLidListToMarketolog(Marketolog marketolog, LocalDate dateFrom, LocalDate dateTo);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE l.createDate >= :dateFrom
          AND l.createDate < :dateTo
          AND l.lidStatus = :status
          AND l.operator = :operator
    """)
    Long findAllByLidListStatusToOperator(String status, Operator operator, LocalDate dateFrom, LocalDate dateTo);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE l.createDate >= :dateFrom
          AND l.createDate < :dateTo
          AND l.operator = :operator
    """)
    Long findAllByLidListToOperator(Operator operator, LocalDate dateFrom, LocalDate dateTo);

    @Query("""
        SELECT l.id
        FROM Lead l
        WHERE l.createDate >= :dateFrom
          AND l.createDate < :dateTo
    """)
    List<Long> findIdListByDate(LocalDate dateFrom, LocalDate dateTo);

    @Query("""
        SELECT l.id
        FROM Lead l
        WHERE l.createDate >= :dateFrom
          AND l.createDate < :dateTo
          AND l.lidStatus = :status
    """)
    List<Long> findIdListByDate(LocalDate dateFrom, LocalDate dateTo, String status);

    @Query("""
        SELECT l.id
        FROM Lead l
        WHERE l.createDate >= :dateFrom
          AND l.createDate < :dateTo
          AND l.manager IN :managerList
    """)
    List<Long> findIdListByDateToOwner(LocalDate dateFrom, LocalDate dateTo, Set<Manager> managerList);

    @Query("""
        SELECT l.id
        FROM Lead l
        WHERE l.createDate >= :dateFrom
          AND l.createDate < :dateTo
          AND l.lidStatus = :status
          AND l.manager IN :managerList
    """)
    List<Long> findIdListByDateToOwner(LocalDate dateFrom, LocalDate dateTo, String status, Set<Manager> managerList);


    @Query("SELECT l FROM Lead l  WHERE l.id IN (:leadId)")
    List<Lead> findAllByDate(List<Long> leadId);


    @Query("select l from Lead l where l.lidStatus = :status and l.manager = :manager")
    @EntityGraph(value = "Lead.detail", type = EntityGraph.EntityGraphType.FETCH)
    Page<Lead> findAllByLidStatusAndManager(String status, Manager manager, Pageable pageable);
    long countByLidStatusAndManager(String status, Manager manager);
    @Query("select l from Lead l where  l.lidStatus = :status and l.marketolog = :marketolog")
    Page<Lead> findAllByLidStatusAndMarketolog(String status, Marketolog marketolog, Pageable pageable);
    long countByLidStatusAndMarketolog(String status, Marketolog marketolog);
    Page<Lead> findAll(Pageable pageable);
    Page<Lead> findAllByManager(Manager manager, Pageable pageable);
    long countByManager(Manager manager);
    Optional<Lead> findById(Long leadId);
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCase(String status, String keyword, Pageable pageable);
    long countByLidStatusAndTelephoneLeadContainingIgnoreCase(String status, String keyword);
    Page<Lead> findByTelephoneLeadContainingIgnoreCase(String keyword, Pageable pageable);
    long countByTelephoneLeadContainingIgnoreCase(String keyword);
    Page<Lead> findByTelephoneLeadContainingIgnoreCaseAndManager(String keyword, Manager manager, Pageable pageable);
    long countByTelephoneLeadContainingIgnoreCaseAndManager(String keyword, Manager manager);
    Page<Lead> findByTelephoneLeadContainingIgnoreCaseAndMarketolog(String keyword, Marketolog marketolog, Pageable pageable);
    long countByTelephoneLeadContainingIgnoreCaseAndMarketolog(String keyword, Marketolog marketolog);
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(String status, String keyword, Manager manager, Pageable pageable);
    long countByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(String status, String keyword, Manager manager);
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndMarketolog(String status, String keyword, Marketolog marketolog, Pageable pageable);
    long countByLidStatusAndTelephoneLeadContainingIgnoreCaseAndMarketolog(String status, String keyword, Marketolog marketolog);



    @Query("SELECT l FROM Lead l WHERE l.manager IN :managers")
    Page<Lead> findAllByManagerToOwner(List<Manager> managers, Pageable pageable);
    long countByManagerIn(Collection<Manager> managers);



    @Query("SELECT l FROM Lead l WHERE LOWER(l.telephoneLead) LIKE %:keyword% AND l.manager IN :managers")
    Page<Lead> findByTelephoneLeadContainingIgnoreCaseAndManagerToOwner(String keyword, List<Manager> managers, Pageable pageable);
    long countByTelephoneLeadContainingIgnoreCaseAndManagerIn(String keyword, Collection<Manager> managers);


    @Query("SELECT l FROM Lead l WHERE l.lidStatus = :status AND LOWER(l.telephoneLead) LIKE LOWER(concat('%', :keyword, '%')) AND l.manager IN :managers")
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManagerToOwner(String status, String keyword, List<Manager> managers, Pageable pageable);
    long countByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManagerIn(String status, String keyword, Collection<Manager> managers);


    @Query("select l from Lead l where l.lidStatus = :status and l.manager IN :managers")
    Page<Lead> findAllByLidStatusAndManagerToOwner(String status, List<Manager> managers, Pageable pageable);
    long countByLidStatusAndManagerIn(String status, Collection<Manager> managers);

    Page<Lead> findByLidStatusAndDateNewTryLessThanEqual(String status, LocalDate dateNewTry, Pageable pageable);
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndDateNewTryLessThanEqual(
            String status, String keyword, LocalDate dateNewTry, Pageable pageable);
    Page<Lead> findByLidStatusAndManagerAndDateNewTryLessThanEqual(
            String status, Manager manager, LocalDate dateNewTry, Pageable pageable);
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManagerAndDateNewTryLessThanEqual(
            String status, String keyword, Manager manager, LocalDate dateNewTry, Pageable pageable);
    long countByLidStatusAndDateNewTryLessThanEqual(String status, LocalDate dateNewTry);
    long countByLidStatusAndTelephoneLeadContainingIgnoreCaseAndDateNewTryLessThanEqual(
            String status, String keyword, LocalDate dateNewTry);
    long countByLidStatusAndManagerAndDateNewTryLessThanEqual(String status, Manager manager, LocalDate dateNewTry);
    long countByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManagerAndDateNewTryLessThanEqual(
            String status, String keyword, Manager manager, LocalDate dateNewTry);

    List<Lead> findByUpdateStatusAfter(LocalDateTime since);

    @Query("""
    SELECT 
        u.fio AS operatorFio, 
        COUNT(l.id) AS allLeadsOperator, 
        SUM(CASE WHEN l.lidStatus = :statusInWork THEN 1 ELSE 0 END) AS statusInWorkOperator, 

        m_user.fio AS marketologFio, 
        COUNT(l.id) AS allLeadsMarketolog, 
        SUM(CASE WHEN l.lidStatus = :statusInWork THEN 1 ELSE 0 END) AS statusInWorkMarketolog 
    FROM Lead l 
    LEFT JOIN l.operator o 
    LEFT JOIN o.user u 
    LEFT JOIN l.marketolog m 
    LEFT JOIN m.user m_user 
        WHERE l.createDate >= :dateFrom
          AND l.createDate < :dateTo
    GROUP BY u.fio, m_user.fio
""")
    List<Object[]> getAllLeadsToMonth(String statusInWork, LocalDate dateFrom, LocalDate dateTo);

    @Query("""
    SELECT 
        m_user.fio AS managerfio, 
        COUNT(CASE WHEN l.lidStatus = :status THEN 1 END) AS statusCount
    FROM Lead l 
    LEFT JOIN l.manager m 
    LEFT JOIN m.user m_user 
    WHERE l.createDate >= :dateFrom
      AND l.createDate < :dateTo
    GROUP BY m_user.fio
""")
    List<Object[]> getAllLeadsToMonthToManager(String status, LocalDate dateFrom, LocalDate dateTo);

//    @Query("""
//    SELECT l FROM Lead l
//    WHERE l.telephone.id = :telephoneId
//      AND l.lidStatus = :status
//      AND l.createDate <= :date
//    ORDER BY l.createDate ASC
//""")
//    Optional<Lead> findFirstByTelephone(Long telephoneId, String status, LocalDate date);

    Optional<Lead> findFirstByTelephone_IdAndLidStatusAndCreateDateLessThanEqualOrderByCreateDateAsc(
            Long telephoneId,
            String status,
            LocalDate date
    );


    int countByTelephone_IdAndCreateDateLessThanEqualAndLidStatus(Long telephone_id, LocalDate createDate, String lidStatus);

    List<Lead> findByUpdateStatusAfter(LocalDate localDate);

    @Query("SELECT l FROM Lead l WHERE l.operator = :operator AND " +
            "(LOWER(l.telephoneLead) LIKE LOWER(:keyword)) AND " +
            "(l.lidStatus = 'Новый' OR l.lidStatus = 'Отправленный' OR l.lidStatus = 'В работу')")
    Page<Lead> getAllLeadsToOperatorAll(
            @Param("operator") Operator operator,
            @Param("keyword") String keyword,
            Pageable pageable);



}
