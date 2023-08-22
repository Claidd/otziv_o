package com.hunt.otziv.c_companies.model;

import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Set;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "companies")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_id")
    private Long id;

    //    название компании
    @Column(name = "company_title")
    private String title;

    //    телефон нового компании
    @Column(name = "company_phone", length = 12, nullable = false, unique = true)
    private String telephone;

    //    город компании
    @Column(name = "company_city", length = 50, nullable = false)
    private String city;

//    //    город по которому шла рассылка
//    @Column(name = "company_url", length = 50, nullable = false)
//    private String url;

    //    мейл пользователя
    @Column(name = "company_email", unique = true, updatable = false)
    @Email
    private String email;


//    //    оператор, который нашел компанию
//    @Column(name = "company_operator")
//    private String operator;
//
//    //   менеджер, который работает с компанией
//    @Column(name = "company_manager")
//    private Manager manager;
//
//    //   список работников, которые работают с компанией
//    @Column(name = "company_worker")
//    private Set<Worker> worker;

    //    статус компании
    @ManyToOne
    @JoinColumn(name = "company_status")
    private CompanyStatus status;

    //    категория компании
    @ManyToOne
    @JoinColumn(name = "company_category")
    private Category categoryCompany;

    //    субкатегория компании
    @ManyToOne
    @JoinColumn(name = "company_subcategory")
    private SubCategory subCategory;

    //    филиал содержащий название и url
    @OneToMany(mappedBy = "company",cascade = CascadeType.ALL)
    @Column(name = "company_filial")
    private Set<Filial> filial;



    //    счетчик не оплаченных отзывов
    @Column(name = "company_counter_no_pay",columnDefinition = "integer default 0")
    private int counterNoPay;

    //    счетчик оплаченых отзывов
    @Column(name = "company_counter_pay",columnDefinition = "integer default 0")
    private int counterPay;

    //    счетчик выручки
    @Column(name = "company_sum",columnDefinition = "integer default 0")
    private int sumTotal;

    //    город по которому шла рассылка
    @Column(name = "company_comments", length = 2000)
    private String commentsCompany;

    //    время создания пользователя
    @Temporal(TemporalType.DATE)
    @Column(name = "create_date", nullable = false)
    private LocalDate createDate;

    //    дата и время обновления статуса
    @Temporal(TemporalType.DATE)
    @Column(name = "update_status")
    private LocalDate updateStatus;

    //    дата и время нового отправления предложения
    @Temporal(TemporalType.DATE)
    @Column(name = "date_new_try")
    private LocalDate dateNewTry;

    //    активнность компании
    @Column(name = "company_active")
    private boolean active;

    // Геттеры и сеттеры

    @PrePersist
    protected void onCreate() {
        createDate = LocalDate.now();
        updateStatus = LocalDate.now();
        dateNewTry = LocalDate.now();
        System.out.println(LocalDate.now().plusDays(10));
    }
}
