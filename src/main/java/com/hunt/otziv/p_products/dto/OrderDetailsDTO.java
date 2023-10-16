package com.hunt.otziv.p_products.dto;


import com.hunt.otziv.r_review.dto.ReviewDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDetailsDTO {
    private UUID id;

    private OrderDTO order;

    private List<ReviewDTO> reviews;

    private LocalDate publishedDate;

    private ProductDTO product;

    private int amount;

    private BigDecimal price;

    private String comment;

}
