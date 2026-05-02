package com.hunt.otziv.text_generator.alltext.repository;

import com.hunt.otziv.text_generator.alltext.model.RandomText;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RandomTextRepository extends JpaRepository<RandomText, Long> {
}
