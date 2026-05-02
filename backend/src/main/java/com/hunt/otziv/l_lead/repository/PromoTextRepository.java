package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.PromoText;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PromoTextRepository extends CrudRepository<PromoText, Integer> {
    List<PromoText> findAll();
}
