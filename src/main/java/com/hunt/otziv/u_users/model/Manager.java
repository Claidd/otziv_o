package com.hunt.otziv.u_users.model;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.l_lead.model.Lead;
import jakarta.persistence.*;
import lombok.*;

import java.util.Set;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "managers")
public class Manager {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "manager_id")

    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @OneToMany(mappedBy = "manager")
    private Set<Company> companies;

    @OneToMany(mappedBy = "manager")
    @ToString.Exclude
    private Set<Lead> leads;
}
