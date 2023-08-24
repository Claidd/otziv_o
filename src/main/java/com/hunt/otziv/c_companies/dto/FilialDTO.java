package com.hunt.otziv.c_companies.dto;

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
    @Column(name = "filial_title")
    private String title;

    //    url
    @Column(name = "filial_url")
    private String url;
}
