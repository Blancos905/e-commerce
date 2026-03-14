package com.e_commerce.dto;

import java.time.LocalDateTime;

public record AppliedImportDTO(
        Long id,
        String fileName,
        LocalDateTime appliedAt,
        Long supplierId,
        String supplierName
) {
}
