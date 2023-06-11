package com.hunt.otziv.a_login.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
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

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Role roles;
//    @OneToOne(cascade = CascadeType.REMOVE)
//    private Bucket bucket;
    @Column(name = "active")
    private boolean active;
    @Column(name = "activate_code")
    private String activateCode;
}
