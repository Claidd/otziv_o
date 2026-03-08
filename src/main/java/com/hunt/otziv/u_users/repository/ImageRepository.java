package com.hunt.otziv.u_users.repository;

import com.hunt.otziv.u_users.model.Image;

import com.hunt.otziv.u_users.model.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ImageRepository extends CrudRepository<Image, Long> {

    @Query("""
       SELECT u.fio, u.image.id, u.id
       FROM User u
       WHERE u.image IS NOT NULL
       """)
    List<Object[]> findAllToScore();
}
