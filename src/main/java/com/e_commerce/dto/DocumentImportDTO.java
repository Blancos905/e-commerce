package com.e_commerce.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.Data;

@Data
public class DocumentImportDTO {

    @CsvBindByName(column = "sku")
    private String sku;

    @CsvBindByName(column = "ean")
    private String ean;

    @CsvBindByName(column = "tipo_documento", required = true)
    private String tipoDocumento;

    @CsvBindByName(column = "url_documento", required = true)
    private String urlDocumento;
}

