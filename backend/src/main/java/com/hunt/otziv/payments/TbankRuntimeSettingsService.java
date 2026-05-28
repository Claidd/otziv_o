package com.hunt.otziv.payments;

import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.payments.dto.TbankRuntimeSettingsResponse;
import com.hunt.otziv.payments.dto.UpdateTbankRuntimeSettingsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TbankRuntimeSettingsService {

    public static final String PAYMENT_SOURCE_MANAGER_TEXT = "MANAGER_TEXT";
    public static final String PAYMENT_SOURCE_TBANK_LINK = "TBANK_LINK";
    public static final TbankPaymentPageMode DEFAULT_PAYMENT_PAGE_MODE = TbankPaymentPageMode.SBP_PRIMARY;

    private final AppSettingService appSettingService;
    private final TbankPaymentProperties properties;

    @Transactional(readOnly = true)
    public TbankRuntimeSettingsResponse response() {
        return buildResponse(
                runtimeMode(),
                isTbankEnabled(),
                isPaymentLinksEnabled(),
                isManagerUiEnabled(),
                isApplyConfirmedPayments(),
                paymentInstructionSource(),
                paymentPageMode(),
                isTpayEnabled(),
                isSberpayEnabled(),
                isMirpayEnabled()
        );
    }

    @Transactional
    public TbankRuntimeSettingsResponse update(UpdateTbankRuntimeSettingsRequest request) {
        TbankRuntimeMode mode = request == null || request.runtimeMode() == null
                ? runtimeMode()
                : TbankRuntimeMode.from(request.runtimeMode(), runtimeMode());
        boolean tbankEnabled = bool(request == null ? null : request.tbankEnabled(), isTbankEnabled());
        boolean paymentLinksEnabled = bool(request == null ? null : request.paymentLinksEnabled(), isPaymentLinksEnabled());
        boolean managerUiEnabled = bool(request == null ? null : request.managerUiEnabled(), isManagerUiEnabled());
        boolean applyConfirmedPayments = bool(
                request == null ? null : request.applyConfirmedPayments(),
                isApplyConfirmedPayments()
        );
        String paymentInstructionSource = normalizePaymentSource(
                request == null ? null : request.paymentInstructionSource(),
                paymentInstructionSource()
        );
        TbankPaymentPageMode paymentPageMode = TbankPaymentPageMode.from(
                request == null ? null : request.paymentPageMode(),
                paymentPageMode()
        );
        boolean tpayEnabled = bool(request == null ? null : request.tpayEnabled(), isTpayEnabled());
        boolean sberpayEnabled = bool(request == null ? null : request.sberpayEnabled(), isSberpayEnabled());
        boolean mirpayEnabled = bool(request == null ? null : request.mirpayEnabled(), isMirpayEnabled());

        validate(mode, tbankEnabled, paymentLinksEnabled, managerUiEnabled, applyConfirmedPayments, paymentInstructionSource);

        appSettingService.setString(AppSettingService.PAYMENTS_TBANK_RUNTIME_MODE, mode.name());
        appSettingService.setBoolean(AppSettingService.PAYMENTS_TBANK_ENABLED, tbankEnabled);
        appSettingService.setBoolean(AppSettingService.PAYMENTS_TBANK_PAYMENT_LINKS_ENABLED, paymentLinksEnabled);
        appSettingService.setBoolean(AppSettingService.PAYMENTS_TBANK_MANAGER_UI_ENABLED, managerUiEnabled);
        appSettingService.setBoolean(AppSettingService.PAYMENTS_TBANK_APPLY_CONFIRMED_PAYMENTS, applyConfirmedPayments);
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE, paymentInstructionSource);
        appSettingService.setString(AppSettingService.PAYMENTS_TBANK_PAYMENT_PAGE_MODE, paymentPageMode.name());
        appSettingService.setBoolean(AppSettingService.PAYMENTS_TBANK_TPAY_ENABLED, tpayEnabled);
        appSettingService.setBoolean(AppSettingService.PAYMENTS_TBANK_SBERPAY_ENABLED, sberpayEnabled);
        appSettingService.setBoolean(AppSettingService.PAYMENTS_TBANK_MIRPAY_ENABLED, mirpayEnabled);

        return buildResponse(
                mode,
                tbankEnabled,
                paymentLinksEnabled,
                managerUiEnabled,
                applyConfirmedPayments,
                paymentInstructionSource,
                paymentPageMode,
                tpayEnabled,
                sberpayEnabled,
                mirpayEnabled
        );
    }

    @Transactional
    public TbankRuntimeSettingsResponse updateClientPaymentSource(boolean useTbankLinks) {
        String source = useTbankLinks ? PAYMENT_SOURCE_TBANK_LINK : PAYMENT_SOURCE_MANAGER_TEXT;
        return update(new UpdateTbankRuntimeSettingsRequest(
                runtimeMode().name(),
                isTbankEnabled(),
                isPaymentLinksEnabled(),
                isManagerUiEnabled(),
                isApplyConfirmedPayments(),
                source,
                paymentPageMode().name(),
                isTpayEnabled(),
                isSberpayEnabled(),
                isMirpayEnabled()
        ));
    }

    @Transactional(readOnly = true)
    public TbankRuntimeMode runtimeMode() {
        return TbankRuntimeMode.from(
                appSettingService.getString(
                        AppSettingService.PAYMENTS_TBANK_RUNTIME_MODE,
                        properties.defaultRuntimeMode().name()
                ),
                properties.defaultRuntimeMode()
        );
    }

    @Transactional(readOnly = true)
    public boolean isTbankEnabled() {
        return appSettingService.getBoolean(AppSettingService.PAYMENTS_TBANK_ENABLED, properties.isEnabled());
    }

    @Transactional(readOnly = true)
    public boolean isPaymentLinksEnabled() {
        return appSettingService.getBoolean(
                AppSettingService.PAYMENTS_TBANK_PAYMENT_LINKS_ENABLED,
                properties.isPaymentLinksEnabled()
        );
    }

    @Transactional(readOnly = true)
    public boolean isManagerUiEnabled() {
        return appSettingService.getBoolean(
                AppSettingService.PAYMENTS_TBANK_MANAGER_UI_ENABLED,
                properties.isManagerUiEnabled()
        );
    }

    @Transactional(readOnly = true)
    public boolean isApplyConfirmedPayments() {
        return appSettingService.getBoolean(
                AppSettingService.PAYMENTS_TBANK_APPLY_CONFIRMED_PAYMENTS,
                properties.isApplyConfirmedPayments()
        );
    }

    @Transactional(readOnly = true)
    public String paymentInstructionSource() {
        return normalizePaymentSource(
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                        PAYMENT_SOURCE_MANAGER_TEXT
                ),
                PAYMENT_SOURCE_MANAGER_TEXT
        );
    }

    @Transactional(readOnly = true)
    public TbankPaymentPageMode paymentPageMode() {
        return TbankPaymentPageMode.from(
                appSettingService.getString(
                        AppSettingService.PAYMENTS_TBANK_PAYMENT_PAGE_MODE,
                        DEFAULT_PAYMENT_PAGE_MODE.name()
                ),
                DEFAULT_PAYMENT_PAGE_MODE
        );
    }

    @Transactional(readOnly = true)
    public boolean isTpayEnabled() {
        return appSettingService.getBoolean(AppSettingService.PAYMENTS_TBANK_TPAY_ENABLED, false);
    }

    @Transactional(readOnly = true)
    public boolean isSberpayEnabled() {
        return appSettingService.getBoolean(AppSettingService.PAYMENTS_TBANK_SBERPAY_ENABLED, false);
    }

    @Transactional(readOnly = true)
    public boolean isMirpayEnabled() {
        return appSettingService.getBoolean(AppSettingService.PAYMENTS_TBANK_MIRPAY_ENABLED, false);
    }

    private TbankRuntimeSettingsResponse buildResponse(
            TbankRuntimeMode runtimeMode,
            boolean tbankEnabled,
            boolean paymentLinksEnabled,
            boolean managerUiEnabled,
            boolean applyConfirmedPayments,
            String paymentInstructionSource,
            TbankPaymentPageMode paymentPageMode,
            boolean tpayEnabled,
            boolean sberpayEnabled,
            boolean mirpayEnabled
    ) {
        String source = normalizePaymentSource(paymentInstructionSource, PAYMENT_SOURCE_MANAGER_TEXT);
        return new TbankRuntimeSettingsResponse(
                runtimeMode.name(),
                runtimeMode.isTest(),
                tbankEnabled,
                paymentLinksEnabled,
                managerUiEnabled,
                applyConfirmedPayments,
                source,
                PAYMENT_SOURCE_TBANK_LINK.equals(source),
                (paymentPageMode == null ? DEFAULT_PAYMENT_PAGE_MODE : paymentPageMode).name(),
                tpayEnabled,
                sberpayEnabled,
                mirpayEnabled
        );
    }

    private void validate(
            TbankRuntimeMode runtimeMode,
            boolean tbankEnabled,
            boolean paymentLinksEnabled,
            boolean managerUiEnabled,
            boolean applyConfirmedPayments,
            String paymentInstructionSource
    ) {
        if (runtimeMode.isTest() && applyConfirmedPayments) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "В тестовом режиме нельзя засчитывать платежи как реальные"
            );
        }
        if (runtimeMode.isTest() && PAYMENT_SOURCE_TBANK_LINK.equals(paymentInstructionSource)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "В тестовом режиме нельзя отправлять клиентам ссылки T-Bank"
            );
        }
        if (PAYMENT_SOURCE_TBANK_LINK.equals(paymentInstructionSource)
                && (!tbankEnabled || !paymentLinksEnabled || !managerUiEnabled)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Для отправки ссылок T-Bank включите API, создание ссылок и UI менеджера"
            );
        }
    }

    private boolean bool(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private String normalizePaymentSource(String value, String fallback) {
        String clean = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (PAYMENT_SOURCE_TBANK_LINK.equals(clean)) {
            return PAYMENT_SOURCE_TBANK_LINK;
        }
        if (PAYMENT_SOURCE_MANAGER_TEXT.equals(clean)) {
            return PAYMENT_SOURCE_MANAGER_TEXT;
        }
        return fallback == null || fallback.isBlank() ? PAYMENT_SOURCE_MANAGER_TEXT : fallback;
    }
}
