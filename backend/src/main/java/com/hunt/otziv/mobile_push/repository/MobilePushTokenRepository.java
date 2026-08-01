package com.hunt.otziv.mobile_push.repository;

import com.hunt.otziv.mobile_push.model.MobilePushToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MobilePushTokenRepository extends JpaRepository<MobilePushToken, Long> {

    Optional<MobilePushToken> findByToken(String token);

    @Query("""
            SELECT token
            FROM MobilePushToken token
            JOIN token.user owner
            WHERE owner.id = :userId
              AND owner.active = true
              AND token.active = true
              AND token.revokedAt IS NULL
              AND token.revokedReason IS NULL
              AND token.authEpoch = owner.authEpoch
            """)
    List<MobilePushToken> findDeliverableByUserId(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE MobilePushToken token
            SET token.active = false,
                token.revokedAt = :revokedAt,
                token.revokedReason = :reason,
                token.revokedByUserId = :actorUserId,
                token.updatedAt = :revokedAt
            WHERE token.user.id = :userId
              AND token.token = :tokenValue
              AND token.active = true
            """)
    int revokeActiveOwnedToken(
            @Param("userId") Long userId,
            @Param("tokenValue") String tokenValue,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") String reason,
            @Param("actorUserId") Long actorUserId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE MobilePushToken token
            SET token.active = false,
                token.revokedAt = :revokedAt,
                token.revokedReason = :reason,
                token.revokedByUserId = :actorUserId,
                token.updatedAt = :revokedAt
            WHERE token.user.id = :userId
              AND token.active = true
            """)
    int revokeAllActiveForUser(
            @Param("userId") Long userId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") String reason,
            @Param("actorUserId") Long actorUserId
    );
}
