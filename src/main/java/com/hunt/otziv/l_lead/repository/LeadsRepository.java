package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadsRepository extends CrudRepository<Lead, Long> {
    Optional<Lead> findByTelephoneLead(String telephoneLead);
    List<Lead> findAllByLidStatus(String status);
    List<Lead> findAllByLidStatusAndManager(String status, Manager manager);
    List<Lead> findAllByLidStatusAndMarketolog(String status, Marketolog marketolog);
    List<Lead> findAll();
    List<Lead> findAllByManager(Manager manager);
    Optional<Lead> findById(Long leadId);
    List<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCase(String status, String keyword);
    List<Lead> findByTelephoneLeadContainingIgnoreCase(String keyword);
    List<Lead> findByTelephoneLeadContainingIgnoreCaseAndManager(String keyword, Manager manager);
    List<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(String status, String keyword, Manager manager);
    List<Lead> findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndMarketolog(String status, String keyword, Marketolog marketolog);
}
