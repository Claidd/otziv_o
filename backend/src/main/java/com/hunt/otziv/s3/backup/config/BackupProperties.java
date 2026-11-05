package com.hunt.otziv.s3.backup.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "backup")
public class BackupProperties {

    private boolean enabled = false;
    private Mysql mysql = new Mysql();
    /** Keycloak accounts, roles and credentials live in their own PostgreSQL database. */
    private Keycloak keycloak = new Keycloak();
    private String workDir = "/docker/backup";
    private int partSizeMb = 16;
    /**
     * Base64 encoded 256-bit key used for client-side AES-GCM encryption.
     * Backups deliberately fail closed when this is missing or malformed.
     */
    private String encryptionKeyBase64;
    private Duration dumpTimeout = Duration.ofMinutes(15);
    private Duration uploadTimeout = Duration.ofMinutes(10);
    private int maxStderrBytes = 64 * 1024;
    /** File name, inside workDir, receiving one JSON evidence record per verified backup. */
    private String evidenceFileName = "backup-evidence.jsonl";
    /** Immutable release commit recorded in evidence when supplied by the deployment. */
    private String sourceCommit;
    /** Most recently measured isolated restore time, recorded in evidence when supplied. */
    private Duration restoreDrillRto;
    /** Date (yyyy-MM-dd) of the latest isolated restore drill; the deploy gate refuses drills older than 90 days. */
    private String restoreDrillDate;
    /** Daily scheduling is independent from the backup engine so a verified one-shot run can be performed safely. */
    private Schedule schedule = new Schedule();
    /** Explicit, idempotent startup request used only for controlled verification runs. */
    private RunOnce runOnce = new RunOnce();
    private Mail mail = new Mail();

    @Data
    public static class Schedule {
        /** Preserves the historical behaviour whenever backup.enabled=true. */
        private boolean enabled = true;
        private String cron = "0 0 7 * * *";
        private String zone = "Asia/Irkutsk";
        /** Required bounded recovery of the previous daily occurrence after downtime. */
        private boolean catchUpEnabled = true;
        /** Covers the previous daily occurrence while remaining below the 36-hour hard limit. */
        private Duration catchUpWindow = Duration.ofHours(26);
        private Duration catchUpCheckInterval = Duration.ofMinutes(15);
        private Duration catchUpInitialDelay = Duration.ofMinutes(1);
    }

    @Data
    public static class RunOnce {
        private boolean enabled = false;
        /** Stable request id makes a successful startup run idempotent across container restarts. */
        private String requestId;
    }

    @Data
    public static class Mysql {
        private String container;
        private String host = "mysql";
        private int port = 3306;
        private String dumpBinary = "mysqldump";
        private String db;
        private String user;
        private String password;
    }

    @Data
    public static class Keycloak {
        /** Every enabled backup run also protects the Keycloak database unless explicitly disabled. */
        private boolean enabled = true;
        private String host = "keycloak-postgres";
        private int port = 5432;
        /** Must be at least the server major version: pg_dump refuses newer servers. */
        private String dumpBinary = "/usr/lib/postgresql/17/bin/pg_dump";
        private String db;
        private String user;
        private String password;
        /** Separate journal: MySQL evidence readers keep seeing only MySQL objects. */
        private String evidenceFileName = "keycloak-backup-evidence.jsonl";
    }

    @Data
    public static class Mail {
        private boolean enabled = false;
        private String to;
        private String from;
        private String subject;
        private String body;
    }
}
