package com.hunt.otziv.r_review.dto;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewDTO {

    private Long id;

    private String text;

    private String answer;

    private CategoryDTO category;

    private SubCategoryDTO subCategory;

    private BotDTO bot;

    private OrderDetailsDTO orderDetails;

    private FilialDTO filial;
}
