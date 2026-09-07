package com.hunt.otziv.c_companies.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class TwoGisLinkResolver {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    public String resolve(String shortUrl) {
        URI uri = TwoGisUrl.parse(shortUrl);
        // Check every hop, and never request arbitrary hosts or download a card page.
        for (int hop = 0; hop < 5; hop++) {
            var card = TwoGisUrl.cardId(uri);
            if (card.isPresent()) {
                return card.get();
            }
            if (!TwoGisUrl.isShort(uri)) {
                break;
            }
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create(TwoGisUrl.shortKey(uri)))
                                .timeout(Duration.ofSeconds(4)).header("User-Agent", "Mozilla/5.0")
                                .GET().build(), HttpResponse.BodyHandlers.discarding());
                if (!java.util.Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                    break;
                }
                String location = response.headers().firstValue("Location").orElse("");
                uri = TwoGisUrl.parse(uri.resolve(location).toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw unavailable(e);
            } catch (IOException | IllegalArgumentException e) {
                throw unavailable(e);
            }
        }
        throw unavailable(null);
    }

    private IllegalArgumentException unavailable(Exception cause) {
        return new IllegalArgumentException(
                "Не удалось раскрыть короткую ссылку 2ГИС. Вставьте полную ссылку на карточку организации", cause);
    }
}
