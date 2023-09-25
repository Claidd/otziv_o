package com.hunt.otziv.b_bots.repository;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BotsRepository extends CrudRepository<Bot, Long> {

    Optional<Bot> findByLogin(String username);

    List<Bot> findAllByWorkerId(Long workerId);

    List<Bot> findAllByWorker(Worker worker);

    List<Bot> findAllByWorkerIdAndActiveIsTrue(Long workerId);
    List<Bot> findAllByWorkerAndActiveIsTrue(Worker worker);

    Optional<Bot> findById(Long id);
    Optional<Bot> findFirstByWorkerOrderByIdDesc(Worker worker);
//    Optional<Bot> findFirstDawnByWorker(Worker worker);


//    Optional<Bot> findByWorkerOrderByBotIdDesc(Worker worker);

//    @Query("SELECT LIMIT 1 b FROM Bot b WHERE b.worker = :worker")
//    Optional<Bot> findLastByWorker(@Param("worker") Worker worker);



//    Optional<Bot> findTopByWorkerOrderByBotIdDesc(Worker worker);

    List<Bot> findAll();
}
