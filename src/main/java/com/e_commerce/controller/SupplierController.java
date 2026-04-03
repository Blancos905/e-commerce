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
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /**
     * Scrape offerte Takefive: genera un CSV e lo restituisce al frontend per l'anteprima.
     * Non importa automaticamente nel catalogo (l'utente sceglie poi "Salva in cartella").
     */
    @PostMapping("/{id}/scrape-offers")
    public ResponseEntity<?> scrapeOffers(@PathVariable Long id,
                                          @RequestParam(value = "fileName", required = false) String fileName) {
        var supplierOpt = supplierService.findById(id);
        if (supplierOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Supplier supplier = supplierOpt.get();

        String supplierName = supplier.getNome() != null ? supplier.getNome().trim() : "";
        if (!"takefive".equalsIgnoreCase(supplierName)) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "Scraping offerte disponibile solo per il fornitore Takefive",
                    "supplierId", id,
                    "supplierName", supplierName
            ));
        }

        String desiredName = computeDesiredOfferFileName(id, fileName);

        String pythonExe = "C:\\Python310\\python.exe";
        String pythonScript = "C:\\Users\\CostantinoM\\scrape_takefive.py";
        Path outputDir = Paths.get("C:\\Users\\CostantinoM\\Desktop\\takefive_csv");

        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExe, pythonScript);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder logs = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logs.append(line).append('\n');
                }
            }

            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                String logText = logs.length() > 4000 ? logs.substring(logs.length() - 4000) : logs.toString();
                return ResponseEntity.status(500).body(java.util.Map.of(
                        "error", "Scrape Takefive fallito",
                        "exitCode", exitCode,
                        "logs", logText
                ));
            }

            if (!Files.exists(outputDir)) {
                return ResponseEntity.status(500).body(java.util.Map.of(
                        "error", "Cartella output scraper non trovata",
                        "outputDir", outputDir.toString()
                ));
            }

            try (java.util.stream.Stream<Path> stream = Files.list(outputDir)) {
                List<Path> csvFiles = stream
                        .filter(p -> p.getFileName() != null && p.getFileName().toString().toLowerCase().endsWith(".csv"))
                        .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                        .collect(Collectors.toList());

                if (csvFiles.isEmpty()) {
                    return ResponseEntity.status(500).body(java.util.Map.of(
                            "error", "Nessun CSV prodotto dallo scraper",
                            "outputDir", outputDir.toString()
                    ));
                }

                Path latest = csvFiles.get(0);
                byte[] bytes = Files.readAllBytes(latest);
                String base64 = Base64.getEncoder().encodeToString(bytes);

                return ResponseEntity.ok(java.util.Map.of(
                        "fileName", desiredName,
                        "fileContentType", "text/csv",
                        "fileContentBase64", base64
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of(
                    "error", "Errore durante lo scrape Takefive: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
            ));
        }
    }

    private String computeDesiredOfferFileName(Long supplierId, String requested) {
        String cleaned = sanitizeCsvFileName(requested);
        if (cleaned != null) {
            return cleaned;
        }
        // Auto: offerta1.csv, offerta2.csv, ...
        int next = nextOffertaIndex(supplierId);
        return "offerta" + next + ".csv";
    }

    private String sanitizeCsvFileName(String requested) {
        if (requested == null) return null;
        String s = requested.trim();
        if (s.isEmpty()) return null;

        // Replace invalid Windows filename chars, collapse spaces
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;

        // Enforce .csv extension
        if (!s.toLowerCase().endsWith(".csv")) {
            s = s + ".csv";
        }

        // Avoid crazy long names
        if (s.length() > 80) {
            s = s.substring(0, 80);
            if (!s.toLowerCase().endsWith(".csv")) {
                s = s.replaceAll("\\.+$", "");
                s = s + ".csv";
            }
        }
        return s;
    }

    private int nextOffertaIndex(Long supplierId) {
        try {
            List<ImportLogSummaryDTO> logs = importLogRepository.findSummariesBySupplierIdOrderByImportedAtDesc(supplierId);
            if (logs == null || logs.isEmpty()) return 1;
            Pattern p = Pattern.compile("(?i)^offerta\\s*(\\d+)\\.csv$");
            int max = 0;
            for (ImportLogSummaryDTO dto : logs) {
                if (dto == null) continue;
                String name = dto.fileName();
                if (name == null) continue;
                Matcher m = p.matcher(name.trim());
                if (m.matches()) {
                    try {
                        int n = Integer.parseInt(m.group(1));
                        if (n > max) max = n;
                    } catch (NumberFormatException ignored) {}
                }
            }
            return max + 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    @GetMapping("/{id}/imports/compare")
    public ResponseEntity<?> compareImports(@PathVariable Long id,
                                            @RequestParam("leftImportId") Long leftImportId,
                                            @RequestParam("rightImportId") Long rightImportId) {
        if (supplierService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImportLog left = importLogRepository.findById(leftImportId).orElse(null);
        ImportLog right = importLogRepository.findById(rightImportId).orElse(null);
        if (left == null || right == null) {
            return ResponseEntity.badRequest().body("Uno o entrambi gli import selezionati non esistono.");
        }
        if (left.getSupplier() == null || right.getSupplier() == null
                || !id.equals(left.getSupplier().getId()) || !id.equals(right.getSupplier().getId())) {
            return ResponseEntity.badRequest().body("Puoi comparare solo CSV dello stesso fornitore.");
        }
        if (!"PRODOTTI".equalsIgnoreCase(left.getTipo()) || !"PRODOTTI".equalsIgnoreCase(right.getTipo())) {
            return ResponseEntity.badRequest().body("La comparazione è supportata solo per CSV di tipo PRODOTTI.");
        }
        try {
            Map<String, Object> result = importService.compareProductImportLogs(left, right);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Errore durante la comparazione CSV: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @GetMapping("/{id}/imports/{importId}/compare-db")
    public ResponseEntity<?> compareImportWithDatabase(@PathVariable Long id, @PathVariable Long importId) {
        if (supplierService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImportLog csvImport = importLogRepository.findById(importId).orElse(null);
        if (csvImport == null || csvImport.getSupplier() == null || !id.equals(csvImport.getSupplier().getId())) {
            return ResponseEntity.badRequest().body("Import non trovato per il fornitore selezionato.");
        }
        if (!"PRODOTTI".equalsIgnoreCase(csvImport.getTipo())) {
            return ResponseEntity.badRequest().body("La comparazione con database è supportata solo per CSV di tipo PRODOTTI.");
        }
        try {
            Map<String, Object> result = importService.compareImportLogWithDatabase(csvImport);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Errore durante la comparazione CSV vs database: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
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
        } catch (CancellationException e) {
            // Import interrotto dall'utente: mantieni i prodotti già applicati (no rollback)
            return ResponseEntity.status(499)
                    .body("Import nel catalogo annullato dall'utente. I prodotti già applicati sono stati salvati.");
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

