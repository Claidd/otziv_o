package com.hunt.otziv.a_login.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;
//implements GrantedAuthority

@Data
@Entity
@Table(name = "roles")
public class Role implements GrantedAuthority {

    private static final String SEQ_NAME = "role_seq";
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = SEQ_NAME)
    @SequenceGenerator(name = SEQ_NAME, sequenceName = SEQ_NAME, allocationSize = 1)
    @Column(name = "id")
    private Integer id;
    @Column(name = "name")
    private String name;


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