package com.hunt.otziv.u_users.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

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

//    @Column(name = "manager_id")
//    private Long managerId;

    @ManyToOne
    @JoinColumn(name = "user_id")
//    @Column(name = "user_id")
    private User user;

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

//    @Override
//    public String toString() {
//        return "Operator{" +
//                "id=" + id +
//                // ... другие поля ...
//                '}';
//    }
}
