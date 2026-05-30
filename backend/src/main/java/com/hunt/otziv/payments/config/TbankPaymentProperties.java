package com.hunt.otziv.payments.config;

import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.TbankRuntimeMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

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
    private String primaryTestTerminalKey = "";
    private String primaryTestPassword = "";
    private String primaryLiveTerminalKey = "";
    private String primaryLivePassword = "";
    private String secondaryTerminalKey = "";
    private String secondaryPassword = "";
    private String secondaryTestTerminalKey = "";
    private String secondaryTestPassword = "";
    private String secondaryLiveTerminalKey = "";
    private String secondaryLivePassword = "";
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

    public String getPrimaryTestTerminalKey() {
        String explicit = safe(primaryTestTerminalKey);
        if (!explicit.isBlank()) {
            return explicit;
        }
        String current = fallback(primaryTerminalKey, terminalKey);
        return isDemoTerminal(current) ? current : "";
    }

    public void setPrimaryTestTerminalKey(String primaryTestTerminalKey) {
        this.primaryTestTerminalKey = primaryTestTerminalKey;
    }

    public String getPrimaryTestPassword() {
        String explicit = safe(primaryTestPassword);
        if (!explicit.isBlank()) {
            return explicit;
        }
        String testTerminal = getPrimaryTestTerminalKey();
        if (!testTerminal.isBlank() && testTerminal.equals(fallback(primaryTerminalKey, terminalKey))) {
            return getPrimaryPassword();
        }
        return "";
    }

    public void setPrimaryTestPassword(String primaryTestPassword) {
        this.primaryTestPassword = primaryTestPassword;
    }

    public String getPrimaryLiveTerminalKey() {
        String explicit = safe(primaryLiveTerminalKey);
        if (!explicit.isBlank()) {
            return explicit;
        }
        String current = fallback(primaryTerminalKey, terminalKey);
        return !current.isBlank() && !isDemoTerminal(current) ? current : "";
    }

    public void setPrimaryLiveTerminalKey(String primaryLiveTerminalKey) {
        this.primaryLiveTerminalKey = primaryLiveTerminalKey;
    }

    public String getPrimaryLivePassword() {
        String explicit = safe(primaryLivePassword);
        if (!explicit.isBlank()) {
            return explicit;
        }
        String liveTerminal = getPrimaryLiveTerminalKey();
        if (!liveTerminal.isBlank() && liveTerminal.equals(fallback(primaryTerminalKey, terminalKey))) {
            return getPrimaryPassword();
        }
        return "";
    }

    public void setPrimaryLivePassword(String primaryLivePassword) {
        this.primaryLivePassword = primaryLivePassword;
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

    public String getSecondaryTestTerminalKey() {
        String explicit = safe(secondaryTestTerminalKey);
        if (!explicit.isBlank()) {
            return explicit;
        }
        String current = safe(secondaryTerminalKey);
        return isDemoTerminal(current) ? current : "";
    }

    public void setSecondaryTestTerminalKey(String secondaryTestTerminalKey) {
        this.secondaryTestTerminalKey = secondaryTestTerminalKey;
    }

    public String getSecondaryTestPassword() {
        String explicit = safe(secondaryTestPassword);
        if (!explicit.isBlank()) {
            return explicit;
        }
        String testTerminal = getSecondaryTestTerminalKey();
        if (!testTerminal.isBlank() && testTerminal.equals(safe(secondaryTerminalKey))) {
            return getSecondaryPassword();
        }
        return "";
    }

    public void setSecondaryTestPassword(String secondaryTestPassword) {
        this.secondaryTestPassword = secondaryTestPassword;
    }

    public String getSecondaryLiveTerminalKey() {
        String explicit = safe(secondaryLiveTerminalKey);
        if (!explicit.isBlank()) {
            return explicit;
        }
        String current = safe(secondaryTerminalKey);
        return !current.isBlank() && !isDemoTerminal(current) ? current : "";
    }

    public void setSecondaryLiveTerminalKey(String secondaryLiveTerminalKey) {
        this.secondaryLiveTerminalKey = secondaryLiveTerminalKey;
    }

    public String getSecondaryLivePassword() {
        String explicit = safe(secondaryLivePassword);
        if (!explicit.isBlank()) {
            return explicit;
        }
        String liveTerminal = getSecondaryLiveTerminalKey();
        if (!liveTerminal.isBlank() && liveTerminal.equals(safe(secondaryTerminalKey))) {
            return getSecondaryPassword();
        }
        return "";
    }

    public void setSecondaryLivePassword(String secondaryLivePassword) {
        this.secondaryLivePassword = secondaryLivePassword;
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
        return defaultProfile(defaultRuntimeMode());
    }

    public TbankPaymentProfile defaultProfile(TbankRuntimeMode runtimeMode) {
        TbankRuntimeMode mode = runtimeMode == null ? defaultRuntimeMode() : runtimeMode;
        return new TbankPaymentProfile(
                null,
                TbankPaymentProfile.PRIMARY_CODE,
                getPrimaryName(),
                true,
                terminalKeyFor(null, mode),
                passwordFor(null, mode),
                mode.isTest()
        );
    }

    public TbankRuntimeMode defaultRuntimeMode() {
        return isDemoTerminal(getPrimaryTerminalKey()) ? TbankRuntimeMode.TEST : TbankRuntimeMode.LIVE;
    }

    public String terminalKeyFor(PaymentProfile profile) {
        return terminalKeyFor(profile, defaultRuntimeMode());
    }

    public String terminalKeyFor(PaymentProfile profile, TbankRuntimeMode runtimeMode) {
        TbankRuntimeMode mode = runtimeMode == null ? defaultRuntimeMode() : runtimeMode;
        if (profile == null) {
            return mode.isTest() ? getPrimaryTestTerminalKey() : getPrimaryLiveTerminalKey();
        }
        if (isPrimaryProfile(profile)) {
            return modeTerminal(
                    mode,
                    getPrimaryTestTerminalKey(),
                    getPrimaryLiveTerminalKey(),
                    profile.getTerminalKey()
            );
        }
        if (TbankPaymentProfile.SECONDARY_CODE.equals(profile.getCode())) {
            return modeTerminal(
                    mode,
                    getSecondaryTestTerminalKey(),
                    getSecondaryLiveTerminalKey(),
                    profile.getTerminalKey()
            );
        }
        return safe(profile.getTerminalKey());
    }

    public String passwordFor(PaymentProfile profile) {
        return passwordFor(profile, defaultRuntimeMode());
    }

    public String passwordFor(PaymentProfile profile, TbankRuntimeMode runtimeMode) {
        TbankRuntimeMode mode = runtimeMode == null ? defaultRuntimeMode() : runtimeMode;
        if (profile == null) {
            return mode.isTest() ? getPrimaryTestPassword() : getPrimaryLivePassword();
        }
        if (isPrimaryProfile(profile)) {
            String primary = mode.isTest() ? getPrimaryTestPassword() : getPrimaryLivePassword();
            if (!primary.isBlank()) {
                return primary;
            }
        }
        if (TbankPaymentProfile.SECONDARY_CODE.equals(profile.getCode())) {
            String secondary = mode.isTest() ? getSecondaryTestPassword() : getSecondaryLivePassword();
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

    public TbankPaymentProfile runtimeProfileForTerminal(PaymentProfile profile, String terminalKey) {
        String cleanTerminal = safe(terminalKey);
        if (profile == null || cleanTerminal.isBlank()) {
            return defaultProfile();
        }
        RuntimeCredential credential = credentialForTerminal(profile, cleanTerminal);
        if (credential == null) {
            TbankRuntimeMode mode = isDemoTerminal(cleanTerminal) ? TbankRuntimeMode.TEST : TbankRuntimeMode.LIVE;
            credential = new RuntimeCredential(cleanTerminal, passwordFor(profile, mode), mode.isTest());
        }
        return new TbankPaymentProfile(
                profile.getId(),
                profile.getCode(),
                profile.getName(),
                profile.isEnabled(),
                credential.terminalKey(),
                credential.password(),
                credential.testMode()
        );
    }

    public boolean matchesAnyTerminal(PaymentProfile profile, String terminalKey) {
        String clean = safe(terminalKey);
        if (profile == null || clean.isBlank()) {
            return false;
        }
        return clean.equals(safe(profile.getTerminalKey()))
                || clean.equals(terminalKeyFor(profile, TbankRuntimeMode.TEST))
                || clean.equals(terminalKeyFor(profile, TbankRuntimeMode.LIVE));
    }

    public boolean isConfiguredTestTerminal(String terminalKey) {
        return isDemoTerminal(terminalKey) || getBaseUrl().contains("test");
    }

    public boolean isTestMode(String terminalKey) {
        return isConfiguredTestTerminal(terminalKey);
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

    private static boolean isDemoTerminal(String terminalKey) {
        return safe(terminalKey).endsWith("DEMO");
    }

    private static String modeTerminal(TbankRuntimeMode mode, String testTerminal, String liveTerminal, String storedTerminal) {
        String selected = mode.isTest() ? safe(testTerminal) : safe(liveTerminal);
        if (!selected.isBlank()) {
            return selected;
        }
        String stored = safe(storedTerminal);
        if (mode.isTest()) {
            return isDemoTerminal(stored) ? stored : "";
        }
        return !stored.isBlank() && !isDemoTerminal(stored) ? stored : "";
    }

    private static boolean isPrimaryProfile(PaymentProfile profile) {
        return profile.isDefaultProfile() || TbankPaymentProfile.PRIMARY_CODE.equals(profile.getCode());
    }

    private RuntimeCredential credentialForTerminal(PaymentProfile profile, String terminalKey) {
        if (isPrimaryProfile(profile)) {
            RuntimeCredential primary = credentialForTerminal(
                    terminalKey,
                    getPrimaryTestTerminalKey(),
                    getPrimaryTestPassword(),
                    getPrimaryLiveTerminalKey(),
                    getPrimaryLivePassword()
            );
            if (primary != null) {
                return primary;
            }
        }
        if (TbankPaymentProfile.SECONDARY_CODE.equals(profile.getCode())) {
            RuntimeCredential secondary = credentialForTerminal(
                    terminalKey,
                    getSecondaryTestTerminalKey(),
                    getSecondaryTestPassword(),
                    getSecondaryLiveTerminalKey(),
                    getSecondaryLivePassword()
            );
            if (secondary != null) {
                return secondary;
            }
        }
        if (terminalKey.equals(safe(profile.getTerminalKey()))) {
            return new RuntimeCredential(
                    terminalKey,
                    passwordFor(profile, isDemoTerminal(terminalKey) ? TbankRuntimeMode.TEST : TbankRuntimeMode.LIVE),
                    isConfiguredTestTerminal(terminalKey)
            );
        }
        return null;
    }

    private RuntimeCredential credentialForTerminal(
            String terminalKey,
            String testTerminal,
            String testPassword,
            String liveTerminal,
            String livePassword
    ) {
        if (!safe(testTerminal).isBlank() && terminalKey.equals(safe(testTerminal))) {
            return new RuntimeCredential(terminalKey, safe(testPassword), true);
        }
        if (!safe(liveTerminal).isBlank() && terminalKey.equals(safe(liveTerminal))) {
            return new RuntimeCredential(terminalKey, safe(livePassword), false);
        }
        return null;
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

    private record RuntimeCredential(String terminalKey, String password, boolean testMode) {
    }
}
