package com.hunt.otziv.c_companies.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "filial")
public class Filial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "filial_id")
    private Long id;

    //    название филиала
    @Column(name = "filial_title")
    private String title;

    //    url
    @Column(name = "filial_url")
    private String url;

    @ManyToOne
    @JoinColumn(name = "company_id")
    private Company company;
}
