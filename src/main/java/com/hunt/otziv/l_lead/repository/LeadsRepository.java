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
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface LeadsRepository extends CrudRepository<Lead, Long> {
    Optional<Lead> findByTelephoneLead(String telephoneLead);

    @Query("select l from Lead l where l.lidStatus = :status")
    Page<Lead> findAllByLidStatus(String status, Pageable pageable);

    @Query("select l from Lead l where l.lidStatus = :status AND l.manager = :manager")
    List<Lead> findAllByLidListStatus(String status, Manager manager);

    @Query("select COUNT(l) from Lead l where YEAR(l.createDate) = YEAR(:localDate) AND MONTH(l.createDate) = MONTH(:localDate) AND l.lidStatus = :status AND l.marketolog = :marketolog")
    Long findAllByLidListStatusToMarketolog(String status, Marketolog marketolog, LocalDate localDate);

    @Query("select COUNT(l) from Lead l where YEAR(l.createDate) = YEAR(:localDate) AND MONTH(l.createDate) = MONTH(:localDate) AND l.marketolog = :marketolog")
    Long findAllByLidListToMarketolog(Marketolog marketolog, LocalDate localDate);

    @Query("select COUNT(l) from Lead l where YEAR(l.createDate) = YEAR(:localDate) AND MONTH(l.createDate) = MONTH(:localDate) AND l.lidStatus = :status AND l.operator = :operator")
    Long findAllByLidListStatusToOperator(String status, Operator operator, LocalDate localDate);

    @Query("select COUNT(l) from Lead l where YEAR(l.createDate) = YEAR(:localDate) AND MONTH(l.createDate) = MONTH(:localDate) AND l.operator = :operator")
    Long findAllByLidListToOperator(Operator operator, LocalDate localDate);

    @Query("SELECT l.id FROM Lead l WHERE YEAR(l.createDate) = YEAR(:localDate) AND MONTH(l.createDate) = MONTH(:localDate)")
    List<Long> findIdListByDate(LocalDate localDate);

    @Query("SELECT l.id FROM Lead l WHERE YEAR(l.createDate) = YEAR(:localDate) AND MONTH(l.createDate) = MONTH(:localDate) AND l.lidStatus = :status")
    List<Long> findIdListByDate(LocalDate localDate, String status);

    @Query("SELECT l.id FROM Lead l WHERE YEAR(l.createDate) = YEAR(:localDate) AND MONTH(l.createDate) = MONTH(:localDate) AND l.manager IN :managerList")
    List<Long> findIdListByDateToOwner(LocalDate localDate, Set<Manager> managerList);

    @Query("SELECT l.id FROM Lead l WHERE YEAR(l.createDate) = YEAR(:localDate) AND MONTH(l.createDate) = MONTH(:localDate) AND l.lidStatus = :status AND l.manager IN :managerList")
    List<Long> findIdListByDateToOwner(LocalDate localDate, String status, Set<Manager> managerList);



    @Query("SELECT l.id FROM Lead l WHERE YEAR(l.createDate) = YEAR(:localDate) AND MONTH(l.createDate) = MONTH(:localDate)")
    List<Long> findIdListByDateNoStatus(LocalDate localDate);

    @Query("SELECT l FROM Lead l  WHERE l.id IN (:leadId)")
    List<Lead> findAllByDate(List<Long> leadId);


    @Query("select l from Lead l where l.lidStatus = :status and l.manager = :manager")
    @EntityGraph(value = "Lead.detail", type = EntityGraph.EntityGraphType.FETCH)
    Page<Lead> findAllByLidStatusAndManager(String status, Manager manager, Pageable pageable);
    @Query("select l from Lead l where  l.lidStatus = :status and l.marketolog = :marketolog")
    Page<Lead> findAllByLidStatusAndMarketolog(String status, Marketolog marketolog, Pageable pageable);
    Page<Lead> findAll(Pageable pageable);
    Page<Lead> findAllByManager(Manager manager, Pageable pageable);
    Optional<Lead> findById(Long leadId);
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCase(String status, String keyword, Pageable pageable);
    Page<Lead> findByTelephoneLeadContainingIgnoreCase(String keyword, Pageable pageable);
    Page<Lead> findByTelephoneLeadContainingIgnoreCaseAndManager(String keyword, Manager manager, Pageable pageable);
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(String status, String keyword, Manager manager, Pageable pageable);
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndMarketolog(String status, String keyword, Marketolog marketolog, Pageable pageable);



    @Query("SELECT l FROM Lead l WHERE l.manager IN :managers")
    Page<Lead> findAllByManagerToOwner(List<Manager> managers, Pageable pageable);



    @Query("SELECT l FROM Lead l WHERE LOWER(l.telephoneLead) LIKE %:keyword% AND l.manager IN :managers")
    Page<Lead> findByTelephoneLeadContainingIgnoreCaseAndManagerToOwner(String keyword, List<Manager> managers, Pageable pageable);


    @Query("SELECT l FROM Lead l WHERE l.lidStatus = :status AND LOWER(l.telephoneLead) LIKE LOWER(concat('%', :keyword, '%')) AND l.manager IN :managers")
    Page<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManagerToOwner(String status, String keyword, List<Manager> managers, Pageable pageable);


    @Query("select l from Lead l where l.lidStatus = :status and l.manager IN :managers")
    Page<Lead> findAllByLidStatusAndManagerToOwner(String status, List<Manager> managers, Pageable pageable);

}
