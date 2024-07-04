package com.hunt.otziv.r_review.repository;

import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface ReviewArchiveRepository extends CrudRepository<ReviewArchive, Long> {
    // Проверка наличия записи с таким же текстом
    boolean existsByText(String text);

    @Override
    <S extends ReviewArchive> S save(S entity);

}
