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
@Table(name = "hello_text")
public class HelloText {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "hello_id")
    private Long id;

    @Column(name = "hello_text")
    private String hello_text;
}
