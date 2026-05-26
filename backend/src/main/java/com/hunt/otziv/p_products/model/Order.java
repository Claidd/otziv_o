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
import java.util.Objects;

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
    @Column(name = "order_status_changed_at")
    private LocalDateTime statusChangedAt;
    @Column(name = "order_pay_day")
    private LocalDate payDay;
    @Column(name = "order_amount")
    private int amount;
    @Column(name = "order_sum")
    private BigDecimal sum;
    @Column(name = "order_counter")
    private int counter;

    @Builder.Default
    @Column(name = "order_waiting_for_client")
    private boolean waitingForClient = false;

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

    public void setStatus(OrderStatus status) {
        if (statusChanged(this.status, status)) {
            this.statusChangedAt = LocalDateTime.now();
        }
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        if (statusChangedAt == null) {
            statusChangedAt = LocalDateTime.now();
        }
    }

    private boolean statusChanged(OrderStatus current, OrderStatus next) {
        if (current == next) {
            return false;
        }
        if (current == null || next == null) {
            return true;
        }
        if (current.getId() != null || next.getId() != null) {
            return !Objects.equals(current.getId(), next.getId());
        }
        return !Objects.equals(current.getTitle(), next.getTitle());
    }

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
                ", manager=" + manager +
                ", complete=" + complete +
                '}';
    }
}
