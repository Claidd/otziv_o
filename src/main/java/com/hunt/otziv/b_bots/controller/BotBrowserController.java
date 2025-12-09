package com.hunt.otziv.b_bots.controller;

import com.hunt.otziv.b_bots.config.MultiBrowserProperties;
import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.dto.BrowserOpenResponse;
import com.hunt.otziv.b_bots.services.BotService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
public class BotBrowserController {

    @Qualifier("browserRestTemplate")
    private final RestTemplate browserRestTemplate;
    private final MultiBrowserProperties multiProps;
    private final BotService botService;

    private String buildExternalKey(BotDTO bot) {
        String raw = (bot.getLogin() == null ? "" : bot.getLogin());
        // убираем все пробельные символы (пробел, таб, перенос строки)
        String phone = raw.trim().replaceAll("\\s+", "");
        return phone + "-" + bot.getId();
    }

    @PostMapping("/{botId}/browser/open")
    public ResponseEntity<BrowserOpenResponse> openBrowser(@PathVariable Long botId) {
        BotDTO bot = botService.findById(botId);
        String externalKey = buildExternalKey(bot);

        String url = multiProps.getBaseUrl() + "/integration/profiles/connect";

        Map<String, String> body = Map.of(
                "externalKey", externalKey,
                "proxyUrl", ""   // потом сюда можно подставлять прокси бота
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> mbResp = browserRestTemplate.postForEntity(url, entity, Map.class);

        if (!mbResp.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "multi-browser вернул статус " + mbResp.getStatusCode()
            );
        }

        Map<String, Object> respBody = mbResp.getBody();
        if (respBody == null || !respBody.containsKey("vncUrl")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "В ответе multi-browser нет поля vncUrl"
            );
        }

        System.out.println("respBody:" + respBody);

        String vncUrl = (String) respBody.get("vncUrl");

        System.out.println(vncUrl);

        return ResponseEntity.ok(new BrowserOpenResponse(botId, vncUrl));
    }

    @PostMapping("/{botId}/browser/close")
    public ResponseEntity<Void> closeBrowser(@PathVariable Long botId) {
        BotDTO bot = botService.findById(botId);
        String externalKey = buildExternalKey(bot);

        String url = multiProps.getBaseUrl()
                + "/integration/profiles/" + externalKey + "/stop";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        browserRestTemplate.postForLocation(url, entity);

        return ResponseEntity.noContent().build();
    }
}

