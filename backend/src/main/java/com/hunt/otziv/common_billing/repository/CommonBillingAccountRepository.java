package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommonBillingAccountRepository extends CrudRepository<CommonBillingAccount, Long> {

    @Query("""
        SELECT DISTINCT account
        FROM CommonBillingAccount account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        ORDER BY account.id DESC
    """)
    List<CommonBillingAccount> findAllForAdmin();

    @Query("""
        SELECT DISTINCT account
        FROM CommonBillingAccount account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE account.id = :id
    """)
    Optional<CommonBillingAccount> findByIdWithRelations(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT DISTINCT account
        FROM CommonBillingAccount account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE account.id = :id
    """)
    Optional<CommonBillingAccount> findByIdWithRelationsForUpdate(@Param("id") Long id);
}
