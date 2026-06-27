package com.hunt.otziv.b_bots.repository;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface BotsRepository extends CrudRepository<Bot, Long> {

    interface AdminBotRow {
        Long getId();
        String getLogin();
        String getPassword();
        String getFio();
        Boolean getActive();
        Integer getCounter();
        Long getStatusId();
        String getStatusTitle();
        Long getWorkerId();
        String getWorkerFio();
        String getWorkerUsername();
        Long getCityId();
        String getCityTitle();
    }

    Optional<Bot> findByLogin(String username);

    @Query("SELECT b.login FROM Bot b WHERE b.login IN :logins")
    Set<String> findExistingLogins(@Param("logins") List<String> logins);

    @Query("""
        SELECT b
        FROM Bot b
        LEFT JOIN FETCH b.status
        LEFT JOIN FETCH b.botCity
        LEFT JOIN FETCH b.worker w
        LEFT JOIN FETCH w.user
    """)
    List<Bot> findAllWithAdminDetails();

    @Query("""
        SELECT b
        FROM Bot b
        LEFT JOIN FETCH b.status
        LEFT JOIN FETCH b.botCity
        LEFT JOIN FETCH b.worker w
        LEFT JOIN FETCH w.user
        WHERE b.id = :id
    """)
    Optional<Bot> findByIdWithAdminDetails(@Param("id") Long id);

    @Query("""
        SELECT b.id AS id,
               b.login AS login,
               b.password AS password,
               b.fio AS fio,
               b.active AS active,
               b.counter AS counter,
               s.id AS statusId,
               s.botStatusTitle AS statusTitle,
               w.id AS workerId,
               u.fio AS workerFio,
               u.username AS workerUsername,
               bc.id AS cityId,
               bc.title AS cityTitle
        FROM Bot b
        LEFT JOIN b.status s
        LEFT JOIN b.worker w
        LEFT JOIN w.user u
        LEFT JOIN b.botCity bc
    """)
    List<AdminBotRow> findAllAdminRows();

    @Query(
            value = """
                SELECT b.id AS id,
                       b.login AS login,
                       b.password AS password,
                       b.fio AS fio,
                       b.active AS active,
                       b.counter AS counter,
                       s.id AS statusId,
                       s.botStatusTitle AS statusTitle,
                       w.id AS workerId,
                       u.fio AS workerFio,
                       u.username AS workerUsername,
                       bc.id AS cityId,
                       bc.title AS cityTitle
                FROM Bot b
                LEFT JOIN b.status s
                LEFT JOIN b.worker w
                LEFT JOIN w.user u
                LEFT JOIN b.botCity bc
                WHERE :keyword IS NULL
                   OR :keyword = ''
                   OR LOWER(STR(b.id)) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(b.login, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(b.password, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(b.fio, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(u.fio, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(u.username, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(s.botStatusTitle, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(bc.title, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                ORDER BY LOWER(COALESCE(b.fio, '')), b.id
            """,
            countQuery = """
                SELECT COUNT(b.id)
                FROM Bot b
                LEFT JOIN b.status s
                LEFT JOIN b.worker w
                LEFT JOIN w.user u
                LEFT JOIN b.botCity bc
                WHERE :keyword IS NULL
                   OR :keyword = ''
                   OR LOWER(STR(b.id)) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(b.login, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(b.password, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(b.fio, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(u.fio, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(u.username, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(s.botStatusTitle, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
                   OR LOWER(COALESCE(bc.title, '')) LIKE CONCAT('%', LOWER(:keyword), '%')
            """
    )
    Page<AdminBotRow> findAdminRows(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
        SELECT b
        FROM Bot b
        LEFT JOIN FETCH b.status
        LEFT JOIN FETCH b.botCity
        LEFT JOIN FETCH b.worker w
        LEFT JOIN FETCH w.user
        WHERE b.worker.id = :workerId
    """)
    List<Bot> findAllByWorkerId(@Param("workerId") Long workerId);

    @Query("""
        SELECT b
        FROM Bot b
        LEFT JOIN FETCH b.status
        LEFT JOIN FETCH b.botCity
        LEFT JOIN FETCH b.worker w
        LEFT JOIN FETCH w.user
        WHERE b.worker = :worker
    """)
    List<Bot> findAllByWorker(@Param("worker") Worker worker);

    @Query("""
        SELECT b
        FROM Bot b
        LEFT JOIN FETCH b.status
        LEFT JOIN FETCH b.botCity
        LEFT JOIN FETCH b.worker w
        LEFT JOIN FETCH w.user
        WHERE b.worker.id = :workerId
          AND b.active = true
    """)
    List<Bot> findAllByWorkerIdAndActiveIsTrue(@Param("workerId") Long workerId);

    @Query("""
        SELECT b
        FROM Bot b
        LEFT JOIN FETCH b.status
        LEFT JOIN FETCH b.botCity
        LEFT JOIN FETCH b.worker w
        LEFT JOIN FETCH w.user
        WHERE b.worker = :worker
          AND b.active = true
    """)
    List<Bot> findAllByWorkerAndActiveIsTrue(@Param("worker") Worker worker);

    @Query("SELECT b FROM Bot b WHERE b.worker = :worker ORDER BY b.id DESC")
    Optional<Bot> findFirstByWorkerOrderByIdDesc(Worker worker);

    @Query("""
        SELECT b
        FROM Bot b
        LEFT JOIN FETCH b.status
        LEFT JOIN FETCH b.botCity
        LEFT JOIN FETCH b.worker w
        LEFT JOIN FETCH w.user
        WHERE b.botCity.id = :cityId
          AND b.active = true
    """)
    List<Bot> findAllByFilialCityId(@Param("cityId") Long cityId);

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

    @Query("""
        SELECT b
        FROM Bot b
        LEFT JOIN FETCH b.status
        LEFT JOIN FETCH b.botCity
        LEFT JOIN FETCH b.worker w
        LEFT JOIN FETCH w.user
        WHERE b.active = true
          AND b.id <> 1
          AND b.counter >= :minCounter
          AND b.botCity IS NOT NULL
          AND b.botCity.id <> :cityId
        ORDER BY b.counter DESC, b.id
    """)
    List<Bot> findActiveBotsOutsideCityWithCounterAtLeast(
            @Param("cityId") Long cityId,
            @Param("minCounter") int minCounter
    );

    @Query("""
        SELECT b
        FROM Bot b
        LEFT JOIN FETCH b.status
        LEFT JOIN FETCH b.botCity
        WHERE b.fio = :fio
          AND b.botCity.id = :cityId
    """)
    List<Bot> findBotsByFioAndCity(
            @Param("fio") String fio,
            @Param("cityId") Long cityId
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
