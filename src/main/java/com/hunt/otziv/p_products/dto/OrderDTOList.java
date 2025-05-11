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
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDTOList {

    private Long id;

    private Long companyId;

    private UUID orderDetailsId;

    private String companyTitle;

    private String filialTitle;

    private String filialUrl;

    private String status;

    private BigDecimal sum;

    private String companyUrlChat;

    private String companyTelephone;

    private String orderComments;

    private String companyComments;

    private String managerPayText;

    private Integer amount;

    private Integer counter;

    private String workerUserFio;

    private String categoryTitle;

    private String subCategoryTitle;

    private LocalDate created;

    private LocalDate changed;

    private LocalDate payDay;

    private long dayToChangeStatusAgo;




//    private Integer amount;
//
//    private Integer counter;
//
//    private BigDecimal sum;
//
//    private List<OrderDetailsDTO> details;
//
//    private UUID orderDetailsId;
//
//    private OrderStatusDTO status;
//
//    private int dayToChangeStatusAgo;
//
//    //    каждый бот имеет Работника, который его добавлял
//    private WorkerDTO worker;
//
//    private Set<WorkerDTO> workers;
//
//    private ManagerDTO manager;
//
//    //    каждый бот имеет Работника, который его добавлял
//    private CompanyDTO company;
//
//    private boolean complete;
//
//    //    филиал содержащий название и url
//    private FilialDTO filial;
}
