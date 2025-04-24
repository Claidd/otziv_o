package com.hunt.otziv.l_lead.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TelephoneIDAndTimeDTO {

    Long telephoneID;
    LocalDateTime time;
}
