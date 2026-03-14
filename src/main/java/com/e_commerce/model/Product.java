package com.e_commerce.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.BatchSize;

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

    /** EAN/GTIN per lookup Icecat (opzionale; se assente si usa sku) */
    @Column(length = 32)
    private String ean;

    /** Marca/vendor per fallback Icecat (prod_id + vendor quando EAN non trova risultati) */
    @Column(length = 128)
    private String marca;

    /** Codice produttore (prod_id Icecat) per fallback con marca */
    @Column(length = 64)
    private String codiceProduttore;

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

    /** CON = contati (stock contato dal fornitore) */
    @Column(length = 64)
    private String contati;

    /** CS = disponibilità (quella che ci interessa) */
    @Column(length = 64)
    private String disponibilita;

    @BatchSize(size = 50)
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordine ASC")
    private List<Document> documenti = new ArrayList<>();
}
