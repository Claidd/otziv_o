package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    User findByEmail(String name);

    @Query("""
    select m.id
    from User u
    join u.managers m
    where u.id = :userId
""")
    List<Long> findManagerIdsByUserId(@Param("userId") Long userId);

    /**
     * Легкий вариант:
     * нужен для security, шапки, статистики и большинства обычных сценариев.
     * Не тянем managers/workers/operators/marketologs.
     */
    @Query("""
        SELECT DISTINCT u
        FROM User u
        LEFT JOIN FETCH u.roles
        LEFT JOIN FETCH u.image
        WHERE u.username = :username
    """)
    Optional<User> findByUsername(@Param("username") String username);

    /**
     * Тяжелый вариант:
     * использовать только там, где реально нужно менять/сравнивать связи пользователя.
     */
    @Query("""
        SELECT DISTINCT u
        FROM User u
        LEFT JOIN FETCH u.roles
        LEFT JOIN FETCH u.image
        LEFT JOIN FETCH u.operators
        LEFT JOIN FETCH u.managers
        LEFT JOIN FETCH u.workers
        LEFT JOIN FETCH u.marketologs
        WHERE u.username = :username
    """)
    Optional<User> findByUsernameWithAssignments(@Param("username") String username);

    @Query("""
        SELECT DISTINCT u
        FROM User u
        LEFT JOIN FETCH u.roles
        LEFT JOIN FETCH u.managers
        LEFT JOIN FETCH u.workers
        LEFT JOIN FETCH u.operators
        LEFT JOIN FETCH u.marketologs
        WHERE u.id = :id
    """)
    Optional<User> findByIdWithAssignments(@Param("id") Long id);

    List<User> findAll();

    Optional<User> findByFio(String operator);

    @Query("""
        SELECT u.fio
        FROM User u
        JOIN u.roles r
        WHERE r.name = :roleName
          AND u.active = true
    """)
    List<String> findAllActiveFioByRole(@Param("roleName") String roleName);

    /**
     * Тоже делаем легким.
     */
    @Query("""
        SELECT DISTINCT u
        FROM User u
        LEFT JOIN FETCH u.roles
        LEFT JOIN FETCH u.image
        WHERE u.telegramChatId = :telegramId
    """)
    Optional<User> findByTelegramChatId(@Param("telegramId") long telegramId);

    @Query("""
        SELECT u.fio, u.telegramChatId
        FROM User u
        WHERE u.active = true
          AND u.telegramChatId IS NOT NULL
    """)
    List<Object[]> getAllWorkersByRole();

    @Query("""
        SELECT DISTINCT u
        FROM User u
        LEFT JOIN FETCH u.image
        LEFT JOIN FETCH u.managers m
        LEFT JOIN FETCH m.user mu
        LEFT JOIN FETCH mu.workers
        JOIN u.roles r
        WHERE r.name = :roleName
          AND u.active = true
    """)
    List<User> findAllOwners(@Param("roleName") String roleName);

//    @Query("""
//    select m.id
//    from User u
//    join u.managers m
//    where u.id = :userId
//""")
//    List<Long> findManagerIdsByUserId(@Param("userId") Long userId);

    @Query("""
    select m.user.id
    from Manager m
    where m.id in :managerIds
""")
    List<Long> findManagerUserIdsByManagerIds(@Param("managerIds") List<Long> managerIds);

    @Query("""
    select distinct w.user.id
    from Worker w
    join w.user u
    join u.managers m
    where m.id in :managerIds
""")
    List<Long> findWorkerUserIdsByManagerIds(@Param("managerIds") List<Long> managerIds);

    @Query("""
    select distinct o.user.id
    from Operator o
    join o.user u
    join u.managers m
    where m.id in :managerIds
""")
    List<Long> findOperatorUserIdsByManagerIds(@Param("managerIds") List<Long> managerIds);

    @Query("""
    select distinct mk.user.id
    from Marketolog mk
    join mk.user u
    join u.managers m
    where m.id in :managerIds
""")
    List<Long> findMarketologUserIdsByManagerIds(@Param("managerIds") List<Long> managerIds);
}
