package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;

import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface OperatorRepository extends CrudRepository<Operator, Long> {

    Optional<Operator> findById(Long id);

    Optional<Operator> findByUserId(Long id);

    Set<Operator> findAll();

    @Query("""
        SELECT o
        FROM Operator o
        JOIN FETCH o.user u
        LEFT JOIN FETCH u.image
        WHERE :manager IN elements(u.managers)
        """)
    List<Operator> findAllToManager(Manager manager);

    @Query("""
        SELECT o
        FROM Operator o
        JOIN FETCH o.user u
        LEFT JOIN FETCH u.image
        WHERE o IN (:operators)
        """)
    List<Operator> findAllToManagerOperators(Set<Operator> operators);

    @Query("""
        SELECT o
        FROM Operator o
        JOIN FETCH o.user u
        LEFT JOIN FETCH u.image
        """)
    List<Operator> findAllWithUserAndImage();

    @Query("""
        SELECT o
        FROM Operator o
        JOIN FETCH o.user u
        LEFT JOIN FETCH u.image
        WHERE o.user.id = :id
        """)
    Optional<Operator> findByUserIdWithUserAndImage(Long id);

    @Query("""
        SELECT o
        FROM Operator o
        JOIN o.telephones t
        WHERE t.id = :telephoneId
        """)
    Operator getOperatorByTelephoneId(Long telephoneId);

    /**
     * Новый bulk-метод для owner-сценариев:
     * сразу получаем всех операторов, привязанных к списку менеджеров.
     */
    @Query("""
        SELECT DISTINCT o
        FROM Operator o
        JOIN FETCH o.user u
        LEFT JOIN FETCH u.image
        LEFT JOIN FETCH u.managers m
        WHERE m IN :managerList
        """)
    Set<Operator> findAllByManagers(List<Manager> managerList);

    @Query("""
    SELECT DISTINCT o.user.id
    FROM Operator o
    JOIN o.user u
    JOIN u.managers m
    WHERE m.id IN :managerIds
""")
    List<Long> findUserIdsByManagerIds(@Param("managerIds") Set<Long> managerIds);
}
