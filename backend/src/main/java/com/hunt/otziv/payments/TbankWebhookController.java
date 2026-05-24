package com.hunt.otziv.payments;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class TbankWebhookController {

    private final PaymentLinkService paymentLinkService;
    private final ObjectMapper objectMapper;

    @PostMapping(
            value = "/api/payments/tbank/webhook",
            consumes = {
                    MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                    MediaType.APPLICATION_JSON_VALUE,
                    MediaType.ALL_VALUE
            },
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    public ResponseEntity<String> webhook(
            HttpServletRequest request,
            @RequestParam MultiValueMap<String, String> params,
            @RequestBody(required = false) String body
    ) {
        paymentLinkService.handleTbankWebhook(payload(request, params, body));
        return ResponseEntity.ok("OK");
    }

    private Map<String, String> payload(HttpServletRequest request, MultiValueMap<String, String> params, String body) {
        Map<String, String> payload = new LinkedHashMap<>();
        params.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                payload.put(key, values.get(0));
            }
        });

        if (!payload.isEmpty() || body == null || body.isBlank()) {
            return payload;
        }

        if (request.getContentType() != null && request.getContentType().contains(MediaType.APPLICATION_JSON_VALUE)) {
            try {
                Map<String, Object> json = objectMapper.readValue(body, new TypeReference<>() {
                });
                json.forEach((key, value) -> {
                    if (value != null && !(value instanceof Map<?, ?>) && !(value instanceof Iterable<?>)) {
                        payload.put(key, String.valueOf(value));
                    }
                });
            } catch (Exception ignored) {
                // Invalid webhook bodies are handled by token validation in the service.
            }
        }
        return payload;
    }
}
