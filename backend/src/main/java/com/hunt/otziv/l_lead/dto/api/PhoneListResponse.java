package com.hunt.otziv.l_lead.dto.api;

import java.util.List;

public record PhoneListResponse(
        List<PhoneResponse> phones,
        List<PhoneOperatorOptionResponse> operators
) {
}
