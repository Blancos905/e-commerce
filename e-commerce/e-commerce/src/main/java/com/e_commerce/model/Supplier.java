package com.e_commerce.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String nome;

    @Column(unique = true)
    private String codice;

    private String email;

    private String telefono;

    @Column(columnDefinition = "TEXT")
    private String note;
}

