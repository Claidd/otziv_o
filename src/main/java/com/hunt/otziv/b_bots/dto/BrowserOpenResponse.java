package com.hunt.otziv.b_bots.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrowserOpenResponse {
    private Long botId;
    private String vncUrl;
    private String userAgent;
    private String platform;
    private String screenResolution;

    public BrowserOpenResponse(Long botId, String vncUrl) {
        this.botId = botId;
        this.vncUrl = vncUrl;
    }
}
