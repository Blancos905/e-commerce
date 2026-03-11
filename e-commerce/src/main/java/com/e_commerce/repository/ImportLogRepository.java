package com.e_commerce.repository;

import com.e_commerce.dto.ImportLogSummaryDTO;
import com.e_commerce.model.ImportLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ImportLogRepository extends JpaRepository<ImportLog, Long> {

    @Query("""
            select new com.e_commerce.dto.ImportLogSummaryDTO(
                l.id,
                l.fileName,
                l.tipo,
                l.fileContentType,
                l.importedAt
            )
            from ImportLog l
            where l.supplier.id = :supplierId
            order by l.importedAt desc
            """)
    List<ImportLogSummaryDTO> findSummariesBySupplierIdOrderByImportedAtDesc(@Param("supplierId") Long supplierId);

    boolean existsByIdAndSupplierId(Long id, Long supplierId);
}

