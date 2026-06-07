package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.u_users.model.Manager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface CompanyRepository extends CrudRepository<Company, Long> {
    @Override
    List<Company> findAll();

    boolean existsByIdAndManager_IdIn(Long id, Collection<Long> managerIds);

    @Query("""
        SELECT DISTINCT c
        FROM Company c
        LEFT JOIN FETCH c.status
        LEFT JOIN FETCH c.user
        LEFT JOIN FETCH c.categoryCompany
        LEFT JOIN FETCH c.subCategory
        LEFT JOIN FETCH c.manager m
        LEFT JOIN FETCH m.user
        WHERE c.id = :companyId
    """)
    Optional<Company> findByIdForCompanyDto(@Param("companyId") Long companyId);

    @Query("""
        SELECT DISTINCT c
        FROM Company c
        LEFT JOIN FETCH c.workers w
        LEFT JOIN FETCH w.user
        WHERE c.id = :companyId
    """)
    Optional<Company> findByIdWithWorkers(@Param("companyId") Long companyId);

    @Query("""
        SELECT DISTINCT c
        FROM Company c
        LEFT JOIN FETCH c.filial f
        LEFT JOIN FETCH f.city
        WHERE c.id = :companyId
    """)
    Optional<Company> findByIdWithFilials(@Param("companyId") Long companyId);

    @Query("""
        SELECT DISTINCT c
        FROM Company c
        LEFT JOIN FETCH c.categoryCompany
        LEFT JOIN FETCH c.subCategory
        LEFT JOIN FETCH c.filial f
        LEFT JOIN FETCH f.city
        WHERE c.id = :companyId
    """)
    Optional<Company> findByIdForReputationAi(@Param("companyId") Long companyId);


    @Query("SELECT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial LEFT JOIN FETCH c.manager ORDER BY c.updateStatus, c.id")
    Page<Company> findAllToAdmin(Pageable pageable);

    @Query("SELECT c.id FROM Company c ORDER BY c.updateStatus, c.id") // взять все id
    List<Long> findAllIdToAdmin();

    @Query(
            value = "SELECT c.id FROM Company c",
            countQuery = "SELECT COUNT(c.id) FROM Company c"
    )
    Page<Long> findPageIdToAdmin(Pageable pageable);

    @Query(
            value = """
                SELECT c.id
                FROM Company c
                LEFT JOIN c.status s
                WHERE COALESCE(s.title, '') NOT IN :hiddenStatuses
                   OR c.updateStatus >= :liveCutoff
            """,
            countQuery = """
                SELECT COUNT(c.id)
                FROM Company c
                LEFT JOIN c.status s
                WHERE COALESCE(s.title, '') NOT IN :hiddenStatuses
                   OR c.updateStatus >= :liveCutoff
            """
    )
    Page<Long> findPageIdToAdminLive(@Param("hiddenStatuses") Collection<String> hiddenStatuses,
                                     @Param("liveCutoff") LocalDate liveCutoff,
                                     Pageable pageable);

    @Query("SELECT c.id FROM Company c JOIN c.manager m WHERE m IN :managers ORDER BY c.updateStatus, c.id")
    List<Long> findAllIdToOwner(List<Manager> managers);

    @Query(
            value = "SELECT c.id FROM Company c WHERE c.manager IN :managers",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE c.manager IN :managers"
    )
    Page<Long> findPageIdToOwner(List<Manager> managers, Pageable pageable);

    @Query(
            value = """
                SELECT c.id
                FROM Company c
                LEFT JOIN c.status s
                WHERE c.manager IN :managers
                  AND (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
            """,
            countQuery = """
                SELECT COUNT(c.id)
                FROM Company c
                LEFT JOIN c.status s
                WHERE c.manager IN :managers
                  AND (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
            """
    )
    Page<Long> findPageIdToOwnerLive(@Param("managers") List<Manager> managers,
                                     @Param("hiddenStatuses") Collection<String> hiddenStatuses,
                                     @Param("liveCutoff") LocalDate liveCutoff,
                                     Pageable pageable);



    @Query("SELECT c.id FROM Company c WHERE c.status.title = :status ORDER BY c.updateStatus, c.id")
        // взять id по статусу
    List<Long> findAllIdByStatus(String status);

    @Query(
            value = "SELECT c.id FROM Company c WHERE c.status.title = :status",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE c.status.title = :status"
    )
    Page<Long> findPageIdByStatus(String status, Pageable pageable);

    @Query("SELECT c.id FROM Company c  WHERE (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) ORDER BY c.updateStatus, c.id")
    List<Long> findAllIdByStatusAndKeyword(String keyword, String status_title, String keyword2, String status_title2); // взять id по статусу + поиск

    @Query(
            value = "SELECT c.id FROM Company c WHERE (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) OR (STR(c.id) = :keyword AND c.status.title = :status_title)",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) OR (STR(c.id) = :keyword AND c.status.title = :status_title)"
    )
    Page<Long> findPageIdByStatusAndKeyword(String keyword, String status_title, String keyword2, String status_title2, Pageable pageable);

    @Query("SELECT c.id FROM Company c WHERE c.manager = :manager ORDER BY c.updateStatus, c.id")
        // взять все id по менеджеру
    List<Long> findAllByManager(Manager manager);

    @Query(
            value = "SELECT c.id FROM Company c WHERE c.manager = :manager",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE c.manager = :manager"
    )
    Page<Long> findPageByManager(Manager manager, Pageable pageable);

    @Query(
            value = """
                SELECT c.id
                FROM Company c
                LEFT JOIN c.status s
                WHERE c.manager = :manager
                  AND (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
            """,
            countQuery = """
                SELECT COUNT(c.id)
                FROM Company c
                LEFT JOIN c.status s
                WHERE c.manager = :manager
                  AND (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
            """
    )
    Page<Long> findPageByManagerLive(@Param("manager") Manager manager,
                                     @Param("hiddenStatuses") Collection<String> hiddenStatuses,
                                     @Param("liveCutoff") LocalDate liveCutoff,
                                     Pageable pageable);

    @Query("SELECT c.id FROM Company c WHERE c.status.title = :status AND c.manager = :manager ORDER BY c.updateStatus, c.id")
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
            value = """
                SELECT c.id
                FROM Company c
                LEFT JOIN c.status s
                WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')))
                   OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
                   OR (c.manager = :manager AND LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                   OR (c.manager = :manager AND STR(c.id) = :keyword)
            """,
            countQuery = """
                SELECT COUNT(c.id)
                FROM Company c
                LEFT JOIN c.status s
                WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')))
                   OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
                   OR (c.manager = :manager AND LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                   OR (c.manager = :manager AND STR(c.id) = :keyword)
            """
    )
    Page<Long> findPageByManagerAndKeyWord(Manager manager, String keyword, Manager manager2, String keyword2, Pageable pageable);

    @Query(
            value = """
                SELECT c.id
                FROM Company c
                LEFT JOIN c.status s
                WHERE c.manager = :manager
                  AND (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
                  AND (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(c.id) = :keyword)
            """,
            countQuery = """
                SELECT COUNT(c.id)
                FROM Company c
                LEFT JOIN c.status s
                WHERE c.manager = :manager
                  AND (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
                  AND (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(c.id) = :keyword)
            """
    )
    Page<Long> findPageByManagerAndKeyWordLive(@Param("manager") Manager manager,
                                               @Param("keyword") String keyword,
                                               @Param("keyword2") String keyword2,
                                               @Param("hiddenStatuses") Collection<String> hiddenStatuses,
                                               @Param("liveCutoff") LocalDate liveCutoff,
                                               Pageable pageable);

    @Query("SELECT c.id FROM Company c WHERE (c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))")
    List<Long> findAllByOwnerAndKeyWord(List<Manager> managers, String keyword, String keyword2);

    @Query(
            value = """
                SELECT c.id
                FROM Company c
                LEFT JOIN c.status s
                WHERE (c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')))
                   OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
                   OR (c.manager IN :managers AND LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                   OR (c.manager IN :managers AND STR(c.id) = :keyword)
            """,
            countQuery = """
                SELECT COUNT(c.id)
                FROM Company c
                LEFT JOIN c.status s
                WHERE (c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')))
                   OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')))
                   OR (c.manager IN :managers AND LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                   OR (c.manager IN :managers AND STR(c.id) = :keyword)
            """
    )
    Page<Long> findPageByOwnerAndKeyWord(List<Manager> managers, String keyword, String keyword2, Pageable pageable);

    @Query(
            value = """
                SELECT c.id
                FROM Company c
                LEFT JOIN c.status s
                WHERE c.manager IN :managers
                  AND (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
                  AND (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(c.id) = :keyword)
            """,
            countQuery = """
                SELECT COUNT(c.id)
                FROM Company c
                LEFT JOIN c.status s
                WHERE c.manager IN :managers
                  AND (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
                  AND (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(c.id) = :keyword)
            """
    )
    Page<Long> findPageByOwnerAndKeyWordLive(@Param("managers") List<Manager> managers,
                                             @Param("keyword") String keyword,
                                             @Param("keyword2") String keyword2,
                                             @Param("hiddenStatuses") Collection<String> hiddenStatuses,
                                             @Param("liveCutoff") LocalDate liveCutoff,
                                             Pageable pageable);


    @Query("SELECT c.id FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) ORDER BY c.updateStatus, c.id")
    List<Long> findAllByManagerAndStatusAndKeyWords(Manager manager, String keyword, String status_title, Manager manager2, String keyword2, String status_title2);
    // взять id по менеджеру + поиск + статус

    @Query(
            value = "SELECT c.id FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) OR (c.manager = :manager AND STR(c.id) = :keyword AND c.status.title = :status_title)",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE (c.manager = :manager AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :status_title) OR (c.manager = :manager2 AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :status_title2) OR (c.manager = :manager AND STR(c.id) = :keyword AND c.status.title = :status_title)"
    )
    Page<Long> findPageByManagerAndStatusAndKeyWords(Manager manager, String keyword, String status_title, Manager manager2, String keyword2, String status_title2, Pageable pageable);


    @Query("SELECT DISTINCT c.id FROM Company c JOIN c.manager m WHERE (m IN :managers) AND (LOWER(c.title) LIKE %:keyword% OR LOWER(c.telephone) LIKE %:keyword2%)")
    List<Long> findAllToOwnerWithFetchWithKeyWord(List<Manager> managers, String keyword, String keyword2);



    @Query("SELECT c.id FROM Company c WHERE ((c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :statusTitle) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :statusTitle2)) ORDER BY c.updateStatus, c.id")
    List<Long> findAllByOwnerListAndStatusAndKeyWords(List<Manager> managers, String keyword, String statusTitle, String keyword2, String statusTitle2);

    @Query(
            value = "SELECT c.id FROM Company c WHERE ((c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :statusTitle) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :statusTitle2) OR (c.manager IN :managers AND STR(c.id) = :keyword AND c.status.title = :statusTitle))",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE ((c.manager IN :managers AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND c.status.title = :statusTitle) OR (c.manager IN :managers AND LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%')) AND c.status.title = :statusTitle2) OR (c.manager IN :managers AND STR(c.id) = :keyword AND c.status.title = :statusTitle))"
    )
    Page<Long> findPageByOwnerListAndStatusAndKeyWords(List<Manager> managers, String keyword, String statusTitle, String keyword2, String statusTitle2, Pageable pageable);


    @Query("SELECT c.id FROM Company c WHERE c.status.title = :status AND c.manager IN :managers ORDER BY c.updateStatus, c.id")
    List<Long> findAllByManagerListAndStatus(List<Manager> managers, String status);


    @Query("SELECT DISTINCT c.id FROM Company c JOIN c.manager m WHERE m IN :managers AND c.status.title = :status ORDER BY c.updateStatus, c.id")
    List<Long> findAllByOwnerAndStatus(List<Manager> managers, String status);

    @Query("SELECT c.id FROM Company c JOIN c.manager m WHERE m IN :managers AND c.status.title = :status ORDER BY c.updateStatus, c.id")
    List<Long> findAllByOwnerAndStatus2(Set<Manager> managers, String status);

    @Query("SELECT c.id FROM Company c WHERE c.manager IN :managers AND c.status.title = :status ORDER BY c.updateStatus, c.id")
    List<Long> findAllByOwnerAndStatusToOwner(List<Manager> managers, String status);

    @Query(
            value = "SELECT c.id FROM Company c WHERE c.manager IN :managers AND c.status.title = :status",
            countQuery = "SELECT COUNT(c.id) FROM Company c WHERE c.manager IN :managers AND c.status.title = :status"
    )
    Page<Long> findPageByOwnerAndStatusToOwner(List<Manager> managers, String status, Pageable pageable);



    @Query("SELECT DISTINCT c FROM Company c LEFT JOIN FETCH c.status LEFT JOIN FETCH c.user LEFT JOIN FETCH c.filial f LEFT JOIN FETCH f.city LEFT JOIN FETCH c.manager m JOIN FETCH m.user WHERE c.id IN :companyId ORDER BY c.updateStatus, c.id")
    List<Company> findAll(List<Long> companyId);


    @Query("SELECT c.id FROM Company c WHERE LOWER(c.title) LIKE %:keyword% OR LOWER(c.telephone) LIKE %:keyword2%")
    List<Long> findAllToAdminWithFetchWithKeyWord(String keyword, String keyword2);

    @Query(
            value = """
                SELECT c.id
                FROM Company c
                LEFT JOIN c.status s
                WHERE LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR STR(c.id) = :keyword
            """,
            countQuery = """
                SELECT COUNT(c.id)
                FROM Company c
                LEFT JOIN c.status s
                WHERE LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR STR(c.id) = :keyword
            """
    )
    Page<Long> findPageToAdminWithFetchWithKeyWord(String keyword, String keyword2, Pageable pageable);

    @Query(
            value = """
                SELECT c.id
                FROM Company c
                LEFT JOIN c.status s
                WHERE (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
                  AND (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(c.id) = :keyword)
            """,
            countQuery = """
                SELECT COUNT(c.id)
                FROM Company c
                LEFT JOIN c.status s
                WHERE (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
                  AND (LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.telephone) LIKE LOWER(CONCAT('%', :keyword2, '%'))
                   OR STR(c.id) = :keyword)
            """
    )
    Page<Long> findPageToAdminWithFetchWithKeyWordLive(@Param("keyword") String keyword,
                                                       @Param("keyword2") String keyword2,
                                                       @Param("hiddenStatuses") Collection<String> hiddenStatuses,
                                                       @Param("liveCutoff") LocalDate liveCutoff,
                                                       Pageable pageable);

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
        WHERE COALESCE(s.title, '') NOT IN :hiddenStatuses
           OR c.updateStatus >= :liveCutoff
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatusLive(@Param("hiddenStatuses") Collection<String> hiddenStatuses,
                                            @Param("liveCutoff") LocalDate liveCutoff);

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
        WHERE c.manager = :manager
          AND (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatusAndManagerLive(@Param("manager") Manager manager,
                                                      @Param("hiddenStatuses") Collection<String> hiddenStatuses,
                                                      @Param("liveCutoff") LocalDate liveCutoff);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(c.id)
        FROM Company c
        LEFT JOIN c.status s
        WHERE c.manager IN :managers
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatusAndManagers(Set<Manager> managers);

    @Query("""
        SELECT COALESCE(s.title, ''), COUNT(c.id)
        FROM Company c
        LEFT JOIN c.status s
        WHERE c.manager IN :managers
          AND (COALESCE(s.title, '') NOT IN :hiddenStatuses OR c.updateStatus >= :liveCutoff)
        GROUP BY s.title
    """)
    List<Object[]> countGroupedByStatusAndManagersLive(@Param("managers") Set<Manager> managers,
                                                       @Param("hiddenStatuses") Collection<String> hiddenStatuses,
                                                       @Param("liveCutoff") LocalDate liveCutoff);

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

    @Query("""
    SELECT c
    FROM Company c
    WHERE c.groupId = :groupId
    ORDER BY c.id
""")
    List<Company> findAllByGroupId(String groupId);

    Optional<Company> findByTelegramGroupChatId(Long telegramGroupChatId);

    Optional<Company> findByMaxGroupChatId(Long maxGroupChatId);

    Optional<Company> findFirstByMaxLinkUserIdOrderByMaxLinkRequestedAtDesc(Long maxLinkUserId);

    @Query("""
    SELECT c
    FROM Company c
    WHERE c.urlChat IS NOT NULL
      AND TRIM(c.urlChat) <> ''
""")
    List<Company> findAllWithChatUrl();

    List<Company> findTop3ByMaxGroupChatIdIsNullAndUrlChatContaining(String chatIdText);

    List<Company> findTop3ByTelegramGroupChatIdIsNullAndUrlChatContainingIgnoreCase(String chatUsername);

    List<Company> findByUrlChatContainingIgnoreCase(String inviteCode);
}
