package com.e_commerce.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.e_commerce.dto.ProductUpdateRequest;
import com.e_commerce.model.Category;
import com.e_commerce.model.Document;
import com.e_commerce.model.Product;
import com.e_commerce.repository.CategoryRepository;
import com.e_commerce.repository.ImportLogRepository;
import com.e_commerce.service.IcecatService;
import com.e_commerce.service.ImportService;
import com.e_commerce.service.ProductService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductService productService;
    private final CategoryRepository categoryRepository;
    private final ImportLogRepository importLogRepository;
    private final ImportService importService;
    private final IcecatService icecatService;

    public ProductController(ProductService productService,
                             CategoryRepository categoryRepository,
                             ImportLogRepository importLogRepository,
                             ImportService importService,
                             IcecatService icecatService) {
        this.productService = productService;
        this.categoryRepository = categoryRepository;
        this.importLogRepository = importLogRepository;
        this.importService = importService;
        this.icecatService = icecatService;
    }

    @GetMapping
    public List<Product> list(@RequestParam(required = false) String nome,
                              @RequestParam(required = false) String sku,
                              @RequestParam(required = false) String categoria) {
        if (nome != null || sku != null || categoria != null) {
            return productService.search(nome, sku, categoria);
        }
        return productService.findAll();
    }

    @PostMapping
    public Product create(@RequestBody Product product) {
        return productService.save(product);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@PathVariable Long id, @RequestBody ProductUpdateRequest updated) {
        log.debug("PUT /products/{} ricevuto prezzoBase={}", id, updated.getPrezzoBase());
        return productService.updateProduct(id, updated)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (productService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        productService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/reset")
    public ResponseEntity<Void> resetCatalog() {
        // cancella tutti i prodotti (documenti collegati vengono rimossi per cascade/orphanRemoval)
        productService.deleteAll();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sync-icecat-images")
    public ResponseEntity<?> syncIcecatImages(@PathVariable Long id) {
        if (productService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        int count = icecatService.syncImagesForProduct(id);
        return ResponseEntity.ok(java.util.Map.of("imagesAdded", count));
    }

    @PostMapping("/sync-all-icecat-images")
    public ResponseEntity<?> syncAllIcecatImages() {
        int total = icecatService.syncImagesForAllProducts();
        return ResponseEntity.ok(java.util.Map.of("imagesAdded", total));
    }

    @GetMapping("/can-rollback-last-import")
    @Transactional(readOnly = true)
    public ResponseEntity<Boolean> canRollbackLastImport() {
        boolean canRollback = importLogRepository.existsAppliedImportWithSnapshot();
        return ResponseEntity.ok(canRollback);
    }

    @PostMapping("/rollback-last-import")
    @Transactional
    public ResponseEntity<?> rollbackLastImport() {
        return importLogRepository.findFirstByAppliedAtNotNullOrderByAppliedAtDesc()
                .filter(log -> log.getPreviousStateJson() != null && !log.getPreviousStateJson().isBlank())
                .map(log -> {
                    try {
                        importService.rollbackImport(log);
                        return ResponseEntity.ok().build();
                    } catch (Exception e) {
                        return ResponseEntity.<Object>internalServerError()
                                .body("Errore durante il rollback: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    }
                })
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body("Nessun import applicato di recente su cui fare rollback."));
    }

    @GetMapping(value = "/export/csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv() {
        List<Product> products = productService.findAll();

        String header = "sku,nome_prodotto,categoria,prezzo_finale,disponibilita,documenti\n";
        String body = products.stream()
                .map(p -> {
                    String categoria = p.getCategoria() != null ? p.getCategoria().getNome() : "";
                    String docs = p.getDocumenti() != null
                            ? p.getDocumenti().stream()
                                .map(Document::getUrl)
                                .collect(Collectors.joining(";"))
                            : "";
                    return String.join(",",
                            safe(p.getSku()),
                            safe(p.getNome()),
                            safe(categoria),
                            p.getPrezzoFinale() != null ? p.getPrezzoFinale().toString() : "",
                            safe(p.getDisponibilita()),
                            safe(docs)
                    );
                })
                .collect(Collectors.joining("\n"));

        String csv = header + body;
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=catalogo.csv");
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));

        return ResponseEntity.ok()
                .headers(headers)
                .body(bytes);
    }

    @GetMapping(value = "/export/csv/by-supplier/{supplierId}", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsvBySupplier(@PathVariable Long supplierId) {
        List<Product> products = productService.findByFornitoreId(supplierId);
        if (products.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String header = "sku,nome_prodotto,categoria,prezzo_finale,disponibilita,documenti\n";
        String body = products.stream()
                .map(p -> {
                    String categoria = p.getCategoria() != null ? p.getCategoria().getNome() : "";
                    String docs = p.getDocumenti() != null
                            ? p.getDocumenti().stream()
                            .map(Document::getUrl)
                            .collect(Collectors.joining(";"))
                            : "";
                    return String.join(",",
                            safe(p.getSku()),
                            safe(p.getNome()),
                            safe(categoria),
                            p.getPrezzoFinale() != null ? p.getPrezzoFinale().toString() : "",
                            safe(p.getDisponibilita()),
                            safe(docs)
                    );
                })
                .collect(Collectors.joining("\n"));

        String csv = header + body;
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=catalogo_fornitore_" + supplierId + ".csv");
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));

        return ResponseEntity.ok()
                .headers(headers)
                .body(bytes);
    }

    @GetMapping("/export/json")
    public List<Product> exportJson() {
        return productService.findAll();
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        // semplice escaping di virgole e virgolette
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

