package com.hunt.otziv.gamification.repository;

import com.hunt.otziv.gamification.model.GamificationRewardClaim;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GamificationRewardClaimRepository extends JpaRepository<GamificationRewardClaim, Long> {
    List<GamificationRewardClaim> findByUserIdOrderByRequestedAtDesc(Long userId);
    List<GamificationRewardClaim> findAllByOrderByRequestedAtDesc();
    boolean existsByUserIdAndRewardIdAndStatusIn(Long userId, Long rewardId, Collection<String> statuses);

    @Query("select count(claim) from GamificationRewardClaim claim where claim.reward.id = :rewardId and claim.status in :statuses")
    long countReserved(@Param("rewardId") Long rewardId, @Param("statuses") Collection<String> statuses);
}
