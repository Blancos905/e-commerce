package com.e_commerce.controller;

import com.e_commerce.dto.ImportLogSummaryDTO;
import com.e_commerce.model.ImportLog;
import com.e_commerce.model.Supplier;
import com.e_commerce.service.ImportService;
import com.e_commerce.service.SupplierService;
import com.e_commerce.repository.ImportLogRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService supplierService;
    private final ImportLogRepository importLogRepository;
    private final ImportService importService;

    public SupplierController(SupplierService supplierService,
                              ImportLogRepository importLogRepository,
                              ImportService importService) {
        this.supplierService = supplierService;
        this.importLogRepository = importLogRepository;
        this.importService = importService;
    }

    @GetMapping
    public List<Supplier> list() {
        return supplierService.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Supplier> get(@PathVariable Long id) {
        return supplierService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/imports")
    public ResponseEntity<List<ImportLogSummaryDTO>> listImports(@PathVariable Long id) {
        if (supplierService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<ImportLogSummaryDTO> logs = importLogRepository.findSummariesBySupplierIdOrderByImportedAtDesc(id);
        return ResponseEntity.ok(logs);
    }

    @PostMapping("/{id}/imports")
    @Transactional
    public ResponseEntity<?> uploadImportFile(@PathVariable Long id,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "tipo", required = false) String tipo) {
        Supplier supplier = supplierService.findById(id).orElse(null);
        if (supplier == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body("File CSV vuoto o non selezionato.");
            }
            ImportLog log = new ImportLog();
            log.setSupplier(supplier);
            String tipoValue = tipo != null && !tipo.isBlank() ? tipo.trim().toUpperCase() : "PRODOTTI";
            log.setTipo(tipoValue);
            log.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "import.csv");
            log.setFileContent(file.getBytes());
            log.setFileContentType(file.getContentType());
            log.setImportedAt(LocalDateTime.now());
            ImportLog saved = importLogRepository.save(log);
            // Salva solo in cartella: l'import nel catalogo avviene separatamente quando l'utente clicca "Importa nel catalogo" (⇢)
            saved = importLogRepository.findById(saved.getId()).orElse(saved);
            return ResponseEntity.ok(new ImportLogSummaryDTO(
                    saved.getId(),
                    saved.getFileName(),
                    saved.getTipo(),
                    saved.getFileContentType(),
                    saved.getImportedAt(),
                    saved.getAppliedAt()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Errore durante il salvataggio del CSV nello storico: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @PostMapping("/{id}/imports/{importId}/apply-products")
    @Transactional
    public ResponseEntity<?> applyImportToCatalog(@PathVariable Long id, @PathVariable Long importId) {
        if (supplierService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImportLog log = importLogRepository.findById(importId).orElse(null);
        if (log == null || log.getSupplier() == null || !log.getSupplier().getId().equals(id)) {
            return ResponseEntity.notFound().build();
        }
        if (log.getTipo() == null || !"PRODOTTI".equalsIgnoreCase(log.getTipo())) {
            return ResponseEntity.badRequest().body("Questo CSV non è di tipo PRODOTTI.");
        }
        try {
            importService.applyImportWithSnapshot(log);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Errore durante l'import nel catalogo: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @PostMapping("/{id}/imports/{importId}/rollback")
    @Transactional
    public ResponseEntity<?> rollbackImport(@PathVariable Long id, @PathVariable Long importId) {
        if (supplierService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImportLog log = importLogRepository.findById(importId).orElse(null);
        if (log == null || log.getSupplier() == null || !log.getSupplier().getId().equals(id)) {
            return ResponseEntity.notFound().build();
        }
        if (log.getTipo() == null || !"PRODOTTI".equalsIgnoreCase(log.getTipo())) {
            return ResponseEntity.badRequest().body("Rollback supportato solo per import di tipo PRODOTTI.");
        }
        try {
            importService.rollbackImport(log);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Errore durante il rollback: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @DeleteMapping("/{id}/imports/{importId}")
    @Transactional
    public ResponseEntity<?> deleteImport(@PathVariable Long id, @PathVariable Long importId) {
        if (supplierService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!importLogRepository.existsByIdAndSupplierId(importId, id)) {
            return ResponseEntity.notFound().build();
        }

        ImportLog log = importLogRepository.findById(importId).orElse(null);
        if (log != null && "PRODOTTI".equalsIgnoreCase(log.getTipo())
                && log.getPreviousStateJson() != null && !log.getPreviousStateJson().isBlank()) {
            try {
                importService.rollbackImport(log);
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body("Errore durante il rollback prima dell'eliminazione: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }

        importLogRepository.deleteById(importId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/imports/{importId}/file")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> downloadImportFile(@PathVariable Long id, @PathVariable Long importId) {
        if (supplierService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImportLog log = importLogRepository.findById(importId).orElse(null);
        if (log == null || log.getSupplier() == null || !log.getSupplier().getId().equals(id)) {
            return ResponseEntity.notFound().build();
        }
        byte[] content = log.getFileContent();
        if (content == null || content.length == 0) {
            return ResponseEntity.notFound().build();
        }

        String filename = log.getFileName() != null ? log.getFileName() : "import.csv";
        String contentType = log.getFileContentType();
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        try {
            if (contentType != null && !contentType.isBlank()) {
                mediaType = MediaType.parseMediaType(contentType);
            } else {
                mediaType = MediaType.valueOf("text/csv");
            }
        } catch (Exception ignored) {
            // fallback
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replace("\"", "") + "\"")
                .body(content);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Supplier supplier) {
        String nome = supplier.getNome() != null ? supplier.getNome().trim() : null;
        if (nome == null || nome.isBlank()) {
            return ResponseEntity.badRequest().body("Il campo 'nome' è obbligatorio.");
        }
        supplier.setNome(nome);

        if (supplierService.findByNome(nome).isPresent()) {
            return ResponseEntity.status(409).body("Fornitore già presente con nome: " + nome);
        }

        String codice = supplier.getCodice() != null ? supplier.getCodice().trim() : null;
        if (codice != null && codice.isBlank()) {
            codice = null;
        }
        supplier.setCodice(codice);

        if (codice != null && supplierService.findByCodice(codice).isPresent()) {
            return ResponseEntity.status(409).body("Codice fornitore già presente: " + codice);
        }

        return ResponseEntity.ok(supplierService.save(supplier));
    }

    @PutMapping("/{id}/increase")
    public ResponseEntity<?> updateSupplierIncrease(@PathVariable Long id,
                                                    @RequestParam("percent") Double percent) {
        return supplierService.findById(id)
                .map(supplier -> {
                    supplier.setAumentoPercentuale(percent);
                    Supplier saved = supplierService.save(supplier);
                    return ResponseEntity.ok(saved);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Supplier updated) {
        return supplierService.findById(id)
                .map(existing -> {
                    String nome = updated.getNome() != null ? updated.getNome().trim() : null;
                    if (nome == null || nome.isBlank()) {
                        return ResponseEntity.badRequest().body("Il campo 'nome' è obbligatorio.");
                    }

                    // Controllo duplicato nome (escludendo se stesso)
                    return supplierService.findByNome(nome)
                            .filter(other -> !other.getId().equals(id))
                            .<ResponseEntity<?>>map(other ->
                                    ResponseEntity.status(409).body("Fornitore già presente con nome: " + nome)
                            )
                            .orElseGet(() -> {
                                String codice = updated.getCodice() != null ? updated.getCodice().trim() : null;
                                if (codice != null && codice.isBlank()) {
                                    codice = null;
                                }

                                String finalCodice = codice;
                                // Controllo duplicato codice (escludendo se stesso)
                                return supplierService.findByCodice(finalCodice)
                                        .filter(other -> !other.getId().equals(id))
                                        .<ResponseEntity<?>>map(other ->
                                                ResponseEntity.status(409).body("Codice fornitore già presente: " + finalCodice)
                                        )
                                        .orElseGet(() -> {
                                            existing.setNome(nome);
                                            existing.setCodice(finalCodice);
                                            existing.setEmail(updated.getEmail());
                                            existing.setTelefono(updated.getTelefono());
                                            existing.setNote(updated.getNote());
                                            Supplier saved = supplierService.save(existing);
                                            return ResponseEntity.ok(saved);
                                        });
                            });
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (supplierService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        supplierService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}

