package com.e_commerce.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class ImportLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    /**
     * Tipo di import: ad esempio "PRODOTTI", "DOCUMENTI", "FORNITORI".
     */
    @Column(nullable = false, length = 50)
    private String tipo;

    /**
     * Nome del file CSV caricato.
     */
    @Column(nullable = false)
    private String fileName;

    @Lob
    @Column(name = "file_content")
    @JsonIgnore
    private byte[] fileContent;

    @Column(length = 200)
    private String fileContentType;

    /**
     * Data e ora dell'import.
     */
    @Column(nullable = false)
    private LocalDateTime importedAt;

    /**
     * Data e ora in cui l'import è stato applicato al catalogo (null = non ancora applicato).
     */
    @Column
    private LocalDateTime appliedAt;

    /**
     * Snapshot JSON dello stato dei prodotti prima dell'import.
     * Mappa SKU -> ProductSnapshotDTO. Permette il rollback senza resettare tutto il catalogo.
     */
    @Lob
    @Column(name = "previous_state_json")
    @JsonIgnore
    private String previousStateJson;
}

