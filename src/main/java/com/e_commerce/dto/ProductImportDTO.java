package com.e_commerce.dto;


import com.e_commerce.dto.converters.DoubleLocaleConverter;
import com.opencsv.bean.CsvBindByName;
import com.opencsv.bean.CsvCustomBindByName;
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

    /** Prezzo base; accetta virgola come separatore decimale (es. 5,25) */
    @CsvCustomBindByName(column = "prezzo", converter = DoubleLocaleConverter.class)
    private Double prezzoBase;

    @CsvBindByName(column = "categoria")
    private String nomeCategoria;

    @CsvBindByName(column = "marca")
    private String marca;

    @CsvBindByName(column = "codice_produttore")
    private String codiceProduttore;

    /** CS = disponibilità (quella che ci interessa) */
    @CsvBindByName(column = "disponibilita")
    private String disponibilita;
}