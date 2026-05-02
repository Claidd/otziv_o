package com.hunt.otziv.s3.buckupBD.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BackupProperties.class)
public class BackupConfig {
}
