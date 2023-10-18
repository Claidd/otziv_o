package com.hunt.otziv.u_users.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hunt.otziv.l_lead.model.Lead;
import jakarta.persistence.*;
import lombok.*;

import java.util.Objects;
import java.util.Set;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "operators")
public class Operator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "operator_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @OneToMany(mappedBy = "operator",fetch = FetchType.LAZY)
    @ToString.Exclude
    private Set<Lead> leads;

    @Override
    public int hashCode() {
        return Objects.hash(id); // или другие уникальные поля
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Operator operator = (Operator) obj;
        return Objects.equals(id, operator.id); // или другие уникальные поля
    }

}
