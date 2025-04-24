package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.DeviceToken;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeviceTokenRepository extends CrudRepository<DeviceToken, String> {

    Optional<DeviceToken> findByToken(String token);
}
