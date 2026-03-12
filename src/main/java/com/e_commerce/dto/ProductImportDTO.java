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

    /** SKU o codice articolo; se assente si usa ean (per file con solo EAN) */
    @CsvBindByName(column = "sku")
    private String sku;

    @CsvBindByName(column = "ean")
    private String ean;

    @CsvBindByName(column = "nome_prodotto")
    private String nome;

    @CsvBindByName(column = "prezzo")
    private Double prezzoBase;

    @CsvBindByName(column = "categoria")
    private String nomeCategoria;

    /** CS = disponibilità (quella che ci interessa) */
    @CsvBindByName(column = "disponibilita")
    private String disponibilita;
}