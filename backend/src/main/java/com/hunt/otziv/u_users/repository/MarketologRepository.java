package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
@Repository
public interface MarketologRepository extends CrudRepository<Marketolog, Long> {

//    Optional<Marketolog> findById(Long id);

    @Query("""
    SELECT m
    FROM Marketolog m
    JOIN FETCH m.user u
    LEFT JOIN FETCH u.image
    WHERE u.id = :id
""")
    Optional<Marketolog> findByUserId(Long id);

    Set<Marketolog> findAll();

    @Query("""
        SELECT m
        FROM Marketolog m
        JOIN FETCH m.user u
        LEFT JOIN FETCH u.image
        WHERE :manager IN elements(u.managers)
        """)
    List<Marketolog> findAllByManager(Manager manager);

    @Query("""
        SELECT m
        FROM Marketolog m
        JOIN FETCH m.user u
        LEFT JOIN FETCH u.image
        WHERE m IN (:marketologs)
        """)
    List<Marketolog> findAllToManagerMarketologs(Set<Marketolog> marketologs);

    @Query("""
        SELECT m
        FROM Marketolog m
        JOIN FETCH m.user u
        LEFT JOIN FETCH u.image
        """)
    List<Marketolog> findAllWithUserAndImage();

    @Query("""
        SELECT DISTINCT m
        FROM Marketolog m
        JOIN FETCH m.user u
        LEFT JOIN FETCH u.image
        WHERE EXISTS (
            SELECT 1
            FROM Manager man
            WHERE man MEMBER OF u.managers
              AND man IN :managers
        )
        """)
    List<Marketolog> findAllByMarketologsToOwner(List<Manager> managers);

    @Query("""
        SELECT m
        FROM Marketolog m
        JOIN FETCH m.user u
        LEFT JOIN FETCH u.image
        WHERE m.user.id = :id
        """)
    Optional<Marketolog> findByUserIdWithUserAndImage(Long id);

    @Query("""
    SELECT DISTINCT m.user.id
    FROM Marketolog m
    JOIN m.user u
    JOIN u.managers manager
    WHERE manager.id IN :managerIds
""")
    List<Long> findUserIdsByManagerIds(@Param("managerIds") Set<Long> managerIds);
}
