package com.hunt.otziv.l_lead.dto.api;

import java.util.List;

public record LeadPageResponse(
        List<LeadResponse> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
