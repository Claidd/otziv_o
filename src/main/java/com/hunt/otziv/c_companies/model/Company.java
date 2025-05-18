package com.hunt.otziv.c_companies.model;

import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
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

    @Column(name = "company_url_chat", length = 500, nullable = false)
    private String urlChat;

    //    мейл пользователя
    @Column(name = "company_email", unique = true, updatable = true)
    @Email
    private String email;

    //     владелец компании
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_user")
    @ToString.Exclude
    private User user;

    //    оператор, который нашел компанию
    @Column(name = "company_operator")
    private String operator;

    //   менеджер, который работает с компанией
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_manager")
    private Manager manager;

    //   список работников, которые работают с компанией
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "workers_companies",
        joinColumns = @JoinColumn(name = "company_id"),
        inverseJoinColumns = @JoinColumn(name = "worker_id")
    )
    private Set<Worker> workers;

    //    статус компании
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_status")
    private CompanyStatus status;

    //    категория компании
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_category")
    @ToString.Exclude
    private Category categoryCompany;

    //    субкатегория компании
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_subcategory")
    @ToString.Exclude
    private SubCategory subCategory;

    //    филиал содержащий название и url
    @OneToMany(mappedBy = "company", fetch = FetchType.LAZY, orphanRemoval = true)
    @BatchSize(size = 10)
    private Set<Filial> filial;

    //    филиал содержащий название и url
    @OneToMany(mappedBy = "company", fetch = FetchType.LAZY, orphanRemoval = true)
    @BatchSize(size = 10)
    private Set<Order> orderList;

    //    счетчик не оплаченных отзывов
    @Column(name = "company_counter_no_pay",columnDefinition = "integer default 0")
    private int counterNoPay;

    //    счетчик оплаченых отзывов
    @Column(name = "company_counter_pay",columnDefinition = "integer default 0")
    private int counterPay;

    //    счетчик выручки
    @Column(name = "company_sum",columnDefinition = "integer default 0.00")
    private BigDecimal sumTotal;

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

    @Column(name = "company_group_id")
    private String groupId;


    // Геттеры и сеттеры

    @PrePersist
    protected void onCreate() {
        createDate = LocalDate.now();
        updateStatus = LocalDate.now();
        dateNewTry = LocalDate.now();
        System.out.println(LocalDate.now().plusDays(10));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Company company = (Company) o;
        return Objects.equals(id, company.id); // Сравниваем только идентификатор
    }

    @Override
    public int hashCode() {
        return Objects.hash(id); // Хэшируем только идентификатор
    }
}
