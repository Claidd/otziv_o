package com.hunt.otziv.manager.dto.api;

import com.hunt.otziv.c_companies.dto.CompanyListDTO;
import com.hunt.otziv.p_products.dto.OrderDTOList;

import java.util.List;

public record ManagerBoardResponse(
        String section,
        String status,
        PageResponse<CompanyListDTO> companies,
        PageResponse<OrderDTOList> orders,
        List<String> companyStatuses,
        List<String> orderStatuses,
        List<ManagerMetricResponse> metrics,
        List<String> promoTexts
) {
}
