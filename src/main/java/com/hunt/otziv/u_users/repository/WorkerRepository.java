package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkerRepository extends CrudRepository<Worker, Long> {
    Optional<Worker> findById(Long id);
    List<Worker> findAll();

    Optional<Worker> findByUserId(Long id);
}
