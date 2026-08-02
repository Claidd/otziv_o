package com.hunt.otziv.b_bots.dto;

/**
 * Minimal browser-launch metadata that is safe to expose to workers.
 *
 * <p>The administrative bot DTO intentionally is not reused here because it
 * contains the account password and worker administration data.</p>
 */
public record BrowserBotMetadataResponse(
        Long botId,
        String login,
        String fio
) {
}
