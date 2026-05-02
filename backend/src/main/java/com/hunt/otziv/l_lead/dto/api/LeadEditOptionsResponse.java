package com.hunt.otziv.l_lead.dto.api;

import java.util.List;

public record LeadEditOptionsResponse(
        List<LeadPersonOptionResponse> operators,
        List<LeadPersonOptionResponse> managers,
        List<LeadPersonOptionResponse> marketologs,
        List<String> statuses
) {
}
