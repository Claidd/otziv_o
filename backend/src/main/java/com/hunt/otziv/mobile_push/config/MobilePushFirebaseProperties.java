package com.hunt.otziv.mobile_push.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "otziv.mobile.push.firebase")
public class MobilePushFirebaseProperties {

    private boolean enabled;

    private String appName = "otziv-mobile";

    private String projectId;

    private String serviceAccountPath;

    private String serviceAccountJson;
}
