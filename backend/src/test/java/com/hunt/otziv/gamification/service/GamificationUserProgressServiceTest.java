package com.hunt.otziv.gamification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.gamification.dto.GamificationLeaderboardResponse;
import com.hunt.otziv.gamification.dto.GamificationRewardSettings;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GamificationUserProgressServiceTest {

    @Mock private GamificationScoreLedgerRepository ledgerRepository;
    @Mock private GamificationSettingsService settingsService;
    @Mock private UserService userService;
    @Mock private GamificationRewardService rewardService;

    private GamificationUserProgressService service;
    private User manager;

    @BeforeEach
    void setUp() {
        service = new GamificationUserProgressService(
                ledgerRepository,
                settingsService,
                userService,
                rewardService
        );
        Role role = new Role();
        role.setName("ROLE_MANAGER");
        manager = User.builder()
                .id(42L)
                .username("manager")
                .fio("Текущий менеджер")
                .roles(List.of(role))
                .build();
        when(userService.findByUserName("manager")).thenReturn(Optional.of(manager));
    }

    @Test
    void leaderboardRanksOnlySameRoleAndMarksCurrentUser() {
        when(rewardService.settings()).thenReturn(rewardSettings(true));
        when(settingsService.isCabinetVisibleForRole("MANAGER")).thenReturn(true);
        when(ledgerRepository.competitionRowsForRole(eq("MANAGER"), any(), any())).thenReturn(List.of(
                new Object[]{11L, "Первое место", "MANAGER", 8L, 120L, 7L, 1L},
                new Object[]{42L, "Текущий менеджер", "MANAGER", 6L, 90L, 6L, 0L}
        ));

        GamificationLeaderboardResponse result = service.leaderboard(principal(), 7);

        assertThat(result.enabled()).isTrue();
        assertThat(result.actorRole()).isEqualTo("MANAGER");
        assertThat(result.ownRank()).isEqualTo(2);
        assertThat(result.totalActors()).isEqualTo(2);
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).timelinessPercent()).isEqualTo(88);
        assertThat(result.entries().get(1).currentUser()).isTrue();
        assertThat(result.entries().get(1).timelinessPercent()).isEqualTo(100);
        verify(ledgerRepository).competitionRowsForRole(eq("MANAGER"), any(), any());
    }

    @Test
    void leaderboardStaysHiddenUntilCompetitionAndCabinetAreEnabled() {
        when(rewardService.settings()).thenReturn(rewardSettings(false));

        GamificationLeaderboardResponse result = service.leaderboard(principal(), 7);

        assertThat(result.enabled()).isFalse();
        assertThat(result.entries()).isEmpty();
        assertThat(result.totalActors()).isZero();
    }

    private Principal principal() {
        return () -> "manager";
    }

    private GamificationRewardSettings rewardSettings(boolean competitionEnabled) {
        return new GamificationRewardSettings(
                true,
                competitionEnabled,
                500,
                5,
                true,
                14,
                90,
                30,
                480,
                60,
                480,
                30,
                240,
                120,
                720
        );
    }
}
