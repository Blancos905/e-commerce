package com.e_commerce.model;



import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String sku;

    @Column(nullable = false)
    private String nome;

    @Column(columnDefinition = "TEXT")
    private String descrizione;

    // prezzo base del fornitore (può essere assente)
    @Column
    private BigDecimal prezzoBase;

    // prezzo finale calcolato con gli aumenti (può essere assente se non c'è prezzo base)
    @Column
    private BigDecimal prezzoFinale;

    // aumento specifico per prodotto (priorità massima)
    private Double aumentoPercentuale;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category categoria;

    @ManyToOne
    @JoinColumn(name = "supplier_id")
    private Supplier fornitore;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Document> documenti = new ArrayList<>();
}
