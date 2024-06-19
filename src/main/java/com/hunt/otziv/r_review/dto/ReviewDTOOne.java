package com.hunt.otziv.r_review.dto;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewDTOOne {

    private Long id;

    private Long companyId;

    private UUID orderDetailsId;

    private Long orderId;

    private String text;

    private String answer;

    private String category;

    private String subCategory;

    private Long botId;

    private String botFio;

    private String botLogin;

    private String botPassword;

    private int botCounter;

    private String companyTitle;

    private String commentCompany;

    private String filialTitle;

    private String filialUrl;

    private String productTitle;

    private String workerFio;

    private LocalDate created;

    private LocalDate changed;

    private LocalDate publishedDate;

    private boolean publish;

    private String comment;

    //    private OrderDetailsDTO orderDetails;

}
