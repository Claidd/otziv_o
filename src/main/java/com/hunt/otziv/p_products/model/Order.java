package com.hunt.otziv.p_products.model;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long id;
    @CreationTimestamp
    @Column(name = "order_created")
    private LocalDate created;
    @UpdateTimestamp
    @Column(name = "order_changed")
    private LocalDate changed;
    @Column(name = "order_sum")
    private BigDecimal sum;
    @OneToMany(mappedBy = "order",cascade = CascadeType.ALL)
    private List<OrderDetails> details;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_status")
    private OrderStatus status;

    //    каждый бот имеет Работника, который его добавлял
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_worker")
    private Worker worker;

    //    каждый бот имеет Работника, который его добавлял
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_company")
    private Company company;

    @Column(name = "order_complete")
    private boolean complete;
}
