package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.PublicPaymentInitRequest;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PublicSbpBankResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.dto.TbankPaymentStatusResponse;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.TbankRuntimeMode;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublicPaymentController {

    private final PaymentLinkService paymentLinkService;
    private final TbankPaymentProperties properties;
    private final TbankRuntimeSettingsService runtimeSettingsService;
    private final PaymentProfileService paymentProfileService;

    @GetMapping("/api/payments/public/tbank-status")
    public TbankPaymentStatusResponse tbankStatus() {
        TbankRuntimeSettingsResponseSafe settings = settings();
        return new TbankPaymentStatusResponse(
                settings.tbankEnabled(),
                settings.paymentLinksEnabled(),
                settings.managerUiEnabled(),
                settings.applyConfirmedPayments(),
                activeProfile().hasCredentials(),
                settings.runtimeMode().isTest(),
                settings.runtimeMode().name(),
                properties.getBaseUrl(),
                properties.getPublicBaseUrl(),
                properties.notificationUrl(),
                properties.successUrl(),
                properties.failUrl()
        );
    }

    @GetMapping("/api/payments/public/{token}")
    public PublicPaymentLinkResponse paymentLink(@PathVariable String token) {
        return paymentLinkService.publicLink(token);
    }

    @GetMapping("/api/payments/public/{token}/sbp/banks")
    public List<PublicSbpBankResponse> sbpBanks(
            @PathVariable String token,
            HttpServletRequest servletRequest
    ) {
        return paymentLinkService.publicSbpBanks(
                token,
                clientDeviceType(servletRequest),
                clientOs(servletRequest)
        );
    }

    @PostMapping("/api/payments/public/{token}/init")
    public PublicPaymentInitResponse initPayment(
            @PathVariable String token,
            @Valid @RequestBody PublicPaymentInitRequest request,
            HttpServletRequest servletRequest
    ) {
        return paymentLinkService.init(
                token,
                request.email(),
                Boolean.TRUE.equals(request.offerConsent()),
                Boolean.TRUE.equals(request.privacyConsent()),
                Boolean.TRUE.equals(request.receiptConsent()),
                clientIp(servletRequest),
                userAgent(servletRequest)
        );
    }

    @PostMapping("/api/payments/public/{token}/sbp")
    public PublicPaymentInitResponse initSbpPayment(
            @PathVariable String token,
            @Valid @RequestBody PublicPaymentInitRequest request,
            HttpServletRequest servletRequest
    ) {
        return paymentLinkService.initSbp(
                token,
                request.email(),
                Boolean.TRUE.equals(request.offerConsent()),
                Boolean.TRUE.equals(request.privacyConsent()),
                Boolean.TRUE.equals(request.receiptConsent()),
                request.sbpBankId(),
                clientIp(servletRequest),
                userAgent(servletRequest)
        );
    }

    @PostMapping("/api/payments/public/{token}/manual-paid")
    public PublicPaymentLinkResponse reportManualPayment(@PathVariable String token) {
        return paymentLinkService.reportManualPayment(token);
    }

    private TbankRuntimeSettingsResponseSafe settings() {
        TbankRuntimeMode runtimeMode = runtimeSettingsService.runtimeMode();
        return new TbankRuntimeSettingsResponseSafe(
                runtimeMode,
                runtimeSettingsService.isTbankEnabled(),
                runtimeSettingsService.isPaymentLinksEnabled(),
                runtimeSettingsService.isManagerUiEnabled(),
                runtimeSettingsService.isApplyConfirmedPayments()
        );
    }

    private TbankPaymentProfile activeProfile() {
        try {
            return paymentProfileService.toRuntime(paymentProfileService.defaultEntityProfile());
        } catch (RuntimeException ignored) {
            return properties.defaultProfile(runtimeSettingsService.runtimeMode());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String userAgent(HttpServletRequest request) {
        String value = request.getHeader("User-Agent");
        return value == null ? "" : value.trim();
    }

    private String clientDeviceType(HttpServletRequest request) {
        String value = userAgent(request).toLowerCase();
        return value.contains("mobile")
                || value.contains("android")
                || value.contains("iphone")
                || value.contains("ipad")
                ? "mobile"
                : "desktop";
    }

    private String clientOs(HttpServletRequest request) {
        String value = userAgent(request).toLowerCase();
        if (value.contains("android")) {
            return "Android";
        }
        if (value.contains("iphone") || value.contains("ipad") || value.contains("ios")) {
            return "iOS";
        }
        if (value.contains("windows")) {
            return "Windows";
        }
        if (value.contains("mac os")) {
            return "macOS";
        }
        if (value.contains("linux")) {
            return "Linux";
        }
        return "";
    }

    private record TbankRuntimeSettingsResponseSafe(
            TbankRuntimeMode runtimeMode,
            boolean tbankEnabled,
            boolean paymentLinksEnabled,
            boolean managerUiEnabled,
            boolean applyConfirmedPayments
    ) {
    }
}
