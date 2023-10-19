package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.u_users.model.Manager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CompanyRepository extends CrudRepository<Company, Long> {
    @Override
    List<Company> findAll();


    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager ORDER BY c.updateStatus")
    Page<Company> findAllToAdmin(Pageable pageable);

    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager WHERE c.status.title = :status ORDER BY c.updateStatus")
    Page<Company> findAllByStatus_title(String status, Pageable pageable);
    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager WHERE c.manager = :manager ORDER BY c.updateStatus")
    Page<Company> findAllByManager(Manager manager, Pageable pageable);

    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager WHERE c.manager = :manager AND c.status.title = :status ORDER BY c.updateStatus")
    Page<Company> findAllByManagerAndStatus(Manager manager, String status, Pageable pageable);

    boolean existsBySubCategoryId(Long reviewSubcategoryId);

    Optional<Company> findByIdAndTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(Long id, String keyword, String keyword2);

    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager WHERE (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) ORDER BY c.updateStatus")
    Page<Company> findByTitleContainingIgnoreCaseAndStatus_TitleOrTelephoneContainingIgnoreCaseAndStatus_TitleOrderByUpdateStatusDesc(String keyword, String status_title, String keyword2, String status_title2, Pageable pageable);

    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager WHERE LOWER(c.title) LIKE %:keyword% OR LOWER(c.telephone) LIKE %:keyword2%")
    Page<Company> findALLByTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(String keyword, String keyword2, Pageable pageable);

    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager WHERE (c.manager = :manager AND LOWER(c.title) LIKE %:keyword%) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE %:keyword2%)")
    Page<Company> findAllByManagerAndTitleContainingIgnoreCaseOrManagerAndTelephoneContainingIgnoreCase(Manager manager,String keyword,Manager manager2,String keyword2, Pageable pageable);

    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) ORDER BY c.updateStatus")
    Page<Company> findByManagerAndTitleContainingIgnoreCaseAndStatus_TitleOrManagerAndTelephoneContainingIgnoreCaseAndStatus_TitleOrderByUpdateStatusDesc(Manager manager,String keyword, String status_title, Manager manager2,String keyword2, String status_title2, Pageable pageable);
}
