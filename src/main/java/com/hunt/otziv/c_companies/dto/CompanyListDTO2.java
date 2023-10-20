package com.hunt.otziv.c_companies.dto;

import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.u_users.model.Manager;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyListDTO2 {

    private Long id;
    private String title;
    private String urlChat;
    private String telephone;
    private Filial filial;
    private CompanyStatus status;
    private Manager manager;
    private String commentsCompany;
    private LocalDate dateNewTry;
}
