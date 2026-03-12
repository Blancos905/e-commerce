package com.e_commerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Snapshot dello stato di un prodotto prima di un import.
 * Usato per il rollback: ripristinare i prodotti modificati ed eliminare quelli creati dall'import.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductSnapshotDTO {

    private Long id;
    private String sku;
    private String ean;
    private String nome;
    private String descrizione;
    private BigDecimal prezzoBase;
    private BigDecimal prezzoFinale;
    private Double aumentoPercentuale;
    private Long categoriaId;
    private Long supplierId;
    private String contati;
    private String disponibilita;
}
