package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;

@Repository
public interface MarketologRepository extends CrudRepository<Marketolog, Long> {

    // найти оператора по id
    Optional<Marketolog> findById(Long id);

    // найти оператора по id его юзера
    Optional<Marketolog> findByUserId(Long id);

    // найти всех операторов в таблице операторы
//    List<Operator> findAll();
    Set<Marketolog> findAll();
}
