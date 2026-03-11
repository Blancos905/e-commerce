package com.e_commerce.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductUpdateRequest {

    private String nome;

    private String descrizione;

    private BigDecimal prezzoBase;

    private Double aumentoPercentuale;

    private Long categoriaId;
}

