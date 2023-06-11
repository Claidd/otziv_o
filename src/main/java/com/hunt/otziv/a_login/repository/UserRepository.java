package com.hunt.otziv.a_login.repository;

import com.hunt.otziv.a_login.model.User;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends CrudRepository<User, Long> {
    User findByEmail(String name);
//    User findFirstByName(String name);
//    User findFirstByActivateCode(String activateCode);
}
