package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface WorkerRepository extends CrudRepository<Worker, Long> {

    interface WorkerOptionRow {
        Long getId();
        String getFio();
        String getUsername();
    }

    Optional<Worker> findById(Long id);

    @Query("""
            SELECT w.id AS id,
                   u.fio AS fio,
                   u.username AS username
            FROM Worker w
            JOIN w.user u
            JOIN u.roles r
            WHERE u.active = true
              AND r.name = 'ROLE_WORKER'
            """)
    List<WorkerOptionRow> findWorkerOptions();

    @Query("""
            SELECT w
            FROM Worker w
            JOIN FETCH w.user u
            JOIN u.roles r
            LEFT JOIN FETCH u.image
            WHERE u.active = true
              AND r.name = 'ROLE_WORKER'
            """)
    Set<Worker> findAll();

    @Query("""
    SELECT DISTINCT w
    FROM Worker w
    JOIN FETCH w.user u
    JOIN u.roles r
    LEFT JOIN FETCH u.image
    WHERE u.active = true
      AND r.name = 'ROLE_WORKER'
""")
    Set<Worker> findAllWithUserAndImageSet();

    @Query("""
            SELECT w
            FROM Worker w
            JOIN FETCH w.user u
            JOIN u.roles r
            LEFT JOIN FETCH u.image
            WHERE :manager IN elements(u.managers)
              AND u.active = true
              AND r.name = 'ROLE_WORKER'
            """)
    List<Worker> findAllToManager(Manager manager);

    @Query("""
            SELECT w
            FROM Worker w
            JOIN FETCH w.user u
            JOIN u.roles r
            LEFT JOIN FETCH u.image
            WHERE w IN (:workers)
              AND u.active = true
              AND r.name = 'ROLE_WORKER'
            """)
    List<Worker> findAllToManagerWorkers(Set<Worker> workers);

    Set<Worker> findAllByUserId(Long id);

    List<Worker> findAllByUser(User user);

    @Query("""
    SELECT w
    FROM Worker w
    JOIN FETCH w.user u
    LEFT JOIN FETCH u.image
    WHERE u.id = :id
""")
    Optional<Worker> findByUserId(Long id);

    @Query("""
            SELECT w
            FROM Worker w
            JOIN FETCH w.user u
            LEFT JOIN FETCH u.image
            WHERE w.user.username = :username
            """)
    Worker findByUsername(String username);

    @Query("""
            SELECT w
            FROM Worker w
            JOIN FETCH w.user u
            JOIN u.roles r
            LEFT JOIN FETCH u.image
            WHERE u.active = true
              AND r.name = 'ROLE_WORKER'
            """)
    List<Worker> findAllWithUserAndImage();

    @Query("""
            SELECT w
            FROM Worker w
            JOIN FETCH w.user u
            LEFT JOIN FETCH u.image
            WHERE w.user.id = :id
            """)
    Optional<Worker> findByUserIdWithUserAndImage(Long id);

    @Query("""
            SELECT DISTINCT w
            FROM Worker w
            JOIN FETCH w.user u
            JOIN u.roles r
            LEFT JOIN FETCH u.image
            LEFT JOIN FETCH u.managers m
            WHERE m IN :managerList
              AND u.active = true
              AND r.name = 'ROLE_WORKER'
            """)
    Set<Worker> findAllToManagerList(List<Manager> managerList);

    @Query("""
            SELECT DISTINCT w
            FROM Worker w
            JOIN FETCH w.user u
            JOIN u.roles r
            LEFT JOIN FETCH u.image
            JOIN u.managers m
            WHERE m.id = :managerId
              AND u.active = true
              AND r.name = 'ROLE_WORKER'
            """)
    Set<Worker> findAllByManagerIdWithUser(@Param("managerId") Long managerId);

    @Query("""
    SELECT DISTINCT w.user.id
    FROM Worker w
    JOIN w.user u
    JOIN u.managers m
    WHERE m.id IN :managerIds
""")
    List<Long> findUserIdsByManagerIds(@Param("managerIds") Set<Long> managerIds);
}
