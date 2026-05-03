package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.u_users.model.Manager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
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

    @Query(
            value = "SELECT c.id FROM Company c",
            countQuery = "SELECT COUNT(c.id) FROM Company c"
    )
    Page<Long> findPageIdToAdmin(Pageable pageable);

    @Query("SELECT c.id FROM Company c JOIN c.manager m WHERE m IN :managers ORDER BY c.updateStatus")
    List<Long> findAllIdToOwner(List<Manager> managers);

    @Query(
            value = "SELECT c.id FROM Company c WHERE c.manager IN :managers",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE c.manager IN :managers"
    )
    Page<Long> findPageIdToOwner(List<Manager> managers, Pageable pageable);



    @Query("SELECT c.id FROM Company c WHERE c.status.title = :status ORDER BY c.updateStatus")
        // взять id по статусу
    List<Long> findAllIdByStatus(String status);

    @Query(
            value = "SELECT c.id FROM Company c WHERE c.status.title = :status",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE c.status.title = :status"
    )
    Page<Long> findPageIdByStatus(String status, Pageable pageable);

    @Query("SELECT c.id FROM Company c  WHERE (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) ORDER BY c.updateStatus")
    List<Long> findAllIdByStatusAndKeyword(String keyword, String status_title, String keyword2, String status_title2); // взять id по статусу + поиск

    @Query(
            value = "SELECT c.id FROM Company c WHERE (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2)",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2)"
    )
    Page<Long> findPageIdByStatusAndKeyword(String keyword, String status_title, String keyword2, String status_title2, Pageable pageable);

    @Query("SELECT c.id FROM Company c WHERE c.manager = :manager ORDER BY c.updateStatus")
        // взять все id по менеджеру
    List<Long> findAllByManager(Manager manager);

    @Query(
            value = "SELECT c.id FROM Company c WHERE c.manager = :manager",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE c.manager = :manager"
    )
    Page<Long> findPageByManager(Manager manager, Pageable pageable);

    @Query("SELECT c.id FROM Company c WHERE c.status.title = :status AND c.manager = :manager ORDER BY c.updateStatus")
        // взять id по менеджеру + статусу
    List<Long> findAllByManagerAndStatus(Manager manager, String status);

    @Query(
            value = "SELECT c.id FROM Company c WHERE c.status.title = :status AND c.manager = :manager",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE c.status.title = :status AND c.manager = :manager"
    )
    Page<Long> findPageByManagerAndStatus(Manager manager, String status, Pageable pageable);

    @Query("SELECT c.id FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))")
    List<Long> findAllByManagerAndKeyWord(Manager manager, String keyword, Manager manager2, String keyword2);

    @Query(
            value = "SELECT c.id FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))"
    )
    Page<Long> findPageByManagerAndKeyWord(Manager manager, String keyword, Manager manager2, String keyword2, Pageable pageable);

    @Query("SELECT c.id FROM Company c WHERE (c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))")
    List<Long> findAllByOwnerAndKeyWord(List<Manager> managers, String keyword, String keyword2);

    @Query(
            value = "SELECT c.id FROM Company c WHERE (c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE (c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))"
    )
    Page<Long> findPageByOwnerAndKeyWord(List<Manager> managers, String keyword, String keyword2, Pageable pageable);


    @Query("SELECT c.id FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) ORDER BY c.updateStatus")
    List<Long> findAllByManagerAndStatusAndKeyWords(Manager manager, String keyword, String status_title, Manager manager2, String keyword2, String status_title2);
    // взять id по менеджеру + поиск + статус

    @Query(
            value = "SELECT c.id FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2)",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2)"
    )
    Page<Long> findPageByManagerAndStatusAndKeyWords(Manager manager, String keyword, String status_title, Manager manager2, String keyword2, String status_title2, Pageable pageable);


    @Query("SELECT DISTINCT c.id FROM Company c JOIN c.manager m WHERE (m IN :managers) AND (LOWER(c.title) LIKE %:keyword% OR LOWER(c.telephone) LIKE %:keyword2%)")
    List<Long> findAllToOwnerWithFetchWithKeyWord(List<Manager> managers, String keyword, String keyword2);



    @Query("SELECT c.id FROM Company c WHERE ((c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :statusTitle) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :statusTitle2)) ORDER BY c.updateStatus")
    List<Long> findAllByOwnerListAndStatusAndKeyWords(List<Manager> managers, String keyword, String statusTitle, String keyword2, String statusTitle2);

    @Query(
            value = "SELECT c.id FROM Company c WHERE ((c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :statusTitle) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :statusTitle2))",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE ((c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :statusTitle) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :statusTitle2))"
    )
    Page<Long> findPageByOwnerListAndStatusAndKeyWords(List<Manager> managers, String keyword, String statusTitle, String keyword2, String statusTitle2, Pageable pageable);


    @Query("SELECT c.id FROM Company c WHERE c.status.title = :status AND c.manager IN :managers ORDER BY c.updateStatus")
    List<Long> findAllByManagerListAndStatus(List<Manager> managers, String status);


    @Query("SELECT DISTINCT c.id FROM Company c JOIN c.manager m WHERE m IN :managers AND c.status.title = :status ORDER BY c.updateStatus")
    List<Long> findAllByOwnerAndStatus(List<Manager> managers, String status);

    @Query("SELECT c.id FROM Company c JOIN c.manager m WHERE m IN :managers AND c.status.title = :status ORDER BY c.updateStatus")
    List<Long> findAllByOwnerAndStatus2(Set<Manager> managers, String status);

    @Query("SELECT c.id FROM Company c WHERE c.manager IN :managers AND c.status.title = :status ORDER BY c.updateStatus")
    List<Long> findAllByOwnerAndStatusToOwner(List<Manager> managers, String status);

    @Query(
            value = "SELECT c.id FROM Company c WHERE c.manager IN :managers AND c.status.title = :status",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE c.manager IN :managers AND c.status.title = :status"
    )
    Page<Long> findPageByOwnerAndStatusToOwner(List<Manager> managers, String status, Pageable pageable);



    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager m JOIN FETCH m.user WHERE c.id IN :companyId  ORDER BY c.updateStatus")
    List<Company> findAll(List<Long> companyId);


    @Query("SELECT c.id FROM Company c WHERE LOWER(c.title) LIKE %:keyword% OR LOWER(c.telephone) LIKE %:keyword2%")
    List<Long> findAllToAdminWithFetchWithKeyWord(String keyword, String keyword2);

    @Query(
            value = "SELECT c.id FROM Company c WHERE LOWER(c.title) LIKE %:keyword% OR LOWER(c.telephone) LIKE %:keyword2%",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE LOWER(c.title) LIKE %:keyword% OR LOWER(c.telephone) LIKE %:keyword2%"
    )
    Page<Long> findPageToAdminWithFetchWithKeyWord(String keyword, String keyword2, Pageable pageable);

    @Query("SELECT COUNT(c.id) FROM Company c WHERE c.status.title = :status")
    int countByStatusTitle(String status);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(c.id)
        FROM Company c
        LEFT JOIN c.status s
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatus();

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(c.id)
        FROM Company c
        LEFT JOIN c.status s
        WHERE c.manager = :manager
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatusAndManager(Manager manager);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(c.id)
        FROM Company c
        LEFT JOIN c.status s
        WHERE c.manager IN :managers
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatusAndManagers(Set<Manager> managers);

    @Query("SELECT COUNT(c.id) FROM Company c")
    int countAllCompanies();

    @Query("SELECT COUNT(c.id) FROM Company c WHERE c.manager = :manager")
    int countByManager(Manager manager);

    @Query("SELECT COUNT(c.id) FROM Company c WHERE c.manager IN :managers")
    int countByManagers(Set<Manager> managers);

    @Query("SELECT COUNT(c.id) FROM Company c WHERE c.manager = :manager AND c.status.title = :status")
    int countByManagerAndStatusTitle(Manager manager, String status);

    @Query("SELECT COUNT(c.id) FROM Company c WHERE c.manager IN :managers AND c.status.title = :status")
    int countByManagersAndStatusTitle(Set<Manager> managers, String status);


    boolean existsBySubCategoryId(Long reviewSubcategoryId);

    Optional<Company> findByIdAndTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(Long id, String keyword, String keyword2);

    @Query("""
    SELECT u.fio, COUNT(c.id)
    FROM Company c
    JOIN c.manager m
    JOIN m.user u
    WHERE c.createDate BETWEEN :firstDayOfMonth AND :lastDayOfMonth
    GROUP BY u.fio
""")
    List<Object[]> getAllNewCompanies(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);


    @Query("""
    SELECT c
    FROM Company c
    WHERE 
        (LOWER(c.telephone) = LOWER(:telephoneNumber) AND LOWER(c.title) LIKE LOWER(CONCAT('%', :title, '%')))
        OR
        (LOWER(c.title) = LOWER(:title) AND LOWER(c.telephone) = LOWER(:telephoneNumber))
""")
    Optional<Company> getByTelephoneOrTitleIgnoreCase(String telephoneNumber, String title);


    @Query("""
    SELECT c
    FROM Company c
    WHERE c.groupId = :groupId
""")
    Optional<Company> findByGroupId(String groupId);
}
