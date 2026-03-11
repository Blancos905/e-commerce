package com.e_commerce.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.Data;

@Data
public class DocumentImportDTO {

    @CsvBindByName(column = "sku", required = true)
    private String sku;

    @CsvBindByName(column = "tipo_documento", required = true)
    private String tipoDocumento;

    @CsvBindByName(column = "url_documento", required = true)
    private String urlDocumento;
}

