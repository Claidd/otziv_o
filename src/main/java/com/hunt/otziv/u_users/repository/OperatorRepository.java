package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Operator;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface OperatorRepository extends CrudRepository<Operator, Long> {

    // найти оператора по id
    Optional<Operator> findById(Long id);
    // найти оператора по id его юзера
    Optional<Operator> findByUserId(Long id);
    Set<Operator> findAll();
}
