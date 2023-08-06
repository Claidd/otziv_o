package com.hunt.otziv.u_users.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.*;
import java.time.LocalDate;
import java.util.Collection;



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


    // Геттеры и сеттеры

    @PrePersist
    protected void onCreate() {
        createTime = LocalDate.now();
    }


//    @Enumerated(EnumType.STRING)
//    @Column(name = "role")
//    private Role roles;
//    @OneToOne(cascade = CascadeType.REMOVE)
//    private Bucket bucket;

}
