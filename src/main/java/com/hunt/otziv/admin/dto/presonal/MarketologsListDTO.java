package com.hunt.otziv.admin.dto.presonal;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketologsListDTO {
    private Long id;
    private Long userId;
    private String login;
    private String fio;
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
