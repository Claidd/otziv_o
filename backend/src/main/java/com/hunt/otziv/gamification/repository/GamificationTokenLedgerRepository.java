package com.hunt.otziv.gamification.repository;

import com.hunt.otziv.gamification.model.GamificationTokenLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GamificationTokenLedgerRepository extends JpaRepository<GamificationTokenLedger, Long> {
    boolean existsByUniqueEntryKey(String uniqueEntryKey);

    @Query("select coalesce(sum(entry.amount), 0) from GamificationTokenLedger entry where entry.userId = :userId")
    long balance(@Param("userId") Long userId);
}
