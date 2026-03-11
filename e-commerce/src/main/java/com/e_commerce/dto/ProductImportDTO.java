package com.e_commerce.dto;


import com.opencsv.bean.CsvBindByName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO per la mappatura dei dati dal file CSV dei prodotti.
 * Utilizza OpenCSV per collegare le colonne del file ai campi della classe.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductImportDTO {

    @CsvBindByName(column = "sku", required = true)
    private String sku;

    @CsvBindByName(column = "nome_prodotto")
    private String nome;

    @CsvBindByName(column = "prezzo")
    private Double prezzoBase;

    @CsvBindByName(column = "categoria", required = true)
    private String nomeCategoria;
}