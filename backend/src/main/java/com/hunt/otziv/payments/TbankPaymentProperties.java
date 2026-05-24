package com.hunt.otziv.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "otziv.payments.tbank")
public class TbankPaymentProperties implements EnvironmentAware {

    private boolean enabled = false;
    private boolean paymentLinksEnabled = true;
    private boolean managerUiEnabled = false;
    private boolean applyConfirmedPayments = false;
    private String baseUrl = "https://securepay.tinkoff.ru";
    private String primaryName = "Основной магазин";
    private String primaryTerminalKey = "";
    private String primaryPassword = "";
    private String secondaryTerminalKey = "";
    private String secondaryPassword = "";
    private String terminalKey = "";
    private String password = "";
    private String publicBaseUrl = "https://o-ogo.ru";
    private String notificationPath = "/api/payments/tbank/webhook";
    private String successPath = "/pay/success";
    private String failPath = "/pay/fail";
    private Duration linkTtl = Duration.ofDays(90);
    private Duration redirectDue = Duration.ofDays(7);
    private String taxation = "usn_income";
    private String tax = "none";
    private String paymentMethod = "full_payment";
    private String paymentObject = "service";
    private String receiptItemName = "Репутационное сопровождение компании в сети Интернет";
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPaymentLinksEnabled() {
        return paymentLinksEnabled;
    }

    public void setPaymentLinksEnabled(boolean paymentLinksEnabled) {
        this.paymentLinksEnabled = paymentLinksEnabled;
    }

    public boolean isManagerUiEnabled() {
        return managerUiEnabled;
    }

    public void setManagerUiEnabled(boolean managerUiEnabled) {
        this.managerUiEnabled = managerUiEnabled;
    }

    public boolean isApplyConfirmedPayments() {
        return applyConfirmedPayments;
    }

    public void setApplyConfirmedPayments(boolean applyConfirmedPayments) {
        this.applyConfirmedPayments = applyConfirmedPayments;
    }

    public String getBaseUrl() {
        return trimTrailingSlash(baseUrl);
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getTerminalKey() {
        return fallback(terminalKey, getPrimaryTerminalKey());
    }

    public void setTerminalKey(String terminalKey) {
        this.terminalKey = terminalKey;
    }

    public String getPassword() {
        return fallback(password, getPrimaryPassword());
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPrimaryName() {
        return fallback(primaryName, "Основной магазин");
    }

    public void setPrimaryName(String primaryName) {
        this.primaryName = primaryName;
    }

    public String getPrimaryTerminalKey() {
        return fallback(primaryTerminalKey, terminalKey);
    }

    public void setPrimaryTerminalKey(String primaryTerminalKey) {
        this.primaryTerminalKey = primaryTerminalKey;
    }

    public String getPrimaryPassword() {
        return fallback(primaryPassword, password);
    }

    public void setPrimaryPassword(String primaryPassword) {
        this.primaryPassword = primaryPassword;
    }

    public String getSecondaryTerminalKey() {
        return safe(secondaryTerminalKey);
    }

    public void setSecondaryTerminalKey(String secondaryTerminalKey) {
        this.secondaryTerminalKey = secondaryTerminalKey;
    }

    public String getSecondaryPassword() {
        return safe(secondaryPassword);
    }

    public void setSecondaryPassword(String secondaryPassword) {
        this.secondaryPassword = secondaryPassword;
    }

    public String getPublicBaseUrl() {
        return trimTrailingSlash(publicBaseUrl);
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getNotificationPath() {
        return ensureLeadingSlash(notificationPath);
    }

    public void setNotificationPath(String notificationPath) {
        this.notificationPath = notificationPath;
    }

    public String getSuccessPath() {
        return ensureLeadingSlash(successPath);
    }

    public void setSuccessPath(String successPath) {
        this.successPath = successPath;
    }

    public String getFailPath() {
        return ensureLeadingSlash(failPath);
    }

    public void setFailPath(String failPath) {
        this.failPath = failPath;
    }

    public Duration getLinkTtl() {
        return linkTtl;
    }

    public void setLinkTtl(Duration linkTtl) {
        this.linkTtl = linkTtl == null ? Duration.ofDays(90) : linkTtl;
    }

    public Duration getRedirectDue() {
        return redirectDue;
    }

    public void setRedirectDue(Duration redirectDue) {
        this.redirectDue = redirectDue == null ? Duration.ofDays(7) : redirectDue;
    }

    public String getTaxation() {
        return safe(taxation);
    }

    public void setTaxation(String taxation) {
        this.taxation = taxation;
    }

    public String getTax() {
        return safe(tax);
    }

    public void setTax(String tax) {
        this.tax = tax;
    }

    public String getPaymentMethod() {
        return safe(paymentMethod);
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getPaymentObject() {
        return safe(paymentObject);
    }

    public void setPaymentObject(String paymentObject) {
        this.paymentObject = paymentObject;
    }

    public String getReceiptItemName() {
        String value = safe(receiptItemName);
        return value.isBlank() ? "Услуга" : value;
    }

    public void setReceiptItemName(String receiptItemName) {
        this.receiptItemName = receiptItemName;
    }

    public boolean hasCredentials() {
        return defaultProfile().hasCredentials();
    }

    public TbankPaymentProfile defaultProfile() {
        return new TbankPaymentProfile(
                null,
                TbankPaymentProfile.PRIMARY_CODE,
                getPrimaryName(),
                true,
                getPrimaryTerminalKey(),
                getPrimaryPassword(),
                isTestMode(getPrimaryTerminalKey())
        );
    }

    public String terminalKeyFor(PaymentProfile profile) {
        if (profile == null) {
            return getPrimaryTerminalKey();
        }
        if (isPrimaryProfile(profile)) {
            return fallback(getPrimaryTerminalKey(), profile.getTerminalKey());
        }
        if (TbankPaymentProfile.SECONDARY_CODE.equals(profile.getCode())) {
            return fallback(getSecondaryTerminalKey(), profile.getTerminalKey());
        }
        return safe(profile.getTerminalKey());
    }

    public String passwordFor(PaymentProfile profile) {
        if (profile == null) {
            return getPrimaryPassword();
        }
        if (isPrimaryProfile(profile)) {
            String primary = getPrimaryPassword();
            if (!primary.isBlank()) {
                return primary;
            }
        }
        if (TbankPaymentProfile.SECONDARY_CODE.equals(profile.getCode())) {
            String secondary = getSecondaryPassword();
            if (!secondary.isBlank()) {
                return secondary;
            }
        }
        String envKey = safe(profile.getPasswordEnvKey());
        if (!envKey.isBlank()) {
            String configured = configuredValue(envKey);
            if (!configured.isBlank()) {
                return configured;
            }
        }
        if (isPrimaryProfile(profile)) {
            return getPassword();
        }
        return "";
    }

    public boolean isTestMode(String terminalKey) {
        return getBaseUrl().contains("test") || safe(terminalKey).endsWith("DEMO");
    }

    public String notificationUrl() {
        return getPublicBaseUrl() + getNotificationPath();
    }

    public String successUrl() {
        return getPublicBaseUrl() + getSuccessPath();
    }

    public String failUrl() {
        return getPublicBaseUrl() + getFailPath();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String clean = safe(value);
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private static String ensureLeadingSlash(String value) {
        String clean = safe(value);
        if (clean.isBlank()) {
            return "";
        }
        return clean.startsWith("/") ? clean : "/" + clean;
    }

    private static String fallback(String value, String fallback) {
        String clean = safe(value);
        return clean.isBlank() ? safe(fallback) : clean;
    }

    private static boolean isPrimaryProfile(PaymentProfile profile) {
        return profile.isDefaultProfile() || TbankPaymentProfile.PRIMARY_CODE.equals(profile.getCode());
    }

    private String configuredValue(String key) {
        if (environment != null) {
            String value = safe(environment.getProperty(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        String property = safe(System.getProperty(key));
        if (!property.isBlank()) {
            return property;
        }
        return safe(System.getenv(key));
    }
}
