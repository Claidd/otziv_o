package com.hunt.otziv.manager.dto.api;

public record OrderNotesResponse(
        String orderComments,
        String companyComments
) {
}
