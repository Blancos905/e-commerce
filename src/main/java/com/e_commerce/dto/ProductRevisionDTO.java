package com.e_commerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO per la cronologia modifiche di un prodotto.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductRevisionDTO {

    private Long id;
    private Long productId;
    private LocalDateTime createdAt;
    private String nome;
    private String descrizione;
    private String disponibilita;
    private String ean;
    private String marca;
    private String codiceProduttore;
    private BigDecimal prezzoBase;
    private BigDecimal prezzoFinale;
    private Double aumentoPercentuale;
    private Long categoriaId;
}
