package com.hunt.otziv.u_users.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hunt.otziv.b_bots.model.Bot;
//import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.model.Review;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @OneToMany(mappedBy = "worker", orphanRemoval = true, fetch = FetchType.LAZY)
    @ToString.Exclude
    private List<Bot> bots;

    // Добавьте это поле
    @Column(name = "last_nagul_time")
    private LocalDateTime lastNagulTime;

}