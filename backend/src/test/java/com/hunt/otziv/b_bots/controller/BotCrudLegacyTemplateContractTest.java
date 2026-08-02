package com.hunt.otziv.b_bots.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BotCrudLegacyTemplateContractTest {

    @Test
    void workerListUsesTheGuardedPostDeleteContract() throws Exception {
        String template = new ClassPathResource(
                "templates/fragments/bot_workers.html"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(template).contains(
                "sec:authorize=\"hasAnyRole('ADMIN', 'OWNER', 'WORKER')\"",
                "<form method=\"post\" th:action=\"'/bots/delete/' + ${bot.id}\""
        ).doesNotContain("th:method=\"delete\"");
    }

    @Test
    void globalListKeepsAdminOwnerVisibilityAndUsesPostDelete() throws Exception {
        String template = new ClassPathResource(
                "templates/bots/bots_list.html"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(template).contains(
                "sec:authorize=\"hasAnyRole('ADMIN', 'OWNER')\"",
                "<form method=\"post\" th:action=\"'/bots/delete/' + ${bot.id}\""
        ).doesNotContain("th:method=\"delete\"");
    }
}
