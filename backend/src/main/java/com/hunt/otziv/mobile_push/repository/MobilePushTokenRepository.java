package com.hunt.otziv.mobile_push.repository;

import com.hunt.otziv.mobile_push.model.MobilePushToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MobilePushTokenRepository extends JpaRepository<MobilePushToken, Long> {

    Optional<MobilePushToken> findByToken(String token);

    List<MobilePushToken> findByUserIdAndActiveTrue(Long userId);
}
