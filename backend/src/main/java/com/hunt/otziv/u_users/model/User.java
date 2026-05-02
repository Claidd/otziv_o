package com.hunt.otziv.u_users.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hunt.otziv.c_companies.model.Company;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "password")
    private String password;

    @Column(name = "fio")
    private String fio;

    @Column(unique = true)
    @Email
    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "coefficient")
    private BigDecimal coefficient;

    // keycloak_id хранит sub из JWT Keycloak;
    @Column(name = "keycloak_id", unique = true)
    private String keycloakId;

    // auth_provider = LOCAL | KEYCLOAK поможет жить в переходный период;
    @Builder.Default
    @Column(name = "auth_provider", nullable = false)
    private String authProvider = "LOCAL";

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "telegram_chat_id", unique = true)
    private Long telegramChatId;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "users_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @ToString.Exclude
    private Collection<Role> roles;

    @Column(name = "active")
    private boolean active;

    @Column(name = "activate_code")
    private String activateCode;

    @Column(name = "create_time")
    private LocalDate createTime;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image")
    private Image image;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "operators_users",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "operator_id")
    )
    @ToString.Exclude
    private Set<Operator> operators;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "managers_users",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "manager_id")
    )
    @ToString.Exclude
    private Set<Manager> managers;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "workers_users",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "worker_id")
    )
    @ToString.Exclude
    private Set<Worker> workers;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "marketologs_users",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "marketolog_id")
    )
    @ToString.Exclude
    private Set<Marketolog> marketologs;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private Set<Company> companies;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDate.now();
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        User user = (User) obj;
        return Objects.equals(id, user.id);
    }

    @Override
    public String toString() {
        return "User(id=" + id + ", username=" + username + ", email=" + email + ", fio=" + fio + ", telegramChatId=" + telegramChatId + " )";
    }
}
