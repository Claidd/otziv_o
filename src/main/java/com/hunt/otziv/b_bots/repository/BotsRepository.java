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
    @Query("SELECT b FROM Bot b WHERE b.worker.id = :workerId AND b.active = true")
    List<Bot> findAllByWorkerIdAndActiveIsTrue(Long workerId);

    @Query("SELECT b FROM Bot b WHERE b.worker = :worker AND b.active = true")
    List<Bot> findAllByWorkerAndActiveIsTrue(Worker worker);

    @Query("SELECT b FROM Bot b WHERE b.worker = :worker ORDER BY b.id DESC")
    Optional<Bot> findFirstByWorkerOrderByIdDesc(Worker worker);

    Optional<Bot> findById(Long id);

    List<Bot> findAll();
}
