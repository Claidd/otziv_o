package com.hunt.otziv.c_companies.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyContactDTO {
    private Long id;
    private String type;
    private String value;
    private String normalizedValue;
    private boolean primaryContact;
    private String source;
    private Long sourceLeadId;
}
