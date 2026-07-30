package com.hunt.otziv.c_companies.dto;

import com.hunt.otziv.c_cities.model.City;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    private boolean archived;

    private LocalDateTime archivedAt;
}
