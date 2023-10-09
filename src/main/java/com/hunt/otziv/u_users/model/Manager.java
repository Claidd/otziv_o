package com.hunt.otziv.u_users.model;

import com.hunt.otziv.c_companies.model.Company;
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
@Table(name = "managers")
public class Manager {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "manager_id")

    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @JoinColumn(name = "pay_text")
    private String payText;

    @OneToMany(mappedBy = "manager")
    private Set<Company> companies;

    @OneToMany(mappedBy = "manager")
    @ToString.Exclude
    private Set<Lead> leads;

    @Override
    public int hashCode() {
        return Objects.hash(id); // Используйте только идентификатор для хэширования
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Manager manager = (Manager) o;
        return Objects.equals(id, manager.id); // Сравнивайте только идентификатор
    }

    @Override
    public String toString() {
        return "Manager(id=" + id + ", user=" + user + ")";
    }
}
