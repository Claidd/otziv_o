package com.hunt.otziv.reputationai.infrastructure.search.yandex;

import com.hunt.otziv.reputationai.infrastructure.search.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YandexSearchXmlParserTest {

    private final YandexSearchXmlParser parser = new YandexSearchXmlParser();

    @Test
    void parsesSearchXmlDocuments() {
        String xml = """
                <yandexsearch>
                  <response>
                    <results>
                      <grouping>
                        <group>
                          <doc>
                            <url>https://example.ru/company</url>
                            <title>Компания Ромашка</title>
                            <headline>Продажа строительных материалов в Иркутске</headline>
                          </doc>
                        </group>
                      </grouping>
                    </results>
                  </response>
                </yandexsearch>
                """;

        List<SearchResult> results = parser.parse(xml, 5);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().url()).isEqualTo("https://example.ru/company");
        assertThat(results.getFirst().title()).isEqualTo("Компания Ромашка");
        assertThat(results.getFirst().snippet()).contains("строительных материалов");
    }
}
