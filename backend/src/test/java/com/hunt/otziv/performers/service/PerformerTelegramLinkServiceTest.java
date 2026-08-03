package com.hunt.otziv.performers.service;

import com.hunt.otziv.performers.model.PerformerProfile;
import com.hunt.otziv.performers.model.PerformerProfileStatus;
import com.hunt.otziv.performers.repository.PerformerProfileRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformerTelegramLinkServiceTest {

    @Mock private PerformerProfileRepository performerProfileRepository;
    @Mock private UserRepository userRepository;

    @Test
    void telegramLinkConsumesTokenButKeepsAccountPendingAndInactive() {
        PerformerTelegramLinkService service = new PerformerTelegramLinkService(
                performerProfileRepository,
                userRepository
        );
        User user = User.builder().id(41L).username("performer_random").fio("Иван").active(false).build();
        PerformerProfile performer = pending(user, LocalDateTime.now().plusHours(1));
        when(performerProfileRepository.findByTelegramLinkTokenForUpdate("performer_token"))
                .thenReturn(Optional.of(performer));
        when(userRepository.findByTelegramChatId(777L)).thenReturn(Optional.empty());

        Optional<String> response = service.handleStartCommand(777L, "/start performer_token");

        assertThat(response).hasValueSatisfying(message -> assertThat(message).contains("ожидает ручной проверки"));
        assertThat(user.getTelegramChatId()).isEqualTo(777L);
        assertThat(user.isActive()).isFalse();
        assertThat(performer.getStatus()).isEqualTo(PerformerProfileStatus.NEW);
        assertThat(performer.getTelegramLinkToken()).isNull();
        assertThat(performer.getTelegramLinkedAt()).isNotNull();
        assertThat(performer.getLastActiveAt()).isNull();
        verify(userRepository).save(user);
        verify(performerProfileRepository).save(performer);
    }

    @Test
    void expiredApplicationCannotBindTelegramAndIsRejected() {
        PerformerTelegramLinkService service = new PerformerTelegramLinkService(
                performerProfileRepository,
                userRepository
        );
        User user = User.builder().id(41L).username("performer_random").active(false).build();
        PerformerProfile performer = pending(user, LocalDateTime.now().minusSeconds(1));
        when(performerProfileRepository.findByTelegramLinkTokenForUpdate("performer_token"))
                .thenReturn(Optional.of(performer));

        Optional<String> response = service.handleStartCommand(777L, "/start performer_token");

        assertThat(response).hasValueSatisfying(message -> assertThat(message).contains("срок заявки истёк"));
        assertThat(performer.getStatus()).isEqualTo(PerformerProfileStatus.REJECTED);
        assertThat(performer.getTelegramLinkToken()).isNull();
        verify(performerProfileRepository).save(performer);
        verify(userRepository, never()).save(user);
    }

    @Test
    void telegramAlreadyOwnedByAnotherAccountCannotBeStolen() {
        PerformerTelegramLinkService service = new PerformerTelegramLinkService(
                performerProfileRepository,
                userRepository
        );
        User applicant = User.builder().id(41L).username("performer_random").active(false).build();
        User owner = User.builder().id(99L).username("existing").telegramChatId(777L).active(true).build();
        PerformerProfile performer = pending(applicant, LocalDateTime.now().plusHours(1));
        when(performerProfileRepository.findByTelegramLinkTokenForUpdate("performer_token"))
                .thenReturn(Optional.of(performer));
        when(userRepository.findByTelegramChatId(777L)).thenReturn(Optional.of(owner));

        Optional<String> response = service.handleStartCommand(777L, "/start performer_token");

        assertThat(response).hasValueSatisfying(message -> assertThat(message).contains("другой учётной записи"));
        assertThat(applicant.getTelegramChatId()).isNull();
        assertThat(performer.getTelegramLinkToken()).isEqualTo("performer_token");
        verify(userRepository, never()).save(applicant);
        verify(performerProfileRepository, never()).save(performer);
    }

    private PerformerProfile pending(User user, LocalDateTime expiresAt) {
        return PerformerProfile.builder()
                .id(7L)
                .user(user)
                .status(PerformerProfileStatus.NEW)
                .telegramLinkToken("performer_token")
                .registrationExpiresAt(expiresAt)
                .build();
    }
}
