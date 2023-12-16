package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Filial;
import org.springframework.data.repository.CrudRepository;

public interface FilialRepository extends CrudRepository<Filial, Long> {

    Filial findByTitleAndUrl(String title, String url);

    Filial findByUrl(String url);
}
