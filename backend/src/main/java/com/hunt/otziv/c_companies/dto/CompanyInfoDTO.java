package com.hunt.otziv.c_companies.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyInfoDTO {
    private Long id;
    private String region;
    private String address;
    private String industries;
    private String companyType;
    private String source;
    private Long sourceLeadId;
}
