package com.hunt.otziv.b_bots.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BotCrudLegacyTemplateContractTest {

    @Test
    void createAndEditFormsNeverRenderStoredPassword() throws Exception {
        String createTemplate = new ClassPathResource(
                "templates/bots/bot_add.html"
        ).getContentAsString(StandardCharsets.UTF_8);
        String editTemplate = new ClassPathResource(
                "templates/bots/bot_edit.html"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(createTemplate).contains(
                "type=\"password\"",
                "name=\"password\"",
                "autocomplete=\"new-password\"",
                "required"
        ).doesNotContain(
                "th:field=\"*{password}\"",
                "getPassword()"
        );
        assertThat(editTemplate).contains(
                "type=\"password\"",
                "name=\"password\" value=\"\"",
                "autocomplete=\"new-password\"",
                "Оставьте пустым, чтобы сохранить текущий пароль"
        ).doesNotContain(
                "th:field=\"*{password}\"",
                "getPassword()",
                "editBotDto.password"
        );
    }

    @Test
    void workerListUsesTheGuardedPostDeleteContractWithoutRenderingCredentials() throws Exception {
        String template = new ClassPathResource(
                "templates/fragments/bot_workers.html"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(template).contains(
                "sec:authorize=\"hasAnyRole('ADMIN', 'OWNER', 'WORKER')\"",
                "<form method=\"post\" th:action=\"'/bots/delete/' + ${bot.id}\"",
                "Скрыт — используйте новый кабинет"
        ).doesNotContain(
                "th:method=\"delete\"",
                "${bot.login}",
                "${bot.password}"
        );
    }

    @Test
    void globalListKeepsAdminOwnerVisibilityAndUsesPostDelete() throws Exception {
        String template = new ClassPathResource(
                "templates/bots/bots_list.html"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(template).contains(
                "sec:authorize=\"hasAnyRole('ADMIN', 'OWNER')\"",
                "<form method=\"post\" th:action=\"'/bots/delete/' + ${bot.id}\""
        ).doesNotContain(
                "th:method=\"delete\"",
                "${bot.password}"
        );
    }
}
