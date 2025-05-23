package com.hunt.otziv.c_companies.dto;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.UserDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyDTO {

    private Long id;

    //    название компании
    @NotEmpty(message = "Название не может быть пустым")
    private String title;

    //    ссылка на чат
    @NotEmpty(message = "Ссылка на чат не может быть пустой")
    private String urlChat;

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

    private Set<Integer> workers2;

    //    статус компании
    private CompanyStatusDTO status;

    //    категория компании
    private CategoryDTO categoryCompany;

    //    субкатегория компании
    private SubCategoryDTO subCategory;

    //    филиал содержащий название и url
    private FilialDTO filial;

    private Set<FilialDTO> filials;

    private Set<OrderDTO> orders;

    //    счетчик не оплаченных отзывов
    private int counterNoPay;

    //    счетчик оплаченых отзывов
    private int counterPay;

    //    счетчик выручки
    private BigDecimal sumTotal;

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

    private String groupId;

}
