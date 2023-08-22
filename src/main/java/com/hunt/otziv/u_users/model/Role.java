package com.hunt.otziv.u_users.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
//implements GrantedAuthority

@Data
@Entity
@Table(name = "roles")
public class Role implements GrantedAuthority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;
    @Column(name = "name")
    private String name;

    @ManyToMany(mappedBy = "roles")
    @ToString.Exclude
    private Collection<User> users;

    @Override
    public String getAuthority() {
        return name;
    }
}
// ADMIN,
//         CALLING,
//         MANAGER,
//         WORKER,
//         CLIENT