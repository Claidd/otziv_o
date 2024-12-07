package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.u_users.model.Manager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface CompanyRepository extends CrudRepository<Company, Long> {
    @Override
    List<Company> findAll();


    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager ORDER BY c.updateStatus")
    Page<Company> findAllToAdmin(Pageable pageable);

    @Query("SELECT c.id FROM Company c ORDER BY c.updateStatus") // взять все id
    List<Long> findAllIdToAdmin();

    @Query("SELECT c.id FROM Company c JOIN c.manager m WHERE m IN :managers ORDER BY c.updateStatus")
    List<Long> findAllIdToOwner(List<Manager> managers);



    @Query("SELECT c.id FROM Company c WHERE c.status.title = :status ORDER BY c.updateStatus")
        // взять id по статусу
    List<Long> findAllIdByStatus(String status);

    @Query("SELECT c.id FROM Company c  WHERE (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) ORDER BY c.updateStatus")
    List<Long> findAllIdByStatusAndKeyword(String keyword, String status_title, String keyword2, String status_title2); // взять id по статусу + поиск

    @Query("SELECT c.id FROM Company c WHERE c.manager = :manager ORDER BY c.updateStatus")
        // взять все id по менеджеру
    List<Long> findAllByManager(Manager manager);

    @Query("SELECT c.id FROM Company c WHERE c.status.title = :status AND c.manager = :manager ORDER BY c.updateStatus")
        // взять id по менеджеру + статусу
    List<Long> findAllByManagerAndStatus(Manager manager, String status);

    @Query("SELECT c.id FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))")
    List<Long> findAllByManagerAndKeyWord(Manager manager, String keyword, Manager manager2, String keyword2);

    @Query("SELECT c.id FROM Company c WHERE (c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))")
    List<Long> findAllByOwnerAndKeyWord(List<Manager> managers, String keyword, String keyword2);


    @Query("SELECT c.id FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) ORDER BY c.updateStatus")
    List<Long> findAllByManagerAndStatusAndKeyWords(Manager manager, String keyword, String status_title, Manager manager2, String keyword2, String status_title2);
    // взять id по менеджеру + поиск + статус


    @Query("SELECT DISTINCT c.id FROM Company c JOIN c.manager m WHERE (m IN :managers) AND (LOWER(c.title) LIKE %:keyword% OR LOWER(c.telephone) LIKE %:keyword2%)")
    List<Long> findAllToOwnerWithFetchWithKeyWord(List<Manager> managers, String keyword, String keyword2);



    @Query("SELECT c.id FROM Company c WHERE ((c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :statusTitle) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :statusTitle2)) ORDER BY c.updateStatus")
    List<Long> findAllByOwnerListAndStatusAndKeyWords(List<Manager> managers, String keyword, String statusTitle, String keyword2, String statusTitle2);


    @Query("SELECT c.id FROM Company c WHERE c.status.title = :status AND c.manager IN :managers ORDER BY c.updateStatus")
    List<Long> findAllByManagerListAndStatus(List<Manager> managers, String status);


    @Query("SELECT DISTINCT c.id FROM Company c JOIN c.manager m WHERE m IN :managers AND c.status.title = :status ORDER BY c.updateStatus")
    List<Long> findAllByOwnerAndStatus(List<Manager> managers, String status);
    @Query("SELECT c.id FROM Company c JOIN c.manager m WHERE m IN :managers AND c.status.title = :status ORDER BY c.updateStatus")
    List<Long> findAllByOwnerAndStatus(Set<Manager> managers, String status);



    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager m JOIN FETCH m.user WHERE c.id IN :companyId  ORDER BY c.updateStatus")
    List<Company> findAll(List<Long> companyId);


    @Query("SELECT c.id FROM Company c WHERE LOWER(c.title) LIKE %:keyword% OR LOWER(c.telephone) LIKE %:keyword2%")
    List<Long> findAllToAdminWithFetchWithKeyWord(String keyword, String keyword2);


    boolean existsBySubCategoryId(Long reviewSubcategoryId);

    Optional<Company> findByIdAndTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(Long id, String keyword, String keyword2);

}
