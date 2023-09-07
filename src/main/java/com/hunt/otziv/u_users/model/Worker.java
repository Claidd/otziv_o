package com.hunt.otziv.u_users.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hunt.otziv.b_bots.model.Bot;
//import com.hunt.otziv.p_products.model.Order;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "workers")
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "worker_id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @OneToMany(mappedBy = "worker", orphanRemoval = true)
    @ToString.Exclude
    private List<Bot> bots;

//    @OneToMany(mappedBy = "worker", cascade = CascadeType.ALL, orphanRemoval = true)
//    private List<Order> orders;
}