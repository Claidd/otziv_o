package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.Telephone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TelephoneRepository extends JpaRepository<Telephone, Long> {

}
