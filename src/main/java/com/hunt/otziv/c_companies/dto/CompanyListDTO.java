package com.hunt.otziv.c_companies.dto;

import com.hunt.otziv.u_users.dto.ManagerDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyListDTO {

    private Long id;

    //    название компании
    private String title;

    //    ссылка на чат
    private String urlChat;

    //    телефон нового компании
    private String telephone;

    //    список филиалов
    private int countFilials;

    //    список филиалов
    private String urlFilial;

    //    статус компании
    private String status;

    //   менеджер, который работает с компанией
    private String manager;

    //    комментарий
    private String commentsCompany;

    //    город
    private String city;

    //    дата и время нового отправления предложения
    private LocalDate dateNewTry;
}
