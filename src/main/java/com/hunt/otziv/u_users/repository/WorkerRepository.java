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
    Optional<Worker> findById(Long id);

    @Query("SELECT w FROM Worker w LEFT JOIN FETCH w.user")
    Set<Worker> findAll();

    @Query("SELECT w FROM Worker w LEFT JOIN FETCH w.user u WHERE :manager IN elements(u.managers)")
    List<Worker> findAllToManager(Manager manager);

    @Query("SELECT w FROM Worker w LEFT JOIN FETCH w.user u WHERE w IN (:workers)")
    List<Worker> findAllToManagerWorkers(Set<Worker> workers);
    Set<Worker> findAllByUserId(Long id);
    List<Worker> findAllByUser(User user);
    Optional<Worker> findByUserId(Long id);

    @Query("SELECT w FROM Worker w LEFT JOIN FETCH w.user WHERE w.user.username = :username")
    Worker findByUsername(String username);

    @Query("SELECT DISTINCT w FROM Worker w LEFT JOIN FETCH w.user u LEFT JOIN FETCH w.bots b LEFT JOIN FETCH u.operators LEFT JOIN FETCH u.marketologs LEFT JOIN FETCH u.workers LEFT JOIN FETCH u.managers")
    List<Worker> findAllWorkers();

    @Query("SELECT DISTINCT w FROM Worker w LEFT JOIN FETCH w.user u LEFT JOIN FETCH w.bots b LEFT JOIN FETCH u.operators LEFT JOIN FETCH u.marketologs LEFT JOIN FETCH u.workers LEFT JOIN FETCH u.managers m WHERE m IN :managerList")
    Set<Worker> findAllToManagerList(List<Manager> managerList);
}
