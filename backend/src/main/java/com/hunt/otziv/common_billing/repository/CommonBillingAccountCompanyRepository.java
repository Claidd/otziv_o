package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommonBillingAccountCompanyRepository extends JpaRepository<CommonBillingAccountCompany, Long> {

    Optional<CommonBillingAccountCompany> findByAccount_IdAndCompany_Id(Long accountId, Long companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT link
        FROM CommonBillingAccountCompany link
        JOIN FETCH link.account account
        JOIN FETCH link.company company
        WHERE link.id = :id
    """)
    Optional<CommonBillingAccountCompany> findByIdForUpdate(@Param("id") Long id);

    List<CommonBillingAccountCompany> findByAccount_IdOrderByCompany_TitleAsc(Long accountId);

    @Query("""
        SELECT link
        FROM CommonBillingAccountCompany link
        JOIN FETCH link.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        JOIN FETCH link.company company
        WHERE company.id = :companyId
          AND link.enabled = true
          AND link.reconcilePending = false
          AND account.enabled = true
        ORDER BY account.id ASC
    """)
    List<CommonBillingAccountCompany> findEnabledLinksForCompany(@Param("companyId") Long companyId);

    @Query("""
        SELECT link
        FROM CommonBillingAccountCompany link
        JOIN FETCH link.account account
        JOIN FETCH link.company company
        WHERE company.id = :companyId
          AND link.enabled = true
          AND account.enabled = true
        ORDER BY account.id ASC
    """)
    List<CommonBillingAccountCompany> findConfiguredEnabledLinksForCompany(
            @Param("companyId") Long companyId
    );

    @Query("""
        SELECT link.id
        FROM CommonBillingAccountCompany link
        JOIN link.account account
        WHERE link.enabled = true
          AND account.enabled = true
          AND link.reconcilePending = true
          AND (link.reconcileNextAttemptAt IS NULL OR link.reconcileNextAttemptAt <= :now)
          AND (link.reconcileLeaseUntil IS NULL OR link.reconcileLeaseUntil <= :now)
        ORDER BY COALESCE(link.reconcileNextAttemptAt, link.createdAt) ASC, link.id ASC
    """)
    List<Long> findPendingReconciliationIds(
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Query("""
        SELECT link
        FROM CommonBillingAccountCompany link
        JOIN FETCH link.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        JOIN FETCH link.company company
        WHERE company.id = :companyId
          AND link.enabled = true
        ORDER BY account.id ASC
    """)
    List<CommonBillingAccountCompany> findLinksForCompany(@Param("companyId") Long companyId);

    @Query("""
        SELECT link
        FROM CommonBillingAccountCompany link
        JOIN FETCH link.company company
        WHERE link.account.id IN :accountIds
        ORDER BY company.title ASC, company.id ASC
    """)
    List<CommonBillingAccountCompany> findByAccountIds(@Param("accountIds") Collection<Long> accountIds);
}
