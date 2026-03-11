package com.e_commerce.model;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import java.util.List;

@Entity
@Data
@JsonIgnoreProperties({"subCategories"})
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String nome;

    private Double aumentoPercentuale; // Aumento specifico per categoria

    @ManyToOne
    @JoinColumn(name = "parent_id")
    private Category parent; // Per struttura gerarchica

    @OneToMany(mappedBy = "parent")
    private List<Category> subCategories;
}