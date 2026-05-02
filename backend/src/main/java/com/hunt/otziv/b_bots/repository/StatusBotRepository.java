package com.hunt.otziv.b_bots.repository;

import com.hunt.otziv.b_bots.model.StatusBot;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface StatusBotRepository extends CrudRepository<StatusBot, Long> {

    Optional<StatusBot> findByBotStatusTitle(String botStatus);

    @Query("SELECT s.botStatusTitle FROM StatusBot s")
    List<String> findAllByBotStatusTitle();
}
