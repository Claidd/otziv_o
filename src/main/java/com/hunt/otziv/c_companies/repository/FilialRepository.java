package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Filial;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FilialRepository extends CrudRepository<Filial, Long> {

    Filial findByTitleAndUrl(String title, String url);

    Filial findByUrl(String url);

    @Query("SELECT f FROM Filial f WHERE f.city.id = :cityId")
    List<Filial> findByCityId(@Param("cityId") Long cityId);
}
