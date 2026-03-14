package com.e_commerce.model;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class Document {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tipo; // manuale, scheda_tecnica, immagine, ecc.
    private String url;

    /** Ordine di visualizzazione (0 = principale). Usato per scegliere quale immagine mostrare per prima. */
    private Integer ordine;

    @ManyToOne
    @JoinColumn(name = "product_id")
    @JsonIgnore
    private Product product;
}