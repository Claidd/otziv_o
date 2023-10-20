package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface ManagerRepository extends CrudRepository<Manager, Long> {

    // найти менеджера по id
    Optional<Manager> findById(Long id);
    // найти оператора по id
    Set<Manager> findAll();

//    @Query("SELECT m FROM Manager m LEFT JOIN FETCH m.user WHERE m.user.id = :id")
    Optional<Manager> findByUserId(Long id);
}
