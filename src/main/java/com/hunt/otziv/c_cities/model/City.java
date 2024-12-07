package com.hunt.otziv.c_cities.model;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_companies.model.Filial;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.util.Set;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "cities")
public class City {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "city_id")
    private Long id;

    //    название города
    @Column(name = "city_title")
    private String title;

    //     одного города может быть много филиалов
    @OneToMany(mappedBy = "city", fetch = FetchType.LAZY, orphanRemoval = true)
    @BatchSize(size = 10)
    private Set<Filial> filial;

    //     одного города может быть много филиалов
    @OneToMany(mappedBy = "botCity", fetch = FetchType.LAZY, orphanRemoval = true)
    @BatchSize(size = 10)
    private Set<Bot> bots;
}
