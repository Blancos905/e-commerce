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
}

