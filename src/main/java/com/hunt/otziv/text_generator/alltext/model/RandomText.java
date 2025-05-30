package com.hunt.otziv.text_generator.alltext.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "random_text")
public class RandomText {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "random_id")
    private Long id;

    @Column(name = "random_text", nullable = false, length = 3000)
    private String text;
}

