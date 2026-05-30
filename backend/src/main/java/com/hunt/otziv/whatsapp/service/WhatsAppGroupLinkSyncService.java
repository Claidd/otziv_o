package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class WhatsAppGroupLinkSyncService {

    private final WhatsAppProperties properties;
    private final WhatsAppService whatsAppService;
    private final WhatsAppGroupCompanyLinker groupCompanyLinker;
    private final AppSettingService appSettingService;

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_INTERVAL_MINUTES = 30;
    public static final int MIN_INTERVAL_MINUTES = 5;
    public static final int MAX_INTERVAL_MINUTES = 1440;

    @Scheduled(
            fixedDelayString = "${whatsapp.group-sync.tick-delay-ms:60000}",
            initialDelayString = "${whatsapp.group-sync.initial-delay-ms:120000}"
    )
    public void syncKnownGroups() {
        WhatsAppGroupSyncSettingsResponse settings = settings();
        if (!settings.enabled()) {
            log.debug("WhatsApp group sync skipped: disabled");
            return;
        }
        if (!syncDue(settings)) {
            log.debug("WhatsApp group sync skipped: lastRunAt={} intervalMinutes={}",
                    settings.lastRunAt(), settings.intervalMinutes());
            return;
        }

        runSync("scheduled");
    }

    @Transactional(readOnly = true)
    public WhatsAppGroupSyncSettingsResponse settings() {
        return new WhatsAppGroupSyncSettingsResponse(
                appSettingService.getBoolean(AppSettingService.WHATSAPP_GROUP_SYNC_ENABLED, DEFAULT_ENABLED),
                normalizeStoredInterval(
                        appSettingService.getInt(
                                AppSettingService.WHATSAPP_GROUP_SYNC_INTERVAL_MINUTES,
                                DEFAULT_INTERVAL_MINUTES
                        )
                ),
                appSettingService.getString(AppSettingService.WHATSAPP_GROUP_SYNC_LAST_RUN_AT, ""),
                Math.max(
                        0,
                        appSettingService.getInt(AppSettingService.WHATSAPP_GROUP_SYNC_LAST_LINKED_COUNT, 0)
                )
        );
    }

    @Transactional
    public WhatsAppGroupSyncSettingsResponse updateSettings(WhatsAppGroupSyncSettingsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Укажите настройки WhatsApp-синхронизации");
        }

        WhatsAppGroupSyncSettingsResponse current = settings();
        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        int intervalMinutes = request.intervalMinutes() == null
                ? current.intervalMinutes()
                : normalizeInterval(request.intervalMinutes());

        appSettingService.setBoolean(AppSettingService.WHATSAPP_GROUP_SYNC_ENABLED, enabled);
        appSettingService.setInt(AppSettingService.WHATSAPP_GROUP_SYNC_INTERVAL_MINUTES, intervalMinutes);
        return settings();
    }

    public WhatsAppGroupSyncSettingsResponse runNow() {
        runSync("manual");
        return settings();
    }

    private int runSync(String source) {
        long startedAt = System.currentTimeMillis();
        int linked = 0;
        int groups = 0;
        int clients = 0;

        log.info("WhatsApp group sync started source={}", source);
        List<WhatsAppProperties.ClientConfig> configuredClients = properties.getClients() != null
                ? properties.getClients()
                : List.of();
        if (configuredClients.isEmpty()) {
            log.warn("WhatsApp group sync skipped source={} reason=no_clients. Configure WHATSAPP_CLIENTS_0_ID and WHATSAPP_CLIENTS_0_URL",
                    source);
        } else {
            for (WhatsAppProperties.ClientConfig client : configuredClients) {
                if (client == null || !hasText(client.getId()) || !hasText(client.getUrl())) {
                    continue;
                }
                clients++;
                SyncClientResult result = syncClientGroupsWithSummary(client.getId());
                groups += result.groups();
                linked += result.linked();
            }
            if (clients == 0) {
                log.warn("WhatsApp group sync skipped source={} reason=no_usable_clients. Check WhatsApp client id/url values",
                        source);
            }
        }

        appSettingService.setString(AppSettingService.WHATSAPP_GROUP_SYNC_LAST_RUN_AT, Instant.now().toString());
        appSettingService.setInt(AppSettingService.WHATSAPP_GROUP_SYNC_LAST_LINKED_COUNT, linked);
        log.info("WhatsApp group sync finished source={} clients={} groups={} linked={} durationMs={}",
                source, clients, groups, linked, System.currentTimeMillis() - startedAt);
        return linked;
    }

    int syncClientGroups(String clientId) {
        return syncClientGroupsWithSummary(clientId).linked();
    }

    private SyncClientResult syncClientGroupsWithSummary(String clientId) {
        int linked = 0;
        if (!hasText(clientId)) {
            return new SyncClientResult(0, 0);
        }

        List<WhatsAppGroupInfo> groups = whatsAppService.listGroups(clientId);
        if (groups.isEmpty()) {
            log.info("WhatsApp group sync client={} groups=0 linked=0", clientId);
            return new SyncClientResult(0, 0);
        }

        List<Company> companiesWithChatUrl = null;
        for (WhatsAppGroupInfo group : groups) {
            if (group == null || !hasText(group.groupId())) {
                continue;
            }

            int groupLinked = groupCompanyLinker.linkByInvite(group.groupId(), group.inviteLink());
            if (groupLinked == 0 && !hasText(group.inviteLink())) {
                if (companiesWithChatUrl == null) {
                    companiesWithChatUrl = groupCompanyLinker.companiesWithChatUrl();
                }
                groupLinked = groupCompanyLinker.linkByGroupName(group.groupId(), group.name(), companiesWithChatUrl);
            }
            linked += groupLinked;
        }

        if (linked > 0) {
            log.info("WhatsApp group sync linked {} group(s) for client {}", linked, clientId);
        }
        log.info("WhatsApp group sync client={} groups={} linked={}", clientId, groups.size(), linked);
        return new SyncClientResult(groups.size(), linked);
    }

    private boolean syncDue(WhatsAppGroupSyncSettingsResponse settings) {
        if (!StringUtils.hasText(settings.lastRunAt())) {
            return true;
        }

        try {
            Instant lastRunAt = Instant.parse(settings.lastRunAt());
            return Duration.between(lastRunAt, Instant.now()).toMinutes() >= settings.intervalMinutes();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private int normalizeStoredInterval(int value) {
        try {
            return normalizeInterval(value);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_INTERVAL_MINUTES;
        }
    }

    private int normalizeInterval(int value) {
        if (value < MIN_INTERVAL_MINUTES || value > MAX_INTERVAL_MINUTES) {
            throw new IllegalArgumentException(
                    "Интервал WhatsApp-синхронизации должен быть от "
                            + MIN_INTERVAL_MINUTES
                            + " до "
                            + MAX_INTERVAL_MINUTES
                            + " минут"
            );
        }
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record SyncClientResult(int groups, int linked) {
    }
}
