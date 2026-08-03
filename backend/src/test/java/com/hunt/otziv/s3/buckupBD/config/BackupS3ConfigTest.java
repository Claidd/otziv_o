package com.hunt.otziv.s3.buckupBD.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BackupS3ConfigTest {

    @Test
    void acceptsExplicitlyConfirmedIndependentDestination() {
        assertThatCode(() -> BackupS3Config.validateIndependentDestination(validBackup(), primary()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPrimaryBucketOrPrimaryCredentials() {
        BackupS3Properties sameBucket = validBackup();
        sameBucket.setBucket("primary-bucket");
        assertThatThrownBy(() -> BackupS3Config.validateIndependentDestination(sameBucket, primary()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary S3 bucket");

        BackupS3Properties sameCredentials = validBackup();
        sameCredentials.setAccessKey("primary-access");
        assertThatThrownBy(() -> BackupS3Config.validateIndependentDestination(sameCredentials, primary()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials distinct");
    }

    @Test
    void rejectsUnconfirmedDestinationAndRetentionWithoutObjectLock() {
        BackupS3Properties unconfirmed = validBackup();
        unconfirmed.setIndependentDestinationConfirmed(false);
        assertThatThrownBy(() -> BackupS3Config.validateIndependentDestination(unconfirmed, primary()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("independent-destination-confirmed");

        BackupS3Properties unenforcedRetention = validBackup();
        unenforcedRetention.setRetentionDays(30);
        assertThatThrownBy(() -> BackupS3Config.validateIndependentDestination(unenforcedRetention, primary()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("object-lock-enabled=true");
    }

    @Test
    void acceptsGovernanceObjectLockWithPositiveRetention() {
        BackupS3Properties backup = validBackup();
        backup.setObjectLockEnabled(true);
        backup.setObjectLockMode("GOVERNANCE");
        backup.setRetentionDays(30);

        assertThatCode(() -> BackupS3Config.validateIndependentDestination(backup, primary()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPlaintextBackupTransport() {
        BackupS3Properties backup = validBackup();
        backup.setEndpoint("http://backup-s3.example.test");

        assertThatThrownBy(() -> BackupS3Config.validateIndependentDestination(backup, primary()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    private BackupS3Properties validBackup() {
        BackupS3Properties backup = new BackupS3Properties();
        backup.setEndpoint("https://backup-s3.example.test");
        backup.setRegion("us-east-1");
        backup.setBucket("independent-backup-bucket");
        backup.setProjectId("otziv-prod");
        backup.setAccessKey("backup-access");
        backup.setSecretKey("backup-secret");
        backup.setIndependentDestinationConfirmed(true);
        backup.setPrivateDestinationConfirmed(true);
        backup.setEncryptionAtRestConfirmed(true);
        return backup;
    }

    private S3Properties primary() {
        S3Properties primary = new S3Properties();
        primary.setEndpoint("https://primary-s3.example.test");
        primary.setBucket("primary-bucket");
        primary.setAccessKey("primary-access");
        primary.setSecretKey("primary-secret");
        return primary;
    }
}
