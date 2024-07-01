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
public class StatDTO {

    String zpPayMap;
    String zpPayMapMonth;
    String orderPayMap;
    String orderPayMapMonth;


//    Суммы Доходов и проценты за текущий месяц

    private int sum1DayPay;
    private int sum1WeekPay;
    private int sum1MonthPay;
    private int sum1YearPay;
    private int sumOrders1MonthPay;
    private int sumOrders2MonthPay;
    private int newLeads;
    private int leadsInWork;

    private int percent1DayPay;
    private int percent1WeekPay;
    private int percent1MonthPay;
    private int percent1YearPay;
    private int percent1MonthOrdersPay;
    private int percent2MonthOrdersPay;
    private int percent1NewLeadsPay;
    private int percent2InWorkLeadsPay;

    //    Суммы ЗП и проценты за прошлый месяц
    private int sum1Day;
    private int sum1Week;
    private int sum1Month;
    private int sum1Year;
    private int sumOrders1Month;
    private int sumOrders2Month;

    private int percent1Day;
    private int percent1Week;
    private int percent1Month;
    private int percent1Year;
    private int percent1MonthOrders;
    private int percent2MonthOrders;



//    private BigDecimal reviewsGet1Day;
//    private BigDecimal reviewsGetWeek;
//    private BigDecimal reviewsGetMonth;
//    private BigDecimal reviewsGetYear;
//
//    private BigDecimal reviewsPublish1Day;
//    private BigDecimal reviewsPublishWeek;
//    private BigDecimal reviewsPublishMonth;
//    private BigDecimal reviewsPublishYear;
//
//    private BigDecimal reviewsPublished1Day;
//    private BigDecimal reviewsPublishedWeek;
//    private BigDecimal reviewsPublishedMonth;
//    private BigDecimal reviewsPublishedYear;
//
//    private BigDecimal reviewsPay1Day;
//    private BigDecimal reviewsPayWeek;
//    private BigDecimal reviewsPayMonth;
//    private BigDecimal reviewsPayYear;

}
