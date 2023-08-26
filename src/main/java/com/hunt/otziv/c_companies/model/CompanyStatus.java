package com.hunt.otziv.c_companies.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "company_status")
public class CompanyStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_status_id")
    private Long id;

    //    статус
    @Column(name = "status_title")
    private String title;

    @OneToMany(mappedBy = "status",cascade = CascadeType.ALL)
    @ToString.Exclude
    List<Company> companyStatus;
}
