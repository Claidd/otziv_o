package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface MarketologRepository extends CrudRepository<Marketolog, Long> {

    // найти оператора по id
    Optional<Marketolog> findById(Long id);
    // найти оператора по id его юзера
    Optional<Marketolog> findByUserId(Long id);
    Set<Marketolog> findAll();

    @Query("SELECT m FROM Marketolog m JOIN FETCH m.user u WHERE :manager IN elements(u.managers)")
    List<Marketolog> findAllByManager(Manager manager);
}
