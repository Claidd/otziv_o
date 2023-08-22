package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Operator;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OperatorRepository extends CrudRepository<Operator, Long> {

    Optional<Operator> findById(Long id);
    Optional<Operator> findByUserId(Long id);
    List<Operator> findAll();
}
