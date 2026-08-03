package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.dto.api.AdminDeviceTokenRow;
import com.hunt.otziv.l_lead.model.DeviceToken;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceTokenRepository extends CrudRepository<DeviceToken, String> {

    @Query("""
            SELECT d
            FROM DeviceToken d
            JOIN FETCH d.telephone t
            LEFT JOIN FETCH t.telephoneOperator
            WHERE d.token = :storedToken
              AND d.active = true
              AND d.expiresAt > :now
            """)
    Optional<DeviceToken> findActiveUnexpiredByStoredToken(
            @Param("storedToken") String storedToken,
            @Param("now") LocalDateTime now
    );

    @Query("""
            SELECT d
            FROM DeviceToken d
            JOIN FETCH d.telephone t
            LEFT JOIN FETCH t.telephoneOperator
            WHERE d.token = :legacyToken AND d.active = true
            """)
    Optional<DeviceToken> findActiveLegacyByStoredToken(@Param("legacyToken") String legacyToken);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE device_tokens
               SET token = :hashedToken,
                   expires_at = COALESCE(expires_at, :expiresAt)
             WHERE token = :legacyToken
               AND active = 1
               AND (expires_at IS NULL OR expires_at > :now)
            """, nativeQuery = true)
    int rotateLegacyToken(
            @Param("legacyToken") String legacyToken,
            @Param("hashedToken") String hashedToken,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM DeviceToken d
            WHERE d.telephone.id = :telephoneId
              AND (d.active = false OR d.expiresAt <= :now)
            """)
    int deleteExpiredOrInactiveByTelephoneId(
            @Param("telephoneId") Long telephoneId,
            @Param("now") LocalDateTime now
    );

    Optional<DeviceToken> findByTokenAndTelephone_Id(String token, Long telephoneId);

    boolean existsByTelephone_Id(Long telephoneId);

    List<DeviceToken> findByTelephone_IdOrderByCreatedAtDesc(Long telephoneId);

    @Query("""
            SELECT new com.hunt.otziv.l_lead.dto.api.AdminDeviceTokenRow(
                d.telephone.id,
                d.token,
                d.createdAt,
                d.active
            )
            FROM DeviceToken d
            WHERE d.telephone.id IN :telephoneIds
            ORDER BY d.telephone.id, d.createdAt DESC
            """)
    List<AdminDeviceTokenRow> findAdminRowsByTelephoneIds(
            @Param("telephoneIds") Collection<Long> telephoneIds
    );
}
