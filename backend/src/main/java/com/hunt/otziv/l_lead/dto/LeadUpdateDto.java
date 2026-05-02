package com.hunt.otziv.l_lead.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadUpdateDto {
    private Long leadId;
    private String telephoneLead;
    private String cityLead;
    private String commentsLead;
    private String lidStatus;
    private LocalDate createDate;
    private LocalDateTime updateStatus;
    private LocalDate dateNewTry;
    private boolean offer;
    private Long managerId;
    private Long operatorId;
    private Long marketologId;
    private Long telephoneId;
    private LocalDateTime lastSeen;
}
