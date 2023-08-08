package com.hunt.otziv.c_categories.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryDTO {
    private Long id;
    private String categoryTitle;

    private List<SubCategoryDTO> subCategories;

    // конструкторы, геттеры и сеттеры для других полей

    public List<SubCategoryDTO> getSubCategories() {
        if (subCategories == null) {
            subCategories = new ArrayList<>();
        }
        return subCategories;
    }
}
