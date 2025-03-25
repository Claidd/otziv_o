package com.hunt.otziv.admin.dto.presonal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonalListDto {
//    private Long id;
//    private Long userId;
//    private String login;
//    private String fio;
//    private Long imageId;
//    private int sum1Month;
//    private int order1Month;
//    private int review1Month;
//    private int payment1Month;
//    private int leadsInWorkInMonth;
//    private int newOrder;
//    private int inCorrect;
//    private int intVigul;
//    private int publish;
    private List<ManagersListDTO> managers;
    private List<WorkersListDTO> workers;
    private List<MarketologsListDTO> marketologs;
    private List<OperatorsListDTO> operators;
}
