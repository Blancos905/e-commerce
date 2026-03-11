package com.e_commerce.dto;

import java.time.LocalDateTime;

public record ImportLogSummaryDTO(
        Long id,
        String fileName,
        String tipo,
        String fileContentType,
        LocalDateTime importedAt
) {
}

