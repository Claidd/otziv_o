package com.hunt.otziv.s3.backup.config;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;

@Configuration
@ConditionalOnProperty(name = "backup.enabled", havingValue = "true")
@EnableConfigurationProperties(BackupS3Properties.class)
public class BackupS3Config {

    @Bean(name = "backupS3Client", destroyMethod = "close")
    public S3Client backupS3Client(BackupS3Properties backup, S3Properties primary) {
        validateIndependentDestination(backup, primary);
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                backup.getAccessKey().trim(),
                backup.getSecretKey().trim()
        );
        return S3Client.builder()
                .region(Region.of(backup.getRegion().trim()))
                .endpointOverride(parseEndpoint(backup.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .forcePathStyle(backup.isForcePathStyle())
                .build();
    }

    static void validateIndependentDestination(BackupS3Properties backup, S3Properties primary) {
        Objects.requireNonNull(backup, "backup.s3 configuration is required");
        requireNonBlank(backup.getAccessKey(), "backup.s3.access-key");
        requireNonBlank(backup.getSecretKey(), "backup.s3.secret-key");
        requireNonBlank(backup.getEndpoint(), "backup.s3.endpoint");
        requireNonBlank(backup.getRegion(), "backup.s3.region");
        requireNonBlank(backup.getBucket(), "backup.s3.bucket");
        requireNonBlank(backup.getProjectId(), "backup.s3.project-id");
        parseEndpoint(backup.getEndpoint());

        if (!backup.isIndependentDestinationConfirmed()) {
            throw new IllegalStateException(
                    "backup.s3.independent-destination-confirmed must be true when backups are enabled"
            );
        }
        if (!backup.isPrivateDestinationConfirmed()) {
            throw new IllegalStateException(
                    "backup.s3.private-destination-confirmed must be true when backups are enabled"
            );
        }
        if (!backup.isEncryptionAtRestConfirmed()) {
            throw new IllegalStateException(
                    "backup.s3.encryption-at-rest-confirmed must be true when backups are enabled"
            );
        }

        if (primary != null) {
            boolean sameBucket = equalsNormalized(backup.getBucket(), primary.getBucket());
            if (sameBucket) {
                throw new IllegalStateException("backup.s3 must not use the primary S3 bucket");
            }
            boolean sameAccessKey = equalsExact(backup.getAccessKey(), primary.getAccessKey());
            if (sameAccessKey) {
                throw new IllegalStateException("backup.s3 must use credentials distinct from primary S3");
            }
        }

        if (backup.getRetentionDays() < 0 || backup.getRetentionDays() > 36_500) {
            throw new IllegalStateException("backup.s3.retention-days must be between 0 and 36500");
        }
        if (backup.isObjectLockEnabled()) {
            if (backup.getRetentionDays() < 1) {
                throw new IllegalStateException(
                        "backup.s3.retention-days must be positive when Object Lock is enabled"
                );
            }
            String mode = requireNonBlank(backup.getObjectLockMode(), "backup.s3.object-lock-mode")
                    .toUpperCase(Locale.ROOT);
            if (ObjectLockMode.GOVERNANCE != ObjectLockMode.fromValue(mode)
                    && ObjectLockMode.COMPLIANCE != ObjectLockMode.fromValue(mode)) {
                throw new IllegalStateException("backup.s3.object-lock-mode must be GOVERNANCE or COMPLIANCE");
            }
        } else if (backup.getRetentionDays() != 0) {
            throw new IllegalStateException(
                    "backup.s3.retention-days requires backup.s3.object-lock-enabled=true"
            );
        }
    }

    private static URI parseEndpoint(String value) {
        URI endpoint;
        try {
            endpoint = URI.create(requireNonBlank(value, "backup.s3.endpoint"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("backup.s3.endpoint must be an absolute HTTPS URI", exception);
        }
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw new IllegalStateException("backup.s3.endpoint must be an absolute HTTPS URI without credentials, query or fragment");
        }
        return endpoint;
    }

    private static String requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required");
        }
        return value.trim();
    }

    private static boolean equalsNormalized(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean equalsExact(String left, String right) {
        return left != null && right != null && left.trim().equals(right.trim());
    }
}
