package com.hunt.otziv.manager.dto.api;

public record OrderUpdateRequest(
        Long filialId,
        Long workerId,
        Long managerId,
        Integer counter,
        String orderComments,
        String commentsCompany,
        Boolean complete
) {
}
