package com.e_commerce.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplierImportDTO {

    @CsvBindByName(column = "nome", required = true)
    private String nome;

    @CsvBindByName(column = "codice")
    private String codice;

    @CsvBindByName(column = "email")
    private String email;

    @CsvBindByName(column = "telefono")
    private String telefono;

    @CsvBindByName(column = "note")
    private String note;
}

