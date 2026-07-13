package com.hunt.otziv.gamification.repository;

import com.hunt.otziv.gamification.model.GamificationReward;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GamificationRewardRepository extends JpaRepository<GamificationReward, Long> {
    List<GamificationReward> findAllByOrderBySortOrderAscTitleAsc();
    List<GamificationReward> findByActiveTrueOrderBySortOrderAscTitleAsc();
    Optional<GamificationReward> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reward from GamificationReward reward where reward.id = :id")
    Optional<GamificationReward> findForUpdate(@Param("id") Long id);
}
