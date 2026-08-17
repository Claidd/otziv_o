package com.hunt.otziv.outreach_bridge;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "outreach-bridge")
public class OutreachBridgeProperties {
    static final String TOKEN_HEADER = "X-Outreach-Bridge-Token";

    private boolean enabled;
    private String sharedSecret = "";
    private String zone = "Asia/Irkutsk";
    private List<Long> adminChatIds = new ArrayList<>(List.of(794146111L, 828987226L));
    private Statuses statuses = new Statuses();

    void validate() {
        if (enabled && (sharedSecret == null || sharedSecret.trim().length() < 32)) {
            throw new IllegalStateException("OUTREACH_BRIDGE_SHARED_SECRET must contain at least 32 characters");
        }
    }

    @Getter
    @Setter
    public static class Statuses {
        private String scan = "Проверка";
        private String ready = "Новый";
        private String initialSent = "Отправленный";
        private String declined = "Отказ";
        private String noWhatsApp = "Нет ватсап";
        private String lastSeenStale = "Не в сети";
        private String lastSeenUnavailable = "Оффлайн";
        private String failed = "Ошибка";
    }
}
