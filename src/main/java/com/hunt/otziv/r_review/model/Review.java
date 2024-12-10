package com.hunt.otziv.r_review.model;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.util.Objects;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "reviews")
public class Review {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long id;
    @Column(name = "review_text")
    private String text;
    @Column(name = "review_answer")
    private String answer;
    @CreationTimestamp
    @Column(name = "review_created")
    private LocalDate created;
    @UpdateTimestamp
    @Column(name = "review_changed")
    private LocalDate changed;
    @Column(name = "review_publish_date")
    private LocalDate publishedDate;
    @Column(name = "review_vigul")
    private boolean vigul;
    @Column(name = "review_publish")
    private boolean publish;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_category")
    private Category category;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_subcategory")
    private SubCategory subCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_bot")
    private Bot bot;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_order_details", updatable = false)
    private OrderDetails orderDetails;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_filial")
    private Filial filial;
    //    каждый бот имеет Работника, который его добавлял
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_worker")
    private Worker worker;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Review review = (Review) o;
        return publish == review.publish && Objects.equals(id, review.id) && Objects.equals(text, review.text) && Objects.equals(answer, review.answer) && Objects.equals(created, review.created) && Objects.equals(changed, review.changed) && Objects.equals(publishedDate, review.publishedDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, text, answer, created, changed, publishedDate, publish);
    }

    @Override
    public String toString() {
        return "Review{" +
                "id=" + id +
                ", text='" + text + '\'' +
                ", answer='" + answer + '\'' +
                ", created=" + created +
                ", changed=" + changed +
                ", publishedDate=" + publishedDate +
                ", publish=" + publish +
                '}';
    }
}
