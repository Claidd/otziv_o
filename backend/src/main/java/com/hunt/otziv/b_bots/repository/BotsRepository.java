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

    @Query("SELECT b FROM Bot b WHERE b.botCity.id = :cityId AND b.active = true")
    List<Bot> findAllByFilialCityId(Long cityId);

    @Query("""
        SELECT b
        FROM Bot b
        LEFT JOIN FETCH b.status
        LEFT JOIN FETCH b.botCity
        WHERE b.active = true
          AND b.fio IN :fioValues
          AND b.botCity.id IN :cityIds
          AND b.status IS NOT NULL
          AND b.status.botStatusTitle = :status
        ORDER BY b.id
    """)
    List<Bot> findReserveBots(
            @Param("fioValues") List<String> fioValues,
            @Param("cityIds") List<Long> cityIds,
            @Param("status") String status
    );

    Optional<Bot> findById(Long id);

    List<Bot> findAll();

    long countByActiveTrue();

    long countByActiveFalse();

    @Query("""
        SELECT COUNT(b.id)
        FROM Bot b
        WHERE b.active = true
          AND b.status.botStatusTitle = :status
    """)
    long countActiveByStatus(@Param("status") String status);

}
