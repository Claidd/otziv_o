package com.hunt.otziv.p_products.model;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.u_users.model.Manager;
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
    @Column(name = "order_pay_day")
    private LocalDate payDay;
    @Column(name = "order_amount")
    private int amount;
    @Column(name = "order_sum")
    private BigDecimal sum;
    @Column(name = "order_counter")
    private int counter;

    @Column(name = "order_zametka")
    private String zametka;

    @OneToMany(mappedBy = "order",fetch = FetchType.LAZY)
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
    @JoinColumn(name = "order_manager")
    private Manager manager;

    //    каждый бот имеет Работника, который его добавлял
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_company")
    private Company company;
    //    каждый бот имеет Работника, который его добавлял
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_filial")
    private Filial filial;


    @Column(name = "order_complete")
    private boolean complete;

    @Override
    public String toString() {
        return "Order{" +
                "id=" + id +
                ", created=" + created +
                ", changed=" + changed +
                ", amount=" + amount +
                ", sum=" + sum +
                ", status=" + status +
                ", worker=" + worker +
                ", complete=" + complete +
                '}';
    }
}
