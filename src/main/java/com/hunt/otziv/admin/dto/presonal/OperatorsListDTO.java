package com.hunt.otziv.admin.dto.presonal;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorsListDTO {
    private Long id;
    private Long userId;
    private String fio;
    private String login;
    private Long imageId;
    private int sum1Month;
    private int order1Month;
    private int review1Month;
    private int leadsNew;
    private int leadsInWork;
    private int percentInWork;
//    private BigDecimal coefficient;
//    private BigDecimal sum1Day;
//    private BigDecimal sum1Week;
//    private BigDecimal sum1Month;
}
