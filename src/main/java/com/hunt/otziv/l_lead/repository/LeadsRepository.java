package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.Lead;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LeadsRepository extends CrudRepository<Lead, Long> {
    Optional<Lead> findByTelephoneLead(String telephoneLead);
}
