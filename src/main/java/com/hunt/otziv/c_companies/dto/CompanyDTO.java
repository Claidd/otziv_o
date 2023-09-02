package com.hunt.otziv.c_companies.dto;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.UserDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.*;

import java.time.LocalDate;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyDTO {

    private Long id;

    //    название компании
    private String title;

    //    телефон нового компании
    private String telephone;

    //    город компании
    private String city;

    //    мейл пользователя
    @Email (message = "Некорректный email")
    private String email;

    //     владелец компании
    private UserDTO user;

    //    оператор, который нашел компанию
    private String operator;

    //   менеджер, который работает с компанией

    private ManagerDTO manager;

    //   список работников, которые работают с компанией
    private WorkerDTO worker;

    //   список работников, которые работают с компанией
    private Set<WorkerDTO> workers;

    //    статус компании
    private CompanyStatusDTO status;

    //    категория компании
    private CategoryDTO categoryCompany;

    //    субкатегория компании
    private SubCategoryDTO subCategory;

    //    филиал содержащий название и url
    private FilialDTO filial;

    private Set<FilialDTO> filials;



    //    счетчик не оплаченных отзывов
    private int counterNoPay;

    //    счетчик оплаченых отзывов
    private int counterPay;

    //    счетчик выручки
    private int sumTotal;

    //    город по которому шла рассылка
    private String commentsCompany;

    //    время создания пользователя
    private LocalDate createDate;

    //    дата и время обновления статуса
    private LocalDate updateStatus;

    //    дата и время нового отправления предложения
    private LocalDate dateNewTry;

    //    активнность компании
    private boolean active;

}
