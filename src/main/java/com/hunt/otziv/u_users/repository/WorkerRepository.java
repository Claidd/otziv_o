package com.hunt.otziv.u_users.repository;

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
    Set<Worker> findAll();
    Set<Worker> findAllByUserId(Long id);
    List<Worker> findAllByUser(User user);
    Optional<Worker> findByUserId(Long id);

}
