package com.hunt.otziv.c_categories.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubCategoryDTO {
    private Long id;
    private String subCategoryTitle;
    private CategoryDTO category;

    public SubCategoryDTO(Long id, String subCategoryTitle) {
    }
}
