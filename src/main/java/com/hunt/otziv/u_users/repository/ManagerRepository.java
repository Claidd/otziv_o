package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
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

    List<Manager> findAll();

    @Query("SELECT DISTINCT m FROM Manager m LEFT JOIN FETCH m.user u LEFT JOIN FETCH m.companies c LEFT JOIN FETCH m.leads l LEFT JOIN FETCH u.operators LEFT JOIN FETCH u.marketologs LEFT JOIN FETCH u.workers LEFT JOIN FETCH u.managers")
    List<Manager> findAllManagers();

    @Query("SELECT m FROM Manager m LEFT JOIN FETCH m.user WHERE m.user.id = :id")
    Optional<Manager> findByUserId(Long id);

//    @Query("SELECT m FROM Manager m LEFT JOIN FETCH m.user u WHERE m IN (:managers)")
//    List<Manager> findAllToManagerManagers(Set<Manager> managers);
}
