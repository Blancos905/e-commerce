package com.e_commerce.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductUpdateRequest {

    private String nome;

    private String descrizione;

    private String disponibilita;

    private String ean;

    private BigDecimal prezzoBase;

    private Double aumentoPercentuale;

    private Long categoriaId;

    /** Accetta stringa (es. "10,50" o "10.50") o numero per evitare errori di deserializzazione. */
    @JsonSetter
    public void setPrezzoBase(Object value) {
        if (value == null) {
            this.prezzoBase = null;
            return;
        }
        if (value instanceof BigDecimal) {
            this.prezzoBase = (BigDecimal) value;
            return;
        }
        if (value instanceof Number) {
            this.prezzoBase = BigDecimal.valueOf(((Number) value).doubleValue());
            return;
        }
        String s = value.toString().trim().replace(',', '.');
        if (s.isEmpty()) {
            this.prezzoBase = null;
            return;
        }
        try {
            this.prezzoBase = new BigDecimal(s);
        } catch (NumberFormatException e) {
            this.prezzoBase = null;
        }
    }
}

