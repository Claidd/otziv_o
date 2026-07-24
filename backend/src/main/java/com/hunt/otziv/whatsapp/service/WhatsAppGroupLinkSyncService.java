package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.dto.SharedChatLinkSyncResponse;
import com.hunt.otziv.c_companies.services.SharedChatLinkSyncService;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupSyncSettingsRequest;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupSyncSettingsResponse;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class WhatsAppGroupLinkSyncService {

    private final WhatsAppProperties properties;
    private final WhatsAppService whatsAppService;
    private final WhatsAppGroupCompanyLinker groupCompanyLinker;
    private final SharedChatLinkSyncService sharedChatLinkSyncService;
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

    @Transactional
    public WhatsAppGroupRepairResult repairCompanyLink(Company company) {
        if (company == null || company.getId() == null) {
            return WhatsAppGroupRepairResult.failed("У компании нет данных для проверки WhatsApp-группы");
        }
        if (WhatsAppGroupCompanyLinker.whatsAppInviteCode(company.getUrlChat()).isEmpty()) {
            return WhatsAppGroupRepairResult.failed("В карточке компании нет корректной WhatsApp invite-ссылки");
        }

        List<WhatsAppProperties.ClientConfig> configuredClients = properties.getClients() != null
                ? properties.getClients()
                : List.of();
        int clients = 0;
        int groups = 0;
        int groupsWithInvite = 0;
        int directInviteMatches = 0;
        List<String> visibleGroupNames = new java.util.ArrayList<>();
        List<Company> singleCompany = List.of(company);

        // The exact invite lookup does not enumerate all chats and therefore keeps the repair
        // button useful even when WhatsApp Web temporarily breaks client.getChats().
        for (WhatsAppProperties.ClientConfig client : configuredClients) {
            if (client == null || !hasText(client.getId()) || !hasText(client.getUrl())) {
                continue;
            }
            clients++;
            Optional<WhatsAppGroupInfo> directGroup = whatsAppService.resolveGroupByInvite(
                    client.getId(),
                    company.getUrlChat()
            );
            if (directGroup.isEmpty() || !hasText(directGroup.get().groupId())) {
                continue;
            }
            directInviteMatches++;
            WhatsAppGroupInfo group = directGroup.get();
            int linked = groupCompanyLinker.linkByInvite(
                    group.groupId(),
                    company.getUrlChat(),
                    singleCompany
            );
            if (linked == 0) {
                linked = groupCompanyLinker.linkByGroupName(group.groupId(), group.name(), singleCompany);
            }
            if (linked > 0 || java.util.Objects.equals(company.getGroupId(), group.groupId())) {
                return WhatsAppGroupRepairResult.linked(
                        "WhatsApp-группа найдена напрямую по invite-ссылке у клиента " + client.getId()
                );
            }
        }

        for (WhatsAppProperties.ClientConfig client : configuredClients) {
            if (client == null || !hasText(client.getId()) || !hasText(client.getUrl())) {
                continue;
            }
            List<WhatsAppGroupInfo> clientGroups = whatsAppService.listGroups(client.getId(), true);
            if (clientGroups == null) {
                clientGroups = List.of();
            }
            groups += clientGroups.size();
            for (WhatsAppGroupInfo group : clientGroups) {
                if (group == null || !hasText(group.groupId())) {
                    continue;
                }
                if (hasText(group.name()) && visibleGroupNames.size() < 5) {
                    visibleGroupNames.add(group.name());
                }
                if (hasText(group.inviteLink())) {
                    groupsWithInvite++;
                }

                int linked = groupCompanyLinker.linkByInvite(group.groupId(), group.inviteLink(), singleCompany);
                if (linked == 0) {
                    linked = groupCompanyLinker.linkByGroupName(group.groupId(), group.name(), singleCompany);
                }
                if (linked > 0) {
                    return WhatsAppGroupRepairResult.linked(
                            "WhatsApp-группа найдена у клиента " + client.getId()
                    );
                }
            }
        }

        if (clients == 0) {
            return WhatsAppGroupRepairResult.failed(
                    "WhatsApp-починка не настроена: нет активных WhatsApp-клиентов в конфигурации"
            );
        }
        if (groups == 0) {
            return WhatsAppGroupRepairResult.failed(
                    directInviteMatches > 0
                            ? "WhatsApp нашел invite-ссылку, но не смог безопасно сохранить groupId. Проверьте конфликт существующей привязки."
                            : "WhatsApp подключен, но не смог разрешить invite-ссылку и прочитать список групп. "
                                    + "Название компании и состав администраторов менять не требуется."
            );
        }

        String groupsHint = visibleGroupNames.isEmpty()
                ? ""
                : " Видимые группы: " + String.join(", ", visibleGroupNames) + ".";
        if (groupsWithInvite == 0) {
            return WhatsAppGroupRepairResult.failed(
                    "WhatsApp-шлюз видит " + groups + " групп, но не вернул invite-ссылки. "
                            + "Починка попробовала сверить название компании с названиями групп, но совпадение не найдено."
                            + groupsHint
            );
        }

        return WhatsAppGroupRepairResult.failed(
                "WhatsApp-шлюз видит " + groups + " групп, из них " + groupsWithInvite
                        + " с invite-ссылкой, но ссылка и название этой компании не совпали ни с одной группой."
                        + groupsHint
        );
    }

    @Async
    public void runNowInBackground(String source) {
        try {
            runSync(hasText(source) ? source : "background");
        } catch (RuntimeException e) {
            log.warn("WhatsApp group sync failed in background source={}: {}", source, e.getMessage(), e);
        }
    }

    public int syncSharedChatIdsNow(String source) {
        SharedChatLinkSyncResponse sharedChatSync = sharedChatLinkSyncService.syncSharedChatIds();
        if (sharedChatSync.updatedCompanies() > 0) {
            log.info(
                    "WhatsApp group sync copied shared chat ids source={} updatedCompanies={} whatsappLinked={} telegramLinked={} maxLinked={} conflictGroups={}",
                    source,
                    sharedChatSync.updatedCompanies(),
                    sharedChatSync.whatsappLinked(),
                    sharedChatSync.telegramLinked(),
                    sharedChatSync.maxLinked(),
                    sharedChatSync.conflictGroups()
            );
        }
        return sharedChatSync.whatsappLinked();
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

        linked += syncSharedChatIdsNow(source);
        linked += repairConflictingInviteGroupIds(configuredClients, source);

        appSettingService.setString(AppSettingService.WHATSAPP_GROUP_SYNC_LAST_RUN_AT, Instant.now().toString());
        appSettingService.setInt(AppSettingService.WHATSAPP_GROUP_SYNC_LAST_LINKED_COUNT, linked);
        log.info("WhatsApp group sync finished source={} clients={} groups={} linked={} durationMs={}",
                source, clients, groups, linked, System.currentTimeMillis() - startedAt);
        return linked;
    }

    /**
     * Repairs the dangerous case where one stored WhatsApp groupId belongs to companies
     * with different invite links. A normal group list sync cannot help while getChats()
     * is unavailable, so only the small conflicting subset is resolved directly by invite.
     */
    int repairConflictingInviteGroupIds(
            List<WhatsAppProperties.ClientConfig> configuredClients,
            String source
    ) {
        List<Company> companies = groupCompanyLinker.companiesWithChatUrl();
        if (companies == null || companies.isEmpty() || configuredClients == null || configuredClients.isEmpty()) {
            return 0;
        }

        Map<String, Map<String, List<Company>>> byGroupAndInvite = new LinkedHashMap<>();
        for (Company company : companies) {
            if (company == null || !hasText(company.getGroupId())) {
                continue;
            }
            Optional<String> inviteCode = WhatsAppGroupCompanyLinker.whatsAppInviteCode(company.getUrlChat());
            if (inviteCode.isEmpty()) {
                continue;
            }
            byGroupAndInvite
                    .computeIfAbsent(company.getGroupId().trim(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(inviteCode.get(), ignored -> new ArrayList<>())
                    .add(company);
        }

        int repaired = 0;
        for (Map.Entry<String, Map<String, List<Company>>> groupEntry : byGroupAndInvite.entrySet()) {
            Map<String, List<Company>> companiesByInvite = groupEntry.getValue();
            if (companiesByInvite.size() < 2) {
                continue;
            }

            log.warn(
                    "WhatsApp groupId conflict detected source={} groupId={} distinctInviteLinks={} companies={}",
                    source,
                    groupEntry.getKey(),
                    companiesByInvite.size(),
                    companiesByInvite.values().stream().mapToInt(List::size).sum()
            );
            for (List<Company> inviteCompanies : companiesByInvite.values()) {
                repaired += repairInviteCompaniesDirectly(inviteCompanies, configuredClients, source);
            }
        }
        return repaired;
    }

    private int repairInviteCompaniesDirectly(
            List<Company> companies,
            List<WhatsAppProperties.ClientConfig> configuredClients,
            String source
    ) {
        if (companies == null || companies.isEmpty()) {
            return 0;
        }
        Company representative = companies.getFirst();
        for (WhatsAppProperties.ClientConfig client : configuredClients) {
            if (client == null || !hasText(client.getId()) || !hasText(client.getUrl())) {
                continue;
            }
            Optional<WhatsAppGroupInfo> resolved = whatsAppService.resolveGroupByInvite(
                    client.getId(),
                    representative.getUrlChat()
            );
            if (resolved.isEmpty() || !hasText(resolved.get().groupId())) {
                continue;
            }

            int updated = groupCompanyLinker.linkByInvite(
                    resolved.get().groupId(),
                    representative.getUrlChat(),
                    companies
            );
            if (updated > 0) {
                log.info(
                        "WhatsApp groupId conflict repaired source={} client={} inviteCompanies={} newGroupId={}",
                        source,
                        client.getId(),
                        updated,
                        resolved.get().groupId()
                );
            }
            return updated;
        }
        return 0;
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
            if (groupLinked == 0) {
                if (companiesWithChatUrl == null) {
                    companiesWithChatUrl = groupCompanyLinker.companiesWithChatUrl();
                }
                groupLinked = groupCompanyLinker.linkByInvite(group.groupId(), group.inviteLink(), companiesWithChatUrl);
                if (groupLinked == 0) {
                    groupLinked = groupCompanyLinker.linkByGroupName(group.groupId(), group.name(), companiesWithChatUrl);
                }
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

    public record WhatsAppGroupRepairResult(boolean linked, String message) {
        private static WhatsAppGroupRepairResult linked(String message) {
            return new WhatsAppGroupRepairResult(true, message);
        }

        private static WhatsAppGroupRepairResult failed(String message) {
            return new WhatsAppGroupRepairResult(false, message);
        }
    }
}
