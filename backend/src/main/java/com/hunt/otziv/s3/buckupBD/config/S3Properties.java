package com.hunt.otziv.s3.buckupBD.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "s3")
public class S3Properties {
    private String accessKey;
    private String secretKey;
    private String endpoint;
    private String region;
    private String bucket;
    private String projectId;
}
