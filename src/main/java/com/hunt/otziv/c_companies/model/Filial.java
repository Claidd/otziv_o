package com.hunt.otziv.c_companies.model;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.r_review.model.Review;
import jakarta.persistence.*;
import lombok.*;

import java.util.Objects;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "filial")
public class Filial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "filial_id")
    private Long id;

    //    название филиала
    @Column(name = "filial_title")
    private String title;

    //    url
    @Column(name = "filial_url")
    private String url;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id")
    @ToString.Exclude
    private City city;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    @ToString.Exclude
    private Company company;

    public Filial(int i, String нетФилиала, String пусто) {
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Filial filial = (Filial) o;
        return Objects.equals(id, filial.id) && Objects.equals(title, filial.title) && Objects.equals(url, filial.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, url);
    }

    @Override
    public String toString() {
        return "Filial{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", url='" + url + '\'' +
                '}';
    }
}
