package com.hunt.otziv.s3.buckupBD.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "backup.s3")
public class BackupS3Properties {

    private String accessKey;
    private String secretKey;
    private String endpoint;
    private String region;
    private String bucket;
    private String projectId;
    private boolean forcePathStyle = true;
    private boolean requireServerSideEncryption = true;
    private boolean independentDestinationConfirmed = false;
    private boolean privateDestinationConfirmed = false;
    private boolean encryptionAtRestConfirmed = false;
    private boolean objectLockEnabled = false;
    private String objectLockMode = "GOVERNANCE";
    private int retentionDays = 0;
}
