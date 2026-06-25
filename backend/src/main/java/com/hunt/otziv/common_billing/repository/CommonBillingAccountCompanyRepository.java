package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommonBillingAccountCompanyRepository extends JpaRepository<CommonBillingAccountCompany, Long> {

    Optional<CommonBillingAccountCompany> findByAccount_IdAndCompany_Id(Long accountId, Long companyId);

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
          AND account.enabled = true
        ORDER BY account.id ASC
    """)
    List<CommonBillingAccountCompany> findEnabledLinksForCompany(@Param("companyId") Long companyId);

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
