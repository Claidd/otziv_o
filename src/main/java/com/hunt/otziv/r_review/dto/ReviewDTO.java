package com.hunt.otziv.r_review.dto;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;

import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

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

    private String botName;

    private OrderDetailsDTO orderDetails;

    private UUID orderDetailsId;

    private FilialDTO filial;

    private WorkerDTO worker;

    private LocalDate created;

    private LocalDate changed;

    private LocalDate publishedDate;

    private boolean publish;

    private String comment;

    private Product product;

    private BigDecimal price;

    private String url;

    private String urlPhoto;

    private String botLogin; // ← можно добавить
    private Long botId; // ← и ID бота для идентификации

}
