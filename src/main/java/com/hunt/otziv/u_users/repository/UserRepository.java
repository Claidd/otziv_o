package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends CrudRepository<User, Long> {
    User findByEmail(String name);
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles r LEFT JOIN FETCH u.managers m LEFT JOIN FETCH u.workers w WHERE u.username = :username")
    Optional<User> findByUsername(String username);
    List<User> findAll();
    Optional<User> findByFio(String operator);
    @Query("SELECT u.fio FROM User u JOIN u.roles r WHERE r.name = :roleName AND u.active = true")
    List<String> findAllActiveFioByRole(String roleName);
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles LEFT JOIN FETCH u.managers m LEFT JOIN FETCH u.workers w WHERE u.telegramChatId = :telegramId")
    Optional<User> findByTelegramChatId(long telegramId);
    @Query("SELECT u.fio, u.telegramChatId FROM User u WHERE u.active = true AND u.telegramChatId IS NOT NULL")
    List<Object[]> getAllWorkersByRole();
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.managers m LEFT JOIN FETCH m.user mu LEFT JOIN FETCH mu.workers w JOIN u.roles r WHERE r.name = :roleName AND u.active = true")
    List<User> findAllOwners(String roleName);


    //    User findFirstByActivateCode(String activateCode);
}
