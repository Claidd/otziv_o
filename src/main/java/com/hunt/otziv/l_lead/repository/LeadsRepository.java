package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.l_lead.model.Lead;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadsRepository extends CrudRepository<Lead, Long> {
    Optional<Lead> findByTelephoneLead(String telephoneLead);

    List<Lead> findAllByLidStatus(String status);

    Optional<Lead> findById(Long leadId);

    List<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCase(String status, String keyword);
}
