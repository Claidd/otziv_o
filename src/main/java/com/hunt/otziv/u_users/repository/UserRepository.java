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
    Optional<User> findByUsername(String username);
    List<User> findAll();
    Optional<User> findByFio(String operator);

    @Query("SELECT u.fio FROM User u JOIN u.roles r WHERE r.name = :roleName AND u.active = true")
    List<String> findAllActiveFioByRole(String roleName);

//    User findFirstByActivateCode(String activateCode);
}
