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

    @Query("SELECT m FROM Marketolog m LEFT JOIN FETCH m.user u WHERE m IN (:marketologs)")
    List<Marketolog> findAllToManagerMarketologs(Set<Marketolog> marketologs);

    @Query("SELECT DISTINCT m FROM Marketolog m LEFT JOIN FETCH m.user u LEFT JOIN FETCH m.leads l LEFT JOIN FETCH u.operators LEFT JOIN FETCH u.marketologs LEFT JOIN FETCH u.workers LEFT JOIN FETCH u.managers")
    List<Marketolog> findAllMarketologs();

    @Query("SELECT DISTINCT m FROM Marketolog m JOIN FETCH m.user u WHERE EXISTS (SELECT 1 FROM Manager man WHERE man MEMBER OF u.managers AND man IN :managers)")
    List<Marketolog> findAllByMarketologsToOwner(List<Manager> managers);
}
