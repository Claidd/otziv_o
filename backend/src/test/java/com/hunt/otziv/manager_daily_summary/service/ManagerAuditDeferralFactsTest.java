package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class ManagerAuditDeferralFactsTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ManagerAuditDeferralFacts facts = new ManagerAuditDeferralFacts(jdbc);

    @Test
    void deferralCountsOnlyWhenTheControlManagerDeferredThisCard() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of(
                event(13L, "Карточка: Другая карточка. Жду", 30L),
                event(4L, "Карточка: Пункт приема цветного и черного металла. Жду", 30L)));

        assertThat(facts.deferredByControlManager(731L, "Пункт приема цветного и черного металла")).isFalse();

        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of(
                event(30L, "Карточка: Пункт приема цветного и черного металла. Жду", 30L)));
        assertThat(facts.deferredByControlManager(731L, "Пункт приема цветного и черного металла")).isTrue();
        assertThat(facts.deferredByControlManager(null, "Пункт")).isFalse();
    }

    @Test
    void unknownAuthorshipOrBoardIsNotAttributedToTheManager() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(facts.deferredByControlManager(731L, "Пункт")).isFalse();
        assertThat(facts.stillOnBoard(3L, "ORDER:21052", LocalDate.of(2026, 10, 21))).isFalse();
    }

    @Test
    void problemIsOnTheBoardOnlyWithAnUnresolvedCardForTheSameEntity() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L, 1L);

        assertThat(facts.stillOnBoard(3L, "ORDER:21052", LocalDate.of(2026, 10, 21))).isFalse();
        assertThat(facts.stillOnBoard(3L, "ORDER:21052", LocalDate.of(2026, 10, 21))).isTrue();
    }

    private Map<String, Object> event(Long actor, String comment, Long managerUser) {
        Map<String, Object> row = new HashMap<>();
        row.put("actor_user_id", actor);
        row.put("comment", comment);
        row.put("manager_user_id", managerUser);
        return row;
    }
}
