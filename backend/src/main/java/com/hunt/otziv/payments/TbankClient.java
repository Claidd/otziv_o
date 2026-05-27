package com.hunt.otziv.payments;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TbankClient {

    private static final DateTimeFormatter TBANK_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final RestTemplate restTemplate;
    private final TbankPaymentProperties properties;
    private final TbankRuntimeSettingsService runtimeSettingsService;
    private final TbankTokenSigner tokenSigner;

    public TbankInitResponse init(TbankInitCommand command) {
        return init(properties.defaultProfile(), command);
    }

    public TbankInitResponse init(TbankPaymentProfile profile, TbankInitCommand command) {
        if (!runtimeSettingsService.isTbankEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Интернет-эквайринг Т-Банка выключен в настройках");
        }
        validateProfile(profile);

        Map<String, Object> payload = initPayload(profile, command);
        payload.put("Token", tokenSigner.sign(payload, profile.password()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<TbankInitResponse> response = restTemplate.postForEntity(
                    properties.getBaseUrl() + "/v2/Init",
                    new HttpEntity<>(payload, headers),
                    TbankInitResponse.class
            );
            TbankInitResponse body = response.getBody();
            if (body == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк вернул пустой ответ на Init");
            }
            if (!body.success()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, body.errorText());
            }
            return body;
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Ошибка запроса Init в Т-Банк: " + e.getResponseBodyAsString(),
                    e
            );
        }
    }

    public TbankCancelResponse cancel(TbankCancelCommand command) {
        return cancel(properties.defaultProfile(), command);
    }

    public TbankCancelResponse cancel(TbankPaymentProfile profile, TbankCancelCommand command) {
        if (!runtimeSettingsService.isTbankEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Интернет-эквайринг Т-Банка выключен в настройках");
        }
        validateProfile(profile);
        if (command.paymentId() == null || command.paymentId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не задан PaymentId для возврата");
        }

        Map<String, Object> payload = cancelPayload(profile, command);
        payload.put("Token", tokenSigner.sign(payload, profile.password()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<TbankCancelResponse> response = restTemplate.postForEntity(
                    properties.getBaseUrl() + "/v2/Cancel",
                    new HttpEntity<>(payload, headers),
                    TbankCancelResponse.class
            );
            TbankCancelResponse body = response.getBody();
            if (body == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк вернул пустой ответ на Cancel");
            }
            if (!body.success()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, body.errorText());
            }
            return body;
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Ошибка запроса Cancel в Т-Банк: " + e.getResponseBodyAsString(),
                    e
            );
        }
    }

    public TbankGetStateResponse getState(TbankPaymentProfile profile, String paymentId) {
        if (!runtimeSettingsService.isTbankEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Интернет-эквайринг Т-Банка выключен в настройках");
        }
        validateProfile(profile);
        if (paymentId == null || paymentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не задан PaymentId для проверки статуса");
        }

        Map<String, Object> payload = statePayload(profile, paymentId);
        payload.put("Token", tokenSigner.sign(payload, profile.password()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<TbankGetStateResponse> response = restTemplate.postForEntity(
                    properties.getBaseUrl() + "/v2/GetState",
                    new HttpEntity<>(payload, headers),
                    TbankGetStateResponse.class
            );
            TbankGetStateResponse body = response.getBody();
            if (body == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк вернул пустой ответ на GetState");
            }
            if (!body.success()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, body.errorText());
            }
            return body;
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Ошибка запроса GetState в Т-Банк: " + e.getResponseBodyAsString(),
                    e
            );
        }
    }

    public TbankGetQrResponse getQr(TbankPaymentProfile profile, TbankGetQrCommand command) {
        if (!runtimeSettingsService.isTbankEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Интернет-эквайринг Т-Банка выключен в настройках");
        }
        validateProfile(profile);
        if (command.paymentId() == null || command.paymentId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не задан PaymentId для СБП-ссылки");
        }

        Map<String, Object> payload = qrPayload(profile, command);
        payload.put("Token", tokenSigner.sign(payload, profile.password()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<TbankGetQrResponse> response = restTemplate.postForEntity(
                    properties.getBaseUrl() + "/v2/GetQr",
                    new HttpEntity<>(payload, headers),
                    TbankGetQrResponse.class
            );
            TbankGetQrResponse body = response.getBody();
            if (body == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк вернул пустой ответ на GetQr");
            }
            if (!body.success()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, body.errorText());
            }
            return body;
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Ошибка запроса GetQr в Т-Банк: " + e.getResponseBodyAsString(),
                    e
            );
        }
    }

    public TbankGetQrBankListResponse getQrBankList(TbankPaymentProfile profile, TbankGetQrBankListCommand command) {
        if (!runtimeSettingsService.isTbankEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Интернет-эквайринг Т-Банка выключен в настройках");
        }
        validateProfile(profile);

        Map<String, Object> payload = qrBankListPayload(profile, command);
        payload.put("Token", tokenSigner.sign(payload, profile.password()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<TbankGetQrBankListResponse> response = restTemplate.postForEntity(
                    properties.getBaseUrl() + "/v2/GetQrBankList",
                    new HttpEntity<>(payload, headers),
                    TbankGetQrBankListResponse.class
            );
            TbankGetQrBankListResponse body = response.getBody();
            if (body == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк вернул пустой ответ на GetQrBankList");
            }
            if (!body.success()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, body.errorText());
            }
            return body;
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Ошибка запроса GetQrBankList в Т-Банк: " + e.getResponseBodyAsString(),
                    e
            );
        }
    }

    private void validateProfile(TbankPaymentProfile profile) {
        if (profile == null || profile.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не выбран платежный профиль Т-Банка");
        }
        if (!profile.enabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платежный профиль Т-Банка «" + profile.displayName() + "» выключен"
            );
        }
        if (!profile.hasCredentials()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Для платежного профиля Т-Банка «" + profile.displayName() + "» не заданы TerminalKey или Password"
            );
        }
    }

    private Map<String, Object> initPayload(TbankPaymentProfile profile, TbankInitCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", profile.terminalKey());
        payload.put("Amount", command.amountKopecks());
        payload.put("OrderId", command.orderId());
        payload.put("Description", command.description());
        payload.put("PayType", "O");
        payload.put("NotificationURL", command.notificationUrl());
        payload.put("SuccessURL", command.successUrl());
        payload.put("FailURL", command.failUrl());
        if (command.redirectDueDate() != null) {
            payload.put("RedirectDueDate", formatRedirectDueDate(command.redirectDueDate()));
        }
        payload.put("DATA", Map.of("Email", command.email()));
        payload.put("Receipt", receipt(command));
        return payload;
    }

    private Map<String, Object> cancelPayload(TbankPaymentProfile profile, TbankCancelCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", profile.terminalKey());
        payload.put("PaymentId", command.paymentId());
        if (command.amountKopecks() != null && command.amountKopecks() > 0) {
            payload.put("Amount", command.amountKopecks());
        }
        return payload;
    }

    private Map<String, Object> statePayload(TbankPaymentProfile profile, String paymentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", profile.terminalKey());
        payload.put("PaymentId", paymentId);
        return payload;
    }

    private Map<String, Object> qrPayload(TbankPaymentProfile profile, TbankGetQrCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", profile.terminalKey());
        payload.put("PaymentId", command.paymentId());
        if (command.dataType() != null && !command.dataType().isBlank()) {
            payload.put("DataType", command.dataType());
        }
        if (command.bankId() != null && !command.bankId().isBlank()) {
            payload.put("BankId", command.bankId());
        }
        return payload;
    }

    private Map<String, Object> qrBankListPayload(TbankPaymentProfile profile, TbankGetQrBankListCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", profile.terminalKey());
        String scenarioType = command == null || command.scenarioType() == null || command.scenarioType().isBlank()
                ? "qr"
                : command.scenarioType();
        String deviceType = command == null || command.deviceType() == null || command.deviceType().isBlank()
                ? "mobile"
                : command.deviceType();
        String os = command == null || command.os() == null ? "" : command.os();
        payload.put("ScenarioType", scenarioType);
        payload.put("Device", Map.of(
                "Type", deviceType,
                "Os", os
        ));
        return payload;
    }

    static String formatRedirectDueDate(OffsetDateTime redirectDueDate) {
        return TBANK_DATE_TIME.format(redirectDueDate);
    }

    private Map<String, Object> receipt(TbankInitCommand command) {
        return Map.of(
                "Email", command.email(),
                "FfdVersion", "1.2",
                "Taxation", properties.getTaxation(),
                "Items", List.of(Map.of(
                        "Name", properties.getReceiptItemName(),
                        "Price", command.amountKopecks(),
                        "Quantity", 1,
                        "Amount", command.amountKopecks(),
                        "PaymentMethod", properties.getPaymentMethod(),
                        "PaymentObject", properties.getPaymentObject(),
                        "Tax", properties.getTax()
                ))
        );
    }
}
