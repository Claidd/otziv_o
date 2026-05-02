package com.hunt.otziv.l_lead.dto.api;

import java.util.List;

public record LeadBoardResponse(
        LeadPageResponse toWork,
        LeadPageResponse newLeads,
        LeadPageResponse send,
        LeadPageResponse archive,
        LeadPageResponse inWork,
        LeadPageResponse all,
        List<String> statuses,
        List<String> promoTexts
) {
}
