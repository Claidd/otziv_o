package com.hunt.otziv.admin.dto.presonal;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManagersListDTO {
    private Long id;
    private Long userId;
    private String login;
    private String fio;
    private Long imageId;
    private int sum1Month;
    private int order1Month;
    private int review1Month;
    private int payment1Month;

//    private BigDecimal coefficient;
//    private BigDecimal sum1Day;
//    private BigDecimal sum1Week;
//    private BigDecimal sum1Month;
}
