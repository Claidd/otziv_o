package com.hunt.otziv.client_campaigns;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class CampaignModels {
    private CampaignModels() {}

    public record Settings(@NotBlank @Size(max=120) String title,
            @NotBlank @Size(max=1000) String message,
            @Min(1) @Max(10000) int dailyLimit, @Min(1) @Max(1440) int intervalMinutes,
            @NotNull @Pattern(regexp="[0-2][0-9]:[0-5][0-9]") String windowStart,
            @NotNull @Pattern(regexp="[0-2][0-9]:[0-5][0-9]") String windowEnd,
            boolean includeActive, boolean includeStopped, boolean includeBanned,
            @NotNull @Pattern(regexp="ATTACHMENT|LINK") String fileMode) {}

    public record Campaign(String id, Settings settings, String state, LocalDateTime createdAt,
            LocalDateTime startedAt, LocalDateTime nextAt, LocalDate budgetDay, int budgetUsed,
            String fileName, String fileType, String fileToken) {}
    public record Counts(long total, long pending, long sending, long sent, long failed, long unknown, long skipped) {}
    public record Summary(Campaign campaign, Counts counts, int usedToday) {}
    public record Board(boolean liveEnabled, List<Summary> campaigns) {}
    public record AudienceCount(String audience, long total, long reachable) {}
    public record Recipient(long id, String campaignId, long companyId, String companyTitle, String audience,
            int priority, String destinationKey, String chatUrl, String clientId, String groupId,
            Long telegramChatId, Long maxChatId, String state, String operationId, String errorMessage,
            LocalDateTime finishedAt) {
        public ClientMessageDelivery.Target target() {
            return new ClientMessageDelivery.Target(companyId, companyTitle, chatUrl, telegramChatId, maxChatId);
        }
    }
    public record Claim(Campaign campaign, Recipient recipient) {}
    public record Attachment(String name, String contentType, byte[] bytes) {}
}
