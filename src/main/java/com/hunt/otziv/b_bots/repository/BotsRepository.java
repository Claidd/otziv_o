package com.hunt.otziv.b_bots.repository;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.u_users.model.User;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BotsRepository extends CrudRepository<Bot, Long> {

    Optional<Bot> findByLogin(String username);

    Optional<Bot> findById(Long id);

    List<Bot> findAll();
}
