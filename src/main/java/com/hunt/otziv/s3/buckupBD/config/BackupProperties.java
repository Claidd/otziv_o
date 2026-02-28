package com.hunt.otziv.s3.buckupBD.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "backup")
public class BackupProperties {

    private Mysql mysql = new Mysql();
    private String workDir = "/docker/backup";
    private int partSizeMb = 35;
    private Mail mail = new Mail();

    @Data
    public static class Mysql {
        private String container;
        private String db;
        private String user;
        private String password;
    }

    @Data
    public static class Mail {
        private String to;
        private String from;
        private String subject;
        private String body;
    }
}

