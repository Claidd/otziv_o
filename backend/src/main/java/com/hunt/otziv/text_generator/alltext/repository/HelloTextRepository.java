package com.hunt.otziv.text_generator.alltext.repository;

import com.hunt.otziv.text_generator.alltext.model.HelloText;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HelloTextRepository extends JpaRepository<HelloText, Long> {
}
