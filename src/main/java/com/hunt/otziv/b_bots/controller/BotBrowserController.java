package com.hunt.otziv.b_bots.controller;

import com.hunt.otziv.b_bots.config.MultiBrowserProperties;
import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.dto.BrowserOpenResponse;
import com.hunt.otziv.b_bots.services.BotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
//@RestController
//@RequestMapping("/api/bots")
//@RequiredArgsConstructor
//@Slf4j
//public class BotBrowserController {
//
//    @Qualifier("browserRestTemplate")
//    private final RestTemplate browserRestTemplate;
//    private final MultiBrowserProperties multiProps;
//    private final BotService botService;
//
//    private String buildExternalKey(BotDTO bot) {
//        String raw = (bot.getLogin() == null ? "" : bot.getLogin());
//        String phone = raw.trim().replaceAll("\\s+", "");
//        return phone + "-" + bot.getId();
//    }
//
//    @PostMapping("/{botId}/browser/open")
//    public ResponseEntity<BrowserOpenResponse> openBrowser(@PathVariable Long botId) {
//        BotDTO bot = botService.findById(botId);
//        String externalKey = buildExternalKey(bot);
//
//        String url = multiProps.getBaseUrl() + "/integration/profiles/connect";
//
//        // Отправляем запрос без указания deviceType - будет выбран случайный мобильный
//        Map<String, Object> body = new HashMap<>();
//        body.put("externalKey", externalKey);
//        body.put("proxyUrl", ""); // можно добавить прокси бота
//        // Не указываем deviceType - будет выбран случайный мобильный
//        body.put("detectionLevel", "ENHANCED");
//        body.put("forceNewFingerprint", false);
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
//
//        try {
//            ResponseEntity<Map> mbResp = browserRestTemplate.postForEntity(url, entity, Map.class);
//
//            if (!mbResp.getStatusCode().is2xxSuccessful()) {
//                throw new ResponseStatusException(
//                        HttpStatus.BAD_GATEWAY,
//                        "multi-browser вернул статус " + mbResp.getStatusCode()
//                );
//            }
//
//            Map<String, Object> respBody = mbResp.getBody();
//            if (respBody == null || !respBody.containsKey("vncUrl")) {
//                throw new ResponseStatusException(
//                        HttpStatus.BAD_GATEWAY,
//                        "В ответе multi-browser нет поля vncUrl"
//                );
//            }
//
//            String vncUrl = (String) respBody.get("vncUrl");
//            String userAgent = (String) respBody.get("userAgent");
//            String platform = (String) respBody.get("platform");
//
//            log.info("Browser opened for bot {}: externalKey={}, userAgent={}, platform={}, vncUrl={}",
//                    botId, externalKey, userAgent, platform, vncUrl);
//
//            BrowserOpenResponse response = new BrowserOpenResponse(botId, vncUrl);
//            response.setUserAgent(userAgent);
//            response.setPlatform(platform);
//
//            return ResponseEntity.ok(response);
//
//        } catch (Exception e) {
//            log.error("Failed to open browser for bot {}: {}", botId, e.getMessage(), e);
//            throw new ResponseStatusException(
//                    HttpStatus.INTERNAL_SERVER_ERROR,
//                    "Failed to open browser: " + e.getMessage()
//            );
//        }
//    }
//
//    @PostMapping("/{botId}/browser/close")
//    public ResponseEntity<Void> closeBrowser(@PathVariable Long botId) {
//        BotDTO bot = botService.findById(botId);
//        String externalKey = buildExternalKey(bot);
//
//        String url = multiProps.getBaseUrl()
//                + "/integration/profiles/" + externalKey + "/stop";
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//        HttpEntity<Void> entity = new HttpEntity<>(headers);
//
//        try {
//            browserRestTemplate.postForLocation(url, entity);
//            log.info("Browser closed for bot {}: externalKey={}", botId, externalKey);
//            return ResponseEntity.noContent().build();
//        } catch (Exception e) {
//            log.error("Failed to close browser for bot {}: {}", botId, e.getMessage(), e);
//            throw new ResponseStatusException(
//                    HttpStatus.INTERNAL_SERVER_ERROR,
//                    "Failed to close browser: " + e.getMessage()
//            );
//        }
//    }
//}
