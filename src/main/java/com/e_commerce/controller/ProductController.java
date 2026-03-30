package com.e_commerce.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.e_commerce.dto.AppliedImportDTO;
import com.e_commerce.dto.ProductUpdateRequest;
import com.e_commerce.dto.ProductRevisionDTO;
import com.e_commerce.model.Category;
import com.e_commerce.model.Document;
import com.e_commerce.model.Product;
import com.e_commerce.repository.CategoryRepository;
import com.e_commerce.repository.DocumentRepository;
import com.e_commerce.repository.ImportLogRepository;
import com.e_commerce.service.IcecatService;
import com.e_commerce.service.ImportService;
import com.e_commerce.service.MagentoService;
import com.e_commerce.service.ProductMatchingService;
import com.e_commerce.service.ProductRevisionService;
import com.e_commerce.service.ProductService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductService productService;
    private final CategoryRepository categoryRepository;
    private final DocumentRepository documentRepository;
    private final ImportLogRepository importLogRepository;
    private final ImportService importService;
    private final IcecatService icecatService;
    private final ProductMatchingService productMatchingService;
    private final ProductRevisionService productRevisionService;
    private final MagentoService magentoService;

    public ProductController(ProductService productService,
                             CategoryRepository categoryRepository,
                             DocumentRepository documentRepository,
                             ImportLogRepository importLogRepository,
                             ImportService importService,
                             IcecatService icecatService,
                             ProductMatchingService productMatchingService,
                             ProductRevisionService productRevisionService,
                             MagentoService magentoService) {
        this.productService = productService;
        this.categoryRepository = categoryRepository;
        this.documentRepository = documentRepository;
        this.importLogRepository = importLogRepository;
        this.importService = importService;
        this.icecatService = icecatService;
        this.productMatchingService = productMatchingService;
        this.productRevisionService = productRevisionService;
        this.magentoService = magentoService;
    }

    /** Test matching: GET /api/products/match?sku=xxx oppure ?ean=xxx */
    @GetMapping("/match")
    public ResponseEntity<?> testMatch(@RequestParam(required = false) String sku,
                                       @RequestParam(required = false) String ean) {
        var result = productMatchingService.findProduct(sku, ean);
        if (result.isFound()) {
            return ResponseEntity.ok(java.util.Map.of(
                    "found", true,
                    "matchType", result.matchType().name(),
                    "productId", result.product().getId(),
                    "productSku", result.product().getSku(),
                    "productEan", result.product().getEan() != null ? result.product().getEan() : ""
            ));
        }
        return ResponseEntity.ok(java.util.Map.of("found", false, "matchType", "NOT_FOUND"));
    }

    /** Numero totale di prodotti nel catalogo virtuale. */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> count() {
        return ResponseEntity.ok(Map.of("count", productService.count()));
    }

    @GetMapping
    public List<Product> list(@RequestParam(required = false) String nome,
                              @RequestParam(required = false) String sku,
                              @RequestParam(required = false) String ean,
                              @RequestParam(required = false) String categoria,
                              @RequestParam(required = false) String fornitore) {
        if (nome != null || sku != null || ean != null || categoria != null || fornitore != null) {
            return productService.search(nome, sku, ean, categoria, fornitore);
        }
        return productService.findAll();
    }

    @PostMapping
    public Product create(@RequestBody Product product) {
        // Prodotto creato manualmente: va nella categoria "Nuovi prodotti"
        // tramite flag, ma mantiene la categoria "vera" scelta dall'utente.
        product.setNuovoManuale(true);

        // Assicuriamoci di associare una categoria "managed" (evita riferimenti detached con solo id).
        if (product.getCategoria() != null && product.getCategoria().getId() != null) {
            Long catId = product.getCategoria().getId();
            Category cat = categoryRepository.findById(catId).orElse(null);
            product.setCategoria(cat);
        }

        return productService.save(product);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@PathVariable Long id, @RequestBody ProductUpdateRequest updated) {
        log.debug("PUT /products/{} ricevuto prezzoBase={}", id, updated.getPrezzoBase());
        return productService.updateProduct(id, updated)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Cronologia modifiche del singolo prodotto. */
    /** Cronologia modifiche globale (tutti i prodotti modificati). */
    @GetMapping("/all-revisions")
    public ResponseEntity<List<ProductRevisionDTO>> getAllRevisions() {
        return ResponseEntity.ok(productRevisionService.getAllRevisions());
    }

    @GetMapping("/{id}/revisions")
    public ResponseEntity<List<ProductRevisionDTO>> getRevisions(@PathVariable Long id) {
        if (productService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(productRevisionService.getRevisions(id));
    }

    /**
     * Ripristina il prodotto a una versione precedente e rimuove la revisione dalla cronologia.
     * Se il prodotto non esiste più, elimina comunque la revisione orfana e ritorna 200.
     */
    @PostMapping("/{id}/revert/{revisionId}")
    @Transactional
    public ResponseEntity<?> revertToRevision(@PathVariable Long id, @PathVariable Long revisionId) {
        var opt = productRevisionService.revertToRevision(id, revisionId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var r = opt.get();
        if (r.product() != null) {
            return ResponseEntity.ok(r.product());
        }
        return ResponseEntity.ok(java.util.Map.of("reverted", false, "revisionDeleted", true));
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
    @Transactional
    public ResponseEntity<Void> resetCatalog() {
        // cancella i prodotti tranne quelli in "Nuovi prodotti" (documenti collegati vengono rimossi per cascade/orphanRemoval)
        productService.deleteAllExceptCategoryName("Nuovi prodotti");

        // Coerenza UI: dopo reset non devono esistere import "applicati" da annullare.
        // Questi import alimentano `canRollbackLastImport` e la modale "Annulla import".
        importLogRepository.clearAppliedProductImports();

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sync-icecat-images")
    public ResponseEntity<?> syncIcecatImages(@PathVariable Long id) {
        var productOpt = productService.findById(id);
        if (productOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var product = productOpt.get();
        String ean = product.getEan() != null ? product.getEan().trim() : product.getSku();
        int count = icecatService.syncImagesForProduct(id);
        String diagnoseMsg = null;
        if (count == 0 && ean != null) {
            if (ean.matches(".*\\d{8,14}.*") || ean.replaceAll("\\D", "").matches("\\d{8,14}")) {
                var diag = icecatService.diagnose(ean);
                diagnoseMsg = diag.message();
            } else {
                diagnoseMsg = "Codice alfanumerico: cercato per nome e marca+codice. Nessuna immagine trovata. Verifica nome e marca del prodotto.";
            }
        }
        return ResponseEntity.ok(java.util.Map.of(
                "imagesAdded", count,
                "diagnoseMessage", diagnoseMsg != null ? diagnoseMsg : ""
        ));
    }

    @PostMapping("/sync-all-icecat-images")
    public ResponseEntity<?> syncAllIcecatImages() {
        int total = icecatService.syncImagesForAllProducts();
        return ResponseEntity.ok(java.util.Map.of("imagesAdded", total));
    }

    /** Debug: testa Icecat per un EAN e restituisce quante immagini trovato (senza salvare). */
    @GetMapping("/icecat-test")
    public ResponseEntity<?> testIcecat(@RequestParam String ean) {
        java.util.List<String> urls = icecatService.fetchImageUrls(ean);
        return ResponseEntity.ok(java.util.Map.of(
                "ean", ean,
                "imagesFound", urls.size(),
                "urls", urls
        ));
    }

    /** Diagnostica Icecat: restituisce il motivo per cui non trova immagini (EAN, 401, prodotto assente, ecc.). */
    @GetMapping("/icecat-diagnose")
    public ResponseEntity<?> diagnoseIcecat(@RequestParam String ean) {
        var diag = icecatService.diagnose(ean);
        var map = new java.util.HashMap<String, Object>();
        map.put("ean", diag.ean());
        map.put("httpStatus", diag.httpStatus() != null ? diag.httpStatus() : "");
        map.put("message", diag.message());
        map.put("authConfigured", icecatService.isAuthConfigured());
        return ResponseEntity.ok(map);
    }

    /** Diagnostica Icecat estesa: quando 0 immagini, include xmlPreview e debug per capire la risposta Icecat. */
    @GetMapping("/icecat-diagnose-verbose")
    public ResponseEntity<?> diagnoseIcecatVerbose(@RequestParam String ean) {
        var diag = icecatService.diagnoseVerbose(ean);
        var map = new java.util.HashMap<String, Object>();
        map.put("ean", diag.ean());
        map.put("httpStatus", diag.httpStatus() != null ? diag.httpStatus() : "");
        map.put("message", diag.message());
        map.put("imageUrls", diag.imageUrls());
        if (diag.xmlPreview() != null) map.put("xmlPreview", diag.xmlPreview());
        return ResponseEntity.ok(map);
    }

    /** Test connessione: stessa chiamata di Postman. Se Postman funziona ma questo no, copia le credenziali da Postman in application.properties. */
    @GetMapping("/icecat-test-connection")
    public ResponseEntity<?> icecatTestConnection(@RequestParam String ean) {
        return ResponseEntity.ok(icecatService.testConnection(ean));
    }

    /** Debug: restituisce XML grezzo da Icecat per diagnostica (vedi cosa restituisce l'API). */
    @GetMapping("/icecat-raw")
    public ResponseEntity<?> icecatRawXml(@RequestParam String ean) {
        String raw = icecatService.fetchRawXmlForDebug(ean);
        return ResponseEntity.ok(java.util.Map.of("ean", ean, "rawXml", raw));
    }

    /** Aggiunge un documento (es. immagine) al prodotto tramite URL. Alternativa a Icecat. */
    @PostMapping("/{id}/documents")
    @Transactional
    public ResponseEntity<?> addDocument(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        String url = body != null ? body.get("url") : null;
        String tipo = body != null && body.get("tipo") != null ? body.get("tipo").trim() : "immagine";
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body("Campo 'url' obbligatorio.");
        }
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ResponseEntity.badRequest().body("L'URL deve iniziare con http:// o https://");
        }
        var productOpt = productService.findByIdWithAssociations(id);
        if (productOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Product product = productOpt.get();
        int maxOrdine = product.getDocumenti().stream()
                .map(d -> d.getOrdine() != null ? d.getOrdine() : -1)
                .max(Integer::compareTo)
                .orElse(-1);
        Document doc = new Document();
        doc.setTipo(tipo);
        doc.setUrl(url);
        doc.setOrdine(maxOrdine + 1);
        doc.setProduct(product);
        documentRepository.save(doc);
        product.getDocumenti().add(doc);
        productService.save(product);
        return ResponseEntity.ok(doc);
    }

    /**
     * Carica un'immagine dal PC locale e la associa al prodotto.
     * Il file viene salvato nella stessa cartella usata da Icecat:
     * storage/product-data/{productId}/images/...
     */
    @PostMapping(path = "/{id}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<?> uploadDocument(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Nessun file caricato.");
        }
        var productOpt = productService.findByIdWithAssociations(id);
        if (productOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Product product = productOpt.get();
        try {
            String originalName = file.getOriginalFilename();
            String ext = ".jpg";
            if (originalName != null && originalName.contains(".")) {
                String e = originalName.substring(originalName.lastIndexOf('.')).toLowerCase();
                if (e.matches("\\.(jpeg|jpg|png|gif|webp)")) {
                    ext = e;
                }
            }
            String filename = "manual_" + System.currentTimeMillis() + ext;
            Path productDir = Paths.get(icecatService.getStoragePathAbsolute(), String.valueOf(id), "images");
            Files.createDirectories(productDir);
            Path filePath = productDir.resolve(filename);
            file.transferTo(filePath.toFile());

            String relativeUrl = "/api/images/product/" + id + "/" + filename;

            int maxOrdine = product.getDocumenti().stream()
                    .map(d -> d.getOrdine() != null ? d.getOrdine() : -1)
                    .max(Integer::compareTo)
                    .orElse(-1);

            Document doc = new Document();
            doc.setTipo("immagine");
            doc.setUrl(relativeUrl);
            doc.setOrdine(maxOrdine + 1);
            doc.setProduct(product);
            documentRepository.save(doc);
            product.getDocumenti().add(doc);
            productService.save(product);

            return ResponseEntity.ok(doc);
        } catch (IOException e) {
            return ResponseEntity.status(500).body("Errore nel salvataggio del file: " + e.getMessage());
        }
    }

    /** Imposta un documento come immagine principale (ordine 0). Le altre immagini vengono riordinate. */
    @PutMapping("/{productId}/documents/{documentId}/set-as-main")
    @Transactional
    public ResponseEntity<?> setDocumentAsMain(@PathVariable Long productId, @PathVariable Long documentId) {
        var docOpt = documentRepository.findById(documentId);
        if (docOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Document doc = docOpt.get();
        if (doc.getProduct() == null || !doc.getProduct().getId().equals(productId)) {
            return ResponseEntity.notFound().build();
        }
        Product product = productService.findByIdWithAssociations(productId).orElse(null);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        int ord = 1;
        for (Document d : product.getDocumenti()) {
            if (d.getId().equals(documentId)) {
                d.setOrdine(0);
            } else {
                d.setOrdine(ord++);
            }
        }
        documentRepository.saveAll(product.getDocumenti());
        return ResponseEntity.ok().build();
    }

    /** Rimuove un documento dal prodotto. */
    @DeleteMapping("/{productId}/documents/{documentId}")
    @Transactional
    public ResponseEntity<Void> removeDocument(@PathVariable Long productId, @PathVariable Long documentId) {
        var docOpt = documentRepository.findById(documentId);
        if (docOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Document doc = docOpt.get();
        if (doc.getProduct() == null || !doc.getProduct().getId().equals(productId)) {
            return ResponseEntity.notFound().build();
        }
        documentRepository.delete(doc);
        return ResponseEntity.noContent().build();
    }

    /** Restituisce il path assoluto della cartella storage Icecat. */
    @GetMapping("/icecat-storage-path")
    public ResponseEntity<?> getIcecatStoragePath() {
        String path = icecatService.getStoragePathAbsolute();
        return ResponseEntity.ok(java.util.Map.of("storagePath", path));
    }

    @GetMapping("/can-rollback-last-import")
    @Transactional(readOnly = true)
    public ResponseEntity<Boolean> canRollbackLastImport() {
        boolean canRollback = importLogRepository.existsAppliedImportWithSnapshot();
        return ResponseEntity.ok(canRollback);
    }

    /** Elenco degli import applicati al catalogo, ordinati per data (più recente per primo). */
    @GetMapping("/applied-imports")
    @Transactional(readOnly = true)
    public ResponseEntity<List<AppliedImportDTO>> listAppliedImports() {
        List<AppliedImportDTO> list = importLogRepository.findAppliedImportsOrderByAppliedAtDesc();
        return ResponseEntity.ok(list);
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

    /**
     * Esporta l'intero catalogo su Magento via REST API (OAuth 1.0a).
     * Crea o aggiorna i prodotti su Magento in base allo SKU.
     */
    @PostMapping("/export/magento")
    public ResponseEntity<?> exportToMagento() {
        if (!magentoService.isConfigured()) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of(
                            "error", "Magento non configurato",
                            "hint", "Configura magento.base-url, consumer-key, consumer-secret, access-token, access-token-secret in application.properties"
                    ));
        }
        List<Product> products = productService.findAll();
        var result = magentoService.syncCatalog(products);
        if (result.getError() != null) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", result.getError()
            ));
        }

        // Sync categorie per evitare che i PUT Magento appendano/lasciassero assegnazioni vecchie:
        // rimuove dalle categorie gestite quelle non più corrette e assegna la categoria attuale.
        var categoryResult = magentoService.syncCategoriesOnly(products);
        if (categoryResult.getError() != null) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", categoryResult.getError()
            ));
        }
        var body = new java.util.HashMap<String, Object>();
        body.put("created", result.getCreated());
        body.put("updated", result.getUpdated());
        body.put("skipped", result.getSkipped());
        body.put("imagesUploaded", result.getImagesUploaded());
        body.put("errorsBySku", result.getErrorsBySku());
        body.put("categoriesUpdated", categoryResult.getUpdated());
        body.put("categoriesUnchanged", categoryResult.getUnchanged());
        body.put("skippedNoSku", categoryResult.getSkippedNoSku());
        body.put("skippedNotOnMagento", categoryResult.getSkippedNotOnMagento());
        body.put("categoryErrorsBySku", categoryResult.getErrorsBySku());
        return ResponseEntity.ok(body);
    }

    /**
     * Sincronizza SOLO l'immagine principale dei prodotti già presenti su Magento.
     * Non tocca prezzi, descrizioni, categorie: ricarica soltanto la primary image
     * e la imposta come Base / Small / Thumbnail / Swatch.
     */
    @PostMapping("/export/magento/images")
    public ResponseEntity<?> exportMagentoImagesOnly() {
        if (!magentoService.isConfigured()) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of(
                            "error", "Magento non configurato",
                            "hint", "Configura magento.base-url, consumer-key, consumer-secret, access-token, access-token-secret in application.properties"
                    ));
        }
        List<Product> products = productService.findAll();
        var result = magentoService.syncImagesOnly(products);
        if (result.getError() != null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", result.getError()));
        }
        var body = new java.util.HashMap<String, Object>();
        body.put("created", result.getCreated());
        body.put("updated", result.getUpdated());
        body.put("skipped", result.getSkipped());
        body.put("imagesUploaded", result.getImagesUploaded());
        body.put("errorsBySku", result.getErrorsBySku());
        return ResponseEntity.ok(body);
    }

    /**
     * Sincronizza SOLO l'immagine principale di un singolo prodotto (by ID).
     * Utile per casi singoli quando manca l'immagine in lista Magento.
     */
    @PostMapping("/{id}/export/magento/image")
    public ResponseEntity<?> exportSingleMagentoImage(@PathVariable Long id) {
        if (!magentoService.isConfigured()) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of(
                            "error", "Magento non configurato",
                            "hint", "Configura magento.base-url, consumer-key, consumer-secret, access-token, access-token-secret in application.properties"
                    ));
        }
        var opt = productService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var result = magentoService.syncImagesOnly(java.util.List.of(opt.get()));
        if (result.getError() != null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", result.getError()));
        }
        var body = new java.util.HashMap<String, Object>();
        body.put("created", result.getCreated());
        body.put("updated", result.getUpdated());
        body.put("skipped", result.getSkipped());
        body.put("imagesUploaded", result.getImagesUploaded());
        body.put("errorsBySku", result.getErrorsBySku());
        return ResponseEntity.ok(body);
    }

    /** Endpoint chiamato dal frontend quando l'utente preme "Annulla" durante una sync Magento. */
    @PostMapping("/export/magento/cancel")
    public ResponseEntity<?> cancelMagentoSync() {
        magentoService.requestCancel();
        return ResponseEntity.ok().build();
    }

    /**
     * Allinea solo le categorie Magento alla categoria attuale nel catalogo virtuale
     * (dopo spostamenti tra categorie). Non crea prodotti né aggiorna prezzi/descrizioni.
     */
    @PostMapping("/export/magento/categories")
    public ResponseEntity<?> syncMagentoCategoriesFromCatalog() {
        if (!magentoService.isConfigured()) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of(
                            "error", "Magento non configurato",
                            "hint", "Configura magento.* in application.properties"
                    ));
        }
        List<Product> products = productService.findAll();
        var result = magentoService.syncCategoriesOnly(products);
        if (result.getError() != null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", result.getError()));
        }
        var body = new java.util.HashMap<String, Object>();
        body.put("updated", result.getUpdated());
        body.put("unchanged", result.getUnchanged());
        body.put("skippedNoSku", result.getSkippedNoSku());
        body.put("skippedNotOnMagento", result.getSkippedNotOnMagento());
        body.put("errorsBySku", result.getErrorsBySku());
        return ResponseEntity.ok(body);
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

