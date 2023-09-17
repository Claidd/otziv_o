package com.hunt.otziv.p_products.dto;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDTO {

    private Long id;

    private LocalDate created;

    private LocalDate changed;

    private Integer amount;
    private Integer counter;

    private BigDecimal sum;

    private List<OrderDetailsDTO> details;

    private OrderStatusDTO status;

    //    каждый бот имеет Работника, который его добавлял
    private WorkerDTO worker;
    private Set<WorkerDTO> workers;
    private ManagerDTO manager;

    //    каждый бот имеет Работника, который его добавлял
    private CompanyDTO company;

    private boolean complete;

    //    филиал содержащий название и url
    private FilialDTO filial;
}
