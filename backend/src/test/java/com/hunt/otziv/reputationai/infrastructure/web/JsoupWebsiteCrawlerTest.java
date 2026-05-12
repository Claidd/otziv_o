package com.hunt.otziv.reputationai.infrastructure.web;

import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JsoupWebsiteCrawlerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void extractsStructuredFactsAndFollowsSitemap() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> send(exchange, """
                <!doctype html>
                <html>
                  <head>
                    <title>Центр развлечений</title>
                    <meta name="description" content="Квесты, лазертаг и день рождения под ключ">
                    <script type="application/ld+json">
                    [
                      {
                        "@context": "http://schema.org",
                        "@type": "Product",
                        "name": "День рождения под ключ",
                        "description": "Праздник с квестами, лазертагом, дискотекой и чайной зоной",
                        "offers": {
                          "@type": "Offer",
                          "price": "19500",
                          "priceCurrency": "RUB"
                          "shippingDetails": {}
                        }
                      }
                    ]
                    </script>
                  </head>
                  <body>
                    <h1>Центр детских развлечений</h1>
                    <p>Бесплатная чайная зона, квесты с актерами и программы для детей.</p>
                  </body>
                </html>
                """, "text/html", 200));
        server.createContext("/sitemap.xml", exchange -> send(exchange, """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>%s/laser.html</loc></url>
                </urlset>
                """.formatted(baseUrl()), "text/xml", 200));
        server.createContext("/laser.html", exchange -> send(exchange, """
                <!doctype html>
                <html>
                  <head>
                    <title>Лазертаг в Иркутске и Ангарске</title>
                    <meta name="description" content="Детский лазертаг на день рождения">
                  </head>
                  <body>
                    <h2>ЛАЗЕРТАГ</h2>
                    <p>Стоимость 1500 руб. В игре используются комплекты нового поколения.</p>
                    <img src="/laser.webp" alt="Лазертаг Иркутск">
                  </body>
                </html>
                """, "text/html", 200));
        server.start();

        ReputationAiProperties properties = new ReputationAiProperties();
        properties.setMaxWebsitePages(3);
        properties.setMaxWebsiteChars(8000);
        JsoupWebsiteCrawler crawler = new JsoupWebsiteCrawler(properties);

        List<CrawledPage> pages = crawler.crawl(List.of(baseUrl() + "/"));
        String text = pages.stream()
                .map(CrawledPage::text)
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(pages).hasSize(2);
        assertThat(text).contains("Услуга/товар: День рождения под ключ");
        assertThat(text).contains("цена: 19500 RUB");
        assertThat(text).contains("Услуга/товар: Лазертаг");
        assertThat(text).contains("цена: 1500 руб");
        assertThat(text).contains("Фото/изображение: Лазертаг Иркутск");
    }

    @Test
    void keepsReviewPlatformsSinglePage() {
        JsoupWebsiteCrawler crawler = new JsoupWebsiteCrawler(new ReputationAiProperties());

        assertThat(crawler.shouldFollowInternalLinks("yandex.ru")).isFalse();
        assertThat(crawler.shouldFollowInternalLinks("2gis.ru")).isFalse();
        assertThat(crawler.shouldFollowInternalLinks("maps.yandex.ru")).isFalse();
        assertThat(crawler.shouldFollowInternalLinks("angarsk.mir-kvestov.ru")).isFalse();
        assertThat(crawler.shouldFollowInternalLinks("naigru.ru")).isTrue();
    }

    @Test
    void deepCrawlsOnlyExplicitHostsWhenProvided() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> send(exchange, """
                <!doctype html>
                <html>
                  <head><title>Внешняя карточка</title></head>
                  <body>
                    <h1>Iquest</h1>
                    <a href="/nested.html">Внутренняя ссылка агрегатора</a>
                  </body>
                </html>
                """, "text/html", 200));
        server.createContext("/sitemap.xml", exchange -> send(exchange, """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>%s/nested.html</loc></url>
                </urlset>
                """.formatted(baseUrl()), "text/xml", 200));
        server.createContext("/nested.html", exchange -> send(exchange, """
                <!doctype html>
                <html><head><title>Шум агрегатора</title></head><body>Эта страница не должна читаться.</body></html>
                """, "text/html", 200));
        server.start();

        ReputationAiProperties properties = new ReputationAiProperties();
        properties.setMaxWebsitePages(4);
        JsoupWebsiteCrawler crawler = new JsoupWebsiteCrawler(properties);

        List<CrawledPage> pages = crawler.crawl(List.of(baseUrl() + "/"), Set.of("official.example"));

        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().url()).isEqualTo(baseUrl() + "/");
    }

    @Test
    void rejectsKnownYandexServiceUrlsButKeepsMapCards() {
        JsoupWebsiteCrawler crawler = new JsoupWebsiteCrawler(new ReputationAiProperties());

        assertThat(crawler.isKnownPlatformServiceUrl("https://yandex.ru/legal/maps_mobile_agreement/ru")).isTrue();
        assertThat(crawler.isKnownPlatformServiceUrl("https://yandex.ru/support/business-priority/ru/manage/sticker")).isTrue();
        assertThat(crawler.isKnownPlatformServiceUrl("https://yandex.ru/project/maps/goodplace/2026")).isTrue();
        assertThat(crawler.isKnownPlatformServiceUrl("https://yandex.ru/maps/org/ay_kvest/17092564278/reviews/")).isFalse();
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void send(HttpExchange exchange, String body, String contentType, int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
