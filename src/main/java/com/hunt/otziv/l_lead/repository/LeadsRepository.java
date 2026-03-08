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

    @Query("""
    SELECT
        YEAR(l.createDate) as y,
        MONTH(l.createDate) as m,
        COUNT(l.id) as totalCount,
        SUM(CASE WHEN l.lidStatus = :statusInWork THEN 1 ELSE 0 END) as inWorkCount
    FROM Lead l
    WHERE l.manager IN :managerList
      AND l.createDate BETWEEN :fromDate AND :toDate
    GROUP BY YEAR(l.createDate), MONTH(l.createDate)
""")
    List<Object[]> aggregateLeadStatsForTwoMonths(
            @Param("managerList") Set<Manager> managerList,
            @Param("statusInWork") String statusInWork,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate
    );

    @Query("""
        SELECT l
        FROM Lead l
        WHERE l.telephone.id = :telephoneId
          AND l.lidStatus = :status
          AND l.createDate <= :date
        ORDER BY l.createDate ASC
    """)
    List<Lead> findByTelephoneAndStatusBeforeDateOrdered(@Param("telephoneId") Long telephoneId,
                                                         @Param("status") String status,
                                                         @Param("date") LocalDate date);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE l.telephone.id = :telephoneId
          AND l.lidStatus = 'Новый'
          AND l.createDate <= CURRENT_DATE
    """)
    long countPendingLeadsByTelephone(@Param("telephoneId") Long telephoneId);

    @Query("""
        SELECT l
        FROM Lead l
        WHERE l.telephone.id = :telephoneId
          AND l.lidStatus = :status
          AND l.createDate <= :date
        ORDER BY l.createDate ASC
    """)
    List<Lead> findByTelephoneAndStatusBeforeDate(@Param("telephoneId") Long telephoneId,
                                                  @Param("status") String status,
                                                  @Param("date") LocalDate date);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE l.telephone.id = :telephoneId
          AND l.lidStatus = :status
          AND l.createDate <= :date
    """)
    int countByTelephoneAndStatusBeforeDate(@Param("telephoneId") Long telephoneId,
                                            @Param("status") String status,
                                            @Param("date") LocalDate date);

    @Query("""
        SELECT l
        FROM Lead l
        WHERE l.telephone.id = :telephoneId
          AND l.lidStatus = :status
          AND l.createDate <= :date
        ORDER BY l.createDate ASC
    """)
    Optional<Lead> findFirstByTelephoneAndStatusBeforeDate(@Param("telephoneId") Long telephoneId,
                                                           @Param("status") String status,
                                                           @Param("date") LocalDate date);

    @Query("""
        SELECT l
        FROM Lead l
        WHERE l.telephone.id = :telephoneId
          AND l.lidStatus = :status
          AND l.createDate <= :date
        ORDER BY l.createDate ASC
    """)
    List<Lead> findAllByTelephoneAndStatusBeforeDate(@Param("telephoneId") Long telephoneId,
                                                     @Param("status") String status,
                                                     @Param("date") LocalDate date);

    @Query("""
        SELECT l.id
        FROM Lead l
        WHERE l.manager IN :managers
          AND l.lidStatus = :status
          AND l.createDate >= :firstDayOfMonth
          AND l.createDate <= :lastDayOfMonth
    """)
    List<Long> getAllLeadsByDateAndStatusToOwnerForTelegram(@Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                                            @Param("lastDayOfMonth") LocalDate lastDayOfMonth,
                                                            @Param("status") String status,
                                                            @Param("managers") Set<Manager> managers);

    @Query("""
        SELECT l.manager.user.id, COUNT(l.id)
        FROM Lead l
        WHERE l.manager IN :managers
          AND l.lidStatus = :status
          AND l.createDate >= :firstDayOfMonth
          AND l.createDate <= :lastDayOfMonth
        GROUP BY l.manager.user.id
    """)
    List<Object[]> aggregateManagerLeadsInWork(@Param("managers") Set<Manager> managers,
                                               @Param("status") String status,
                                               @Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                               @Param("lastDayOfMonth") LocalDate lastDayOfMonth);

    Optional<Lead> findByTelephoneLead(String telephoneLead);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("SELECT l FROM Lead l WHERE l.lidStatus = :status")
    Page<Lead> findAllByLidStatus(@Param("status") String status, Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("""
        SELECT l
        FROM Lead l
        WHERE l.telephone.id = :telephoneId
          AND LOWER(l.telephoneLead) LIKE LOWER(concat('%', :keyword, '%'))
        ORDER BY l.createDate DESC
    """)
    Optional<Lead> findTopByLidStatusAndTelephoneIdAndKeywordOrderByCreateDateDesc(@Param("telephoneId") Long telephoneId,
                                                                                   @Param("keyword") String keyword);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("""
        SELECT l
        FROM Lead l
        WHERE l.lidStatus = :status
          AND l.telephone.id = :telephoneId
        ORDER BY l.createDate DESC
    """)
    Optional<Lead> findTopByLidStatusAndTelephoneIdOrderByCreateDateDesc(@Param("telephoneId") Long telephoneId,
                                                                         @Param("status") String status);

    @Query("SELECT l FROM Lead l WHERE l.lidStatus = :status AND l.manager = :manager")
    List<Lead> findAllByLidListStatus(@Param("status") String status,
                                      @Param("manager") Manager manager);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE l.lidStatus = :status
          AND l.manager = :manager
    """)
    long countByLidStatusAndManager(@Param("status") String status,
                                    @Param("manager") Manager manager);

    @Query("""
       select count(l)
       from Lead l
       join l.manager m
       join m.user u
       where u.id = :userId
         and l.lidStatus = 'Новый'
       """)
    long countNewLeadsForManagerUserId(@Param("userId") Long userId);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
          AND l.lidStatus = :status
          AND l.marketolog = :marketolog
    """)
    Long findAllByLidListStatusToMarketolog(@Param("status") String status,
                                            @Param("marketolog") Marketolog marketolog,
                                            @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
          AND l.marketolog = :marketolog
    """)
    Long findAllByLidListToMarketolog(@Param("marketolog") Marketolog marketolog,
                                      @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
          AND l.lidStatus = :status
          AND l.operator = :operator
    """)
    Long findAllByLidListStatusToOperator(@Param("status") String status,
                                          @Param("operator") Operator operator,
                                          @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT COUNT(l)
        FROM Lead l
        WHERE YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
          AND l.operator = :operator
    """)
    Long findAllByLidListToOperator(@Param("operator") Operator operator,
                                    @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT l.id
        FROM Lead l
        WHERE YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
    """)
    List<Long> findIdListByDate(@Param("localDate") LocalDate localDate);

    @Query("""
        SELECT l.id
        FROM Lead l
        WHERE YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
          AND l.lidStatus = :status
    """)
    List<Long> findIdListByDate(@Param("localDate") LocalDate localDate,
                                @Param("status") String status);

    @Query("""
        SELECT l.id
        FROM Lead l
        WHERE YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
          AND l.manager IN :managerList
    """)
    List<Long> findIdListByDateToOwner(@Param("localDate") LocalDate localDate,
                                       @Param("managerList") Set<Manager> managerList);

    @Query("""
        SELECT l.id
        FROM Lead l
        WHERE YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
          AND l.lidStatus = :status
          AND l.manager IN :managerList
    """)
    List<Long> findIdListByDateToOwner(@Param("localDate") LocalDate localDate,
                                       @Param("status") String status,
                                       @Param("managerList") Set<Manager> managerList);

    @Query("""
        SELECT l.id
        FROM Lead l
        WHERE YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
    """)
    List<Long> findIdListByDateNoStatus(@Param("localDate") LocalDate localDate);

    @Query("SELECT l FROM Lead l WHERE l.id IN :leadId")
    List<Lead> findAllByDate(@Param("leadId") List<Long> leadId);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("SELECT l FROM Lead l WHERE l.lidStatus = :status AND l.manager = :manager")
    Page<Lead> findAllByLidStatusAndManager(@Param("status") String status,
                                            @Param("manager") Manager manager,
                                            Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("SELECT l FROM Lead l WHERE l.lidStatus = :status AND l.marketolog = :marketolog")
    Page<Lead> findAllByLidStatusAndMarketolog(@Param("status") String status,
                                               @Param("marketolog") Marketolog marketolog,
                                               Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    Page<Lead> findAll(Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    Page<Lead> findAllByManager(Manager manager, Pageable pageable);

    Optional<Lead> findById(Long leadId);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("SELECT l FROM Lead l WHERE l.id = :id")
    Optional<Lead> findByIdWithRelations(@Param("id") Long id);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCase(String status, String keyword, Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    Page<Lead> findByTelephoneLeadContainingIgnoreCase(String keyword, Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    Page<Lead> findByTelephoneLeadContainingIgnoreCaseAndManager(String keyword, Manager manager, Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(String status, String keyword, Manager manager, Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndMarketolog(String status, String keyword, Marketolog marketolog, Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("SELECT l FROM Lead l WHERE l.manager IN :managers")
    Page<Lead> findAllByManagerToOwner(@Param("managers") List<Manager> managers, Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("""
        SELECT l
        FROM Lead l
        WHERE LOWER(l.telephoneLead) LIKE LOWER(concat('%', :keyword, '%'))
          AND l.manager IN :managers
    """)
    Page<Lead> findByTelephoneLeadContainingIgnoreCaseAndManagerToOwner(@Param("keyword") String keyword,
                                                                        @Param("managers") List<Manager> managers,
                                                                        Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("""
        SELECT l
        FROM Lead l
        WHERE l.lidStatus = :status
          AND LOWER(l.telephoneLead) LIKE LOWER(concat('%', :keyword, '%'))
          AND l.manager IN :managers
    """)
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManagerToOwner(@Param("status") String status,
                                                                                    @Param("keyword") String keyword,
                                                                                    @Param("managers") List<Manager> managers,
                                                                                    Pageable pageable);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("SELECT l FROM Lead l WHERE l.lidStatus = :status AND l.manager IN :managers")
    Page<Lead> findAllByLidStatusAndManagerToOwner(@Param("status") String status,
                                                   @Param("managers") List<Manager> managers,
                                                   Pageable pageable);

    List<Lead> findByUpdateStatusAfter(LocalDateTime since);

    @Query("""
        SELECT
            u.fio AS operatorFio,
            COUNT(l.id) AS allLeadsOperator,
            SUM(CASE WHEN l.lidStatus = :statusInWork THEN 1 ELSE 0 END) AS statusInWorkOperator,
            mUser.fio AS marketologFio,
            COUNT(l.id) AS allLeadsMarketolog,
            SUM(CASE WHEN l.lidStatus = :statusInWork THEN 1 ELSE 0 END) AS statusInWorkMarketolog
        FROM Lead l
        LEFT JOIN l.operator o
        LEFT JOIN o.user u
        LEFT JOIN l.marketolog m
        LEFT JOIN m.user mUser
        WHERE l.createDate BETWEEN :firstDayOfMonth AND :lastDayOfMonth
        GROUP BY u.fio, mUser.fio
    """)
    List<Object[]> getAllLeadsToMonth(@Param("statusInWork") String statusInWork,
                                      @Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                      @Param("lastDayOfMonth") LocalDate lastDayOfMonth);

    @Query("""
    SELECT
        mUser.fio,
        COUNT(l.id)
    FROM Lead l
    LEFT JOIN l.manager m
    LEFT JOIN m.user mUser
    WHERE l.lidStatus = :status
      AND l.createDate BETWEEN :firstDayOfMonth AND :lastDayOfMonth
    GROUP BY mUser.fio
""")
    List<Object[]> getAllLeadsToMonthToManager(
            @Param("status") String status,
            @Param("firstDayOfMonth") LocalDate firstDayOfMonth,
            @Param("lastDayOfMonth") LocalDate lastDayOfMonth
    );

    Optional<Lead> findFirstByTelephone_IdAndLidStatusAndCreateDateLessThanEqualOrderByCreateDateAsc(Long telephoneId,
                                                                                                     String status,
                                                                                                     LocalDate date);

    int countByTelephone_IdAndCreateDateLessThanEqualAndLidStatus(Long telephoneId,
                                                                  LocalDate createDate,
                                                                  String lidStatus);

    @EntityGraph(attributePaths = {
            "telephone",
            "telephone.telephoneOperator",
            "operator",
            "manager",
            "marketolog"
    })
    @Query("""
        SELECT l
        FROM Lead l
        WHERE l.operator = :operator
          AND LOWER(l.telephoneLead) LIKE LOWER(:keyword)
          AND (l.lidStatus = 'Новый' OR l.lidStatus = 'Отправленный' OR l.lidStatus = 'В работе')
    """)
    Page<Lead> getAllLeadsToOperatorAll(@Param("operator") Operator operator,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);

    @Query("""
        SELECT l.operator.id, COUNT(l.id)
        FROM Lead l
        WHERE l.operator.id IN :operatorIds
          AND YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
        GROUP BY l.operator.id
    """)
    List<Object[]> countAllByOperatorIdsInMonth(@Param("operatorIds") List<Long> operatorIds,
                                                @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT l.operator.id, COUNT(l.id)
        FROM Lead l
        WHERE l.operator.id IN :operatorIds
          AND l.lidStatus = :status
          AND YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
        GROUP BY l.operator.id
    """)
    List<Object[]> countAllByOperatorIdsAndStatusInMonth(@Param("operatorIds") List<Long> operatorIds,
                                                         @Param("status") String status,
                                                         @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT l.marketolog.id, COUNT(l.id)
        FROM Lead l
        WHERE l.marketolog.id IN :marketologIds
          AND YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
        GROUP BY l.marketolog.id
    """)
    List<Object[]> countAllByMarketologIdsInMonth(@Param("marketologIds") List<Long> marketologIds,
                                                  @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT l.marketolog.id, COUNT(l.id)
        FROM Lead l
        WHERE l.marketolog.id IN :marketologIds
          AND l.lidStatus = :status
          AND YEAR(l.createDate) = YEAR(:localDate)
          AND MONTH(l.createDate) = MONTH(:localDate)
        GROUP BY l.marketolog.id
    """)
    List<Object[]> countAllByMarketologIdsAndStatusInMonth(@Param("marketologIds") List<Long> marketologIds,
                                                           @Param("status") String status,
                                                           @Param("localDate") LocalDate localDate);

    @Query("""
    SELECT
        YEAR(l.createDate),
        MONTH(l.createDate),
        COUNT(l.id),
        SUM(CASE WHEN l.lidStatus = :statusInWork THEN 1 ELSE 0 END)
    FROM Lead l
    WHERE l.manager.id IN :managerIds
      AND l.createDate >= :fromDate
      AND l.createDate <= :toDate
    GROUP BY YEAR(l.createDate), MONTH(l.createDate)
""")
    List<Object[]> aggregateLeadMonthStatsForManagerIds(@Param("managerIds") List<Long> managerIds,
                                                        @Param("statusInWork") String statusInWork,
                                                        @Param("fromDate") LocalDate fromDate,
                                                        @Param("toDate") LocalDate toDate);
}
