package com.hunt.otziv.security.credentials;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "otziv.credential-encryption")
public class CredentialEncryptionProperties {

    private boolean required;
    private String activeKeyId = "primary";
    private String activeKeyBase64;
    private String previousKeys;
    private boolean backfillEnabled = true;
    private int backfillBatchSize = 250;

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public void setActiveKeyId(String activeKeyId) {
        this.activeKeyId = activeKeyId;
    }

    public String getActiveKeyBase64() {
        return activeKeyBase64;
    }

    public void setActiveKeyBase64(String activeKeyBase64) {
        this.activeKeyBase64 = activeKeyBase64;
    }

    public String getPreviousKeys() {
        return previousKeys;
    }

    public void setPreviousKeys(String previousKeys) {
        this.previousKeys = previousKeys;
    }

    public boolean isBackfillEnabled() {
        return backfillEnabled;
    }

    public void setBackfillEnabled(boolean backfillEnabled) {
        this.backfillEnabled = backfillEnabled;
    }

    public int getBackfillBatchSize() {
        return backfillBatchSize;
    }

    public void setBackfillBatchSize(int backfillBatchSize) {
        this.backfillBatchSize = backfillBatchSize;
    }
}
