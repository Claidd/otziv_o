package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.DeviceToken;
import com.hunt.otziv.l_lead.model.Telephone;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeviceTokenRepository extends CrudRepository<DeviceToken, String> {

    Optional<DeviceToken> findByToken(String token);

    @Query("SELECT d.telephone FROM DeviceToken d WHERE d.token = :token AND d.active = true")
    Optional<Telephone> findTelephoneByToken(String token);
}
