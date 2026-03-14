package com.e_commerce.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Snapshot dello stato di un prodotto prima di una modifica manuale.
 * Permette la cronologia delle modifiche e il ripristino a una versione precedente.
 */
@Entity
@Data
public class ProductRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    /**
     * Snapshot JSON dello stato del prodotto prima della modifica.
     * Contiene i campi modificabili: nome, descrizione, disponibilita, ean, marca,
     * codiceProduttore, prezzoBase, prezzoFinale, aumentoPercentuale, categoriaId.
     * Usa TEXT invece di @Lob per evitare l'errore PostgreSQL "Large Object in auto-commit".
     */
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    @JsonIgnore
    private String snapshotJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
