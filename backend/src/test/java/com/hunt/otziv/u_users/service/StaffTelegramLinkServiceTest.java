package com.hunt.otziv.u_users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class StaffTelegramLinkServiceTest {

    private JdbcTemplate jdbcTemplate;
    private UserRepository userRepository;
    private StaffTelegramLinkService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        userRepository = mock(UserRepository.class);
        service = new StaffTelegramLinkService(jdbcTemplate, userRepository);
        ReflectionTestUtils.setField(service, "telegramBotUsername", "@otziv_bot");
    }

    @Test
    void typedLoginsAndForeignStartPayloadsAreNotLinkCommands() {
        assertThat(service.handleStartCommand(1L, "manager_ivan")).isEmpty();
        assertThat(service.handleStartCommand(1L, "/start performer_abc")).isEmpty();
        assertThat(service.handleStartCommand(1L, "/start")).isEmpty();
        verify(userRepository, never()).save(any());
    }

    @Test
    void issuedLinkCarriesOnlyTheTokenAndStoresItsHash() {
        User user = User.builder().id(5L).username("alex").active(true).build();
        when(userRepository.findByUsername("alex")).thenReturn(Optional.of(user));

        StaffTelegramLinkService.IssuedLink link = service.issue("alex");

        String prefix = "https://t.me/otziv_bot?start=staff_";
        assertThat(link.url()).startsWith(prefix);
        String token = link.url().substring(prefix.length());
        ArgumentCaptor<Object> hash = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(
                eq("INSERT INTO staff_telegram_link_tokens (token_hash, user_id, expires_at) VALUES (?, ?, ?)"),
                hash.capture(), eq(5L), any());
        assertThat(hash.getValue()).isNotEqualTo(token);
        assertThat(String.valueOf(hash.getValue())).hasSize(64);
    }

    @Test
    void validOneTimeTokenBindsTheChatOfTheIssuingUser() {
        User user = User.builder().id(5L).username("alex").active(true).build();
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any(), any())).thenReturn(List.of(5L));
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(userRepository.findByTelegramChatId(777L)).thenReturn(Optional.empty());

        Optional<String> reply = service.handleStartCommand(777L, "/start staff_AbCdEf123");

        assertThat(reply).isPresent();
        assertThat(user.getTelegramChatId()).isEqualTo(777L);
        verify(userRepository).save(user);
        verify(jdbcTemplate).update(eq("DELETE FROM staff_telegram_link_tokens WHERE token_hash = ?"), anyString());
    }

    @Test
    void unknownOrExpiredTokenNeverBinds() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any(), any())).thenReturn(List.of());

        Optional<String> reply = service.handleStartCommand(777L, "/start staff_expired");

        assertThat(reply).isPresent();
        verify(userRepository, never()).save(any());
    }

    @Test
    void chatAlreadyOwnedByAnotherAccountIsNotStolen() {
        User user = User.builder().id(5L).username("alex").active(true).build();
        User other = User.builder().id(6L).username("boss").active(true).telegramChatId(777L).build();
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any(), any())).thenReturn(List.of(5L));
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(userRepository.findByTelegramChatId(777L)).thenReturn(Optional.of(other));

        service.handleStartCommand(777L, "/start staff_AbCdEf123");

        assertThat(user.getTelegramChatId()).isNull();
        verify(userRepository, never()).save(any());
    }
}
