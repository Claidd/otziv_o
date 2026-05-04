package com.hunt.otziv.manager.dto.api;

import java.math.BigDecimal;
import java.util.List;

public record OrderEditResponse(
        Long id,
        Long companyId,
        String companyTitle,
        String status,
        BigDecimal sum,
        Integer amount,
        Integer counter,
        String created,
        String changed,
        String payDay,
        String orderComments,
        String commentsCompany,
        boolean complete,
        OptionResponse filial,
        OptionResponse manager,
        OptionResponse worker,
        List<OptionResponse> filials,
        List<OptionResponse> managers,
        List<OptionResponse> workers,
        boolean canComplete,
        boolean canDelete
) {
}
