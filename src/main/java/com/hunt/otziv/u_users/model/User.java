package com.hunt.otziv.u_users.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_companies.model.Company;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;


@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer"})
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    //    имя пользователя
    @Column(name = "username", nullable = false)
    private String username;

    //    пароль пользователя
    @Column(name = "password")
    private String password;

    //    фамилия, имя и отчество пользователя
    @Column(name = "fio")
    private String fio;

    //    мейл пользователя
    @Column(unique = true, updatable = false)
    @Email
    private String email;

    //    номер телефона пользователя
    @Column(name = "phone_number")
    private String phoneNumber;

    //    Коеффициент
    @Column(name = "coefficient")
    private BigDecimal coefficient;

    //    роль пользователя в системе. связь многие ко многим.
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(
            name = "users_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @ToString.Exclude
    private Collection<Role> roles;

    //    статус пользователя: активен или находится в бане
    @Column(name = "active")
    private boolean active;

    //    активационный код, который высылается на почту для подтверждения почты
    @Column(name = "activate_code")
    private String activateCode;

    //    время создания пользователя
    @Column(name = "create_time")
    private LocalDate createTime;

    //    картинка
    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "image")
    private Image image;

    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(
            name = "operators_users",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "operator_id")
    )
    @ToString.Exclude
    private Set<Operator> operators;

    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(
            name = "managers_users",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "manager_id")
    )
    @ToString.Exclude
    private Set<Manager> managers;

    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(
            name = "workers_users",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "worker_id")
    )
    @ToString.Exclude
    private Set<Worker> workers;

    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(
            name = "marketologs_users",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "marketolog_id")
    )
    @ToString.Exclude
    private Set<Marketolog> marketologs;

    @OneToMany(mappedBy = "user")
    private Set<Company> companies;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDate.now();
    }

    @Override
    public int hashCode() {
        return Objects.hash(id); // или другие уникальные поля
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        User user = (User) obj;
        return Objects.equals(id, user.id); // или другие уникальные поля
    }
    @Override
    public String toString() {
        return "User(id=" + id + ", username=" + username + ", email=" + email + ", fio=" + fio + ")";
    }

}
