package com.hunt.otziv.c_companies.dto;

import com.hunt.otziv.c_cities.model.City;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FilialDTO {

    private Long id;

    //    название филиала
    private String title;

    //    url
    private String url;

    //    id город
    private City city;
}
