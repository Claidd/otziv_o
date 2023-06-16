package com.hunt.otziv.a_login.model;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.*;

import java.util.Collection;


@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
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

    @Column(unique = true, updatable = false)
    @Email
    private String email;
    @Column(name = "phone_number")
    private String phoneNumber;

    @ManyToMany()
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

//    @Enumerated(EnumType.STRING)
//    @Column(name = "role")
//    private Role roles;
//    @OneToOne(cascade = CascadeType.REMOVE)
//    private Bucket bucket;

}
