package com.hunt.otziv.admin.dto.personal_stat;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserStatDTO {
    private Long id;
//    private Long userId;
    private String fio;
    private Long imageId;
    private BigDecimal coefficient;
    private BigDecimal percentNoPay;
    private BigDecimal avgPublish1Day;

    private int sum1Day;
    private int sum1Week;
    private int sum1Month;
    private int sum1Year;
    private int sumOrders1Month;
    private int sumOrders1Year;

    private int percent1Day;
    private int percent1Week;
    private int percent1Month;
    private int percent1Year;

    private BigDecimal reviewsGet1Day;
    private BigDecimal reviewsGetWeek;
    private BigDecimal reviewsGetMonth;
    private BigDecimal reviewsGetYear;

    private BigDecimal reviewsPublish1Day;
    private BigDecimal reviewsPublishWeek;
    private BigDecimal reviewsPublishMonth;
    private BigDecimal reviewsPublishYear;

    private BigDecimal reviewsPublished1Day;
    private BigDecimal reviewsPublishedWeek;
    private BigDecimal reviewsPublishedMonth;
    private BigDecimal reviewsPublishedYear;

    private BigDecimal reviewsPay1Day;
    private BigDecimal reviewsPayWeek;
    private BigDecimal reviewsPayMonth;
    private BigDecimal reviewsPayYear;

}
