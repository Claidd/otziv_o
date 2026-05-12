package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.reputationai.api.dto.ReputationContentPackRequest;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReputationContentFactoryTest {

    private final LocalReputationContentFactory factory = new LocalReputationContentFactory();

    @Test
    void buildsExpectedContentBlocksFromSnapshot() {
        ResearchSnapshot snapshot = new ResearchSnapshot(
                1L,
                "Ромашка",
                "Иркутск",
                "https://example.ru",
                "Строительные материалы",
                "Декоративная штукатурка",
                "Комментарий",
                List.of("штукатурка"),
                List.of("доставка", "консультация перед покупкой"),
                List.of("помощь менеджера"),
                List.of("сроки доставки"),
                List.of(),
                List.of(new CompanySource("website", "Сайт", "https://example.ru", "Текст сайта")),
                "local",
                false,
                List.of(),
                0,
                1,
                List.of(),
                LocalDateTime.now()
        );

        ReputationContentPack pack = factory.create(
                snapshot,
                new ReputationContentPackRequest("декоративная штукатурка", null, null, null, true, 5, 5, 3, 3, null)
        );

        assertThat(pack.companyProfile().shortDescription()).contains("Ромашка");
        assertThat(pack.utp()).isNotEmpty();
        assertThat(pack.adTexts()).hasSize(5);
        assertThat(pack.socialPosts()).hasSize(5);
        assertThat(pack.socialPosts()).allMatch(post -> post.length() > 700);
        assertThat(pack.reviewDraftTemplates()).isNotEmpty();
        assertThat(pack.reviewDraftTemplates()).allMatch(draft -> draft.contains("Ромашка"));
        assertThat(pack.safetyNotes()).anyMatch(note -> note.contains("Не публикуйте"));
        assertThat(pack.sourceUrls()).contains("https://example.ru");
    }
}
