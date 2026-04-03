package com.e_commerce.service;

import com.e_commerce.dto.DocumentImportDTO;
import com.e_commerce.dto.ProductImportDTO;
import com.e_commerce.dto.ProductSnapshotDTO;
import com.e_commerce.dto.ProductRevisionDTO;
import com.e_commerce.dto.SupplierImportDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.e_commerce.model.Category;
import com.e_commerce.model.Document;
import com.e_commerce.model.ImportLog;
import com.e_commerce.model.Product;
import com.e_commerce.model.ProductRevision;
import com.e_commerce.model.Supplier;
import com.e_commerce.repository.CategoryRepository;
import com.e_commerce.repository.DocumentRepository;
import com.e_commerce.repository.ProductRepository;
import com.e_commerce.repository.ProductRevisionRepository;
import com.e_commerce.repository.SupplierRepository;
import com.e_commerce.repository.ImportLogRepository;
import org.springframework.data.domain.PageRequest;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.bean.CsvToBeanBuilder;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /** Indice colonna Excel CS (97ª colonna, 0-based=96), usata spesso per disponibilità. */
    private static final int COLONNA_CS_INDEX = 96;
    /** Indice colonna CS in file con molte colonne – colonna AG (33ª colonna Excel, 0-based=32). */
    private static final int COLONNA_CS_ALTERNATIVA = 32;
    /** Prima riga dati in questo formato – riga 15 Excel (0-based=14). */
    private static final int FIRST_DATA_ROW_ALTERNATIVA = 14;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final DocumentRepository documentRepository;
    private final SupplierRepository supplierRepository;
    private final ImportLogRepository importLogRepository;
    private final ProductRevisionRepository productRevisionRepository;
    private final ProductService productService;
    private final ProductMatchingService productMatchingService;
    private final IcecatService icecatService;

    /** Flag per annullare in modo cooperativo gli import lunghi. */
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    /**
     * Esegue in background la sync Icecat dopo l'applicazione import, per evitare che la richiesta HTTP resti appesa.
     * Disattivabile via property `import.icecat.sync-after-apply=false`.
     */
    @org.springframework.beans.factory.annotation.Value("${import.icecat.sync-after-apply:true}")
    private boolean syncIcecatAfterApply;

    public ImportService(ProductRepository productRepository,
                         CategoryRepository categoryRepository,
                         DocumentRepository documentRepository,
                         SupplierRepository supplierRepository,
                         ImportLogRepository importLogRepository,
                         ProductRevisionRepository productRevisionRepository,
                         ProductService productService,
                         ProductMatchingService productMatchingService,
                         IcecatService icecatService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.documentRepository = documentRepository;
        this.supplierRepository = supplierRepository;
        this.importLogRepository = importLogRepository;
        this.productRevisionRepository = productRevisionRepository;
        this.productService = productService;
        this.productMatchingService = productMatchingService;
        this.icecatService = icecatService;
    }

    /** Chiamato quando l'utente preme "Annulla" dal frontend. */
    public void requestCancel() {
        cancelRequested.set(true);
    }

    private void resetCancel() {
        cancelRequested.set(false);
    }

    private void checkCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Operazione di import annullata dall'utente.");
        }
    }

    public void importProducts(MultipartFile file, Long supplierId) throws Exception {
        resetCancel();
        importProductsFromBytes(
                file.getBytes(),
                file.getOriginalFilename(),
                file.getContentType(),
                supplierId,
                true
        );
    }

    public void importProductsFromImportLog(ImportLog log) throws Exception {
        if (log == null || log.getFileContent() == null || log.getFileContent().length == 0) {
            throw new IllegalArgumentException("File CSV non disponibile nello storico import.");
        }
        Long supplierId = log.getSupplier() != null ? log.getSupplier().getId() : null;
        resetCancel();
        importProductsFromBytes(
                log.getFileContent(),
                log.getFileName(),
                log.getFileContentType(),
                supplierId,
                false
        );
    }

    /**
     * Applica l'import al catalogo salvando uno snapshot dello stato precedente.
     * Permette il rollback senza resettare tutto il catalogo.
     * Transazione nel servizio per evitare "rollback-only" quando il controller cattura eccezioni.
     */
    @Transactional(noRollbackFor = CancellationException.class)
    public void applyImportWithSnapshot(ImportLog log) throws Exception {
        if (log == null || log.getFileContent() == null || log.getFileContent().length == 0) {
            throw new IllegalArgumentException("File non disponibile nello storico import.");
        }
        if (log.getSupplier() == null) {
            throw new IllegalArgumentException("Import senza fornitore associato.");
        }
        resetCancel();
        List<ProductImportDTO> rows = parseProductRowsFromBytes(
                log.getFileContent(),
                log.getFileName(),
                log.getFileContentType()
        );
        Map<String, Boolean> autoOfferByKey = computeAutoOfferFlagsFromPreviousImport(log, rows);
        Map<String, ProductSnapshotDTO> snapshot = new HashMap<>();
        for (ProductImportDTO dto : rows) {
            checkCancelled();
            String sku = normalize(dto.getSku());
            if (sku == null) continue;
            String skuTruncated = truncate(sku, 255);
            productMatchingService.findProductBySkuOnly(skuTruncated).getProduct().ifPresent(p -> snapshot.put(skuTruncated, toSnapshot(p)));
        }
        processProductRows(rows, log.getSupplier(), log.getFileName(), autoOfferByKey);
        ObjectMapper mapper = new ObjectMapper();
        log.setPreviousStateJson(mapper.writeValueAsString(snapshot));
        log.setAppliedAt(LocalDateTime.now());
        importLogRepository.save(log);

        // Sincronizza automaticamente le immagini Icecat per i prodotti appena importati.
        // IMPORTANTE: lo facciamo in background per non bloccare la risposta HTTP quando il file ha molte righe.
        if (syncIcecatAfterApply) {
            CompletableFuture.runAsync(() -> {
                try {
                    for (ProductImportDTO dto : rows) {
                        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                            throw new CancellationException("Sync Icecat annullata dall'utente.");
                        }
                        String sku = normalize(dto.getSku());
                        if (sku == null) continue;
                        String skuTruncated = truncate(sku, 255);
                        productMatchingService.findProductBySkuOnly(skuTruncated).getProduct().ifPresent(p -> {
                            try {
                                Product full = productRepository.findByIdWithAssociations(p.getId()).orElse(p);
                                if (isManualLocked(full)) return;
                                int added = icecatService.syncImagesForProduct(p.getId());
                                if (added > 0) {
                                    ImportService.log.info("Import catalogo: Icecat ha aggiunto {} immagini per prodotto {} (SKU: {})", added, p.getId(), skuTruncated);
                                }
                            } catch (Exception e) {
                                ImportService.log.warn("Import catalogo: sync Icecat fallito per prodotto {} (SKU: {}): {}", p.getId(), skuTruncated, e.getMessage());
                            }
                        });
                    }
                } catch (CancellationException ce) {
                    ImportService.log.info("Import catalogo: sync Icecat interrotta: {}", ce.getMessage());
                } catch (Exception e) {
                    ImportService.log.warn("Import catalogo: sync Icecat background fallita: {}", e.getMessage());
                }
            });
        } else {
            ImportService.log.info("Import catalogo: sync Icecat post-apply disattivata (import.icecat.sync-after-apply=false)");
        }
    }

    /**
     * Annulla l'ultimo import: ripristina i prodotti modificati ed elimina quelli creati nel catalogo virtuale.
     * Il file nella cartella fornitori (ImportLog) non viene eliminato: resta disponibile per eventuale ri-import.
     */
    public void rollbackImport(ImportLog log) throws Exception {
        if (log == null || log.getPreviousStateJson() == null || log.getPreviousStateJson().isBlank()) {
            throw new IllegalArgumentException("Impossibile fare rollback: nessuno snapshot disponibile per questo import.");
        }
        resetCancel();

        // I prodotti manuali creati dall'utente devono essere preservati anche in rollback.
        // Usiamo il flag invece della categoria, perché la categoria "vera" deve rimanere quella scelta.
        List<ProductImportDTO> rows = parseProductRowsFromBytes(
                log.getFileContent(),
                log.getFileName(),
                log.getFileContentType()
        );
        ObjectMapper mapper = new ObjectMapper();
        Map<String, ProductSnapshotDTO> snapshot = mapper.readValue(
                log.getPreviousStateJson(),
                new TypeReference<Map<String, ProductSnapshotDTO>>() {}
        );
        for (ProductImportDTO dto : rows) {
            checkCancelled();
            String sku = normalize(dto.getSku());
            if (sku == null) continue;
            String skuTruncated = truncate(sku, 255);
            ProductSnapshotDTO snap = snapshot.get(skuTruncated);
            if (snap != null) {
                restoreFromSnapshot(snap, log, mapper);
            } else {
                productMatchingService.findProductBySkuOnly(skuTruncated).getProduct().ifPresent(p -> {
                    // Nessuno snapshot: significa prodotto creato dall'import nel "catalogo virtuale".
                    // Però se l'utente ha creato manualmente lo stesso SKU, lo preserviamo.
                    boolean isManualNew = Boolean.TRUE.equals(p.getNuovoManuale());
                    if (isManualNew) return;

                    // Se l'utente ha comunque salvato immagini manuali sul prodotto,
                    // non lo eliminiamo durante il rollback dell'import.
                    Product full = productRepository.findByIdWithAssociations(p.getId()).orElse(p);
                    if (hasManualImages(full)) return;

                    productService.deleteById(p.getId());
                });
            }
        }
        // Non eliminare l'ImportLog: il file resta nella cartella fornitori. Solo annulliamo lo stato "applicato".
        log.setAppliedAt(null);
        log.setPreviousStateJson(null);
        importLogRepository.save(log);
    }

    private ProductSnapshotDTO toSnapshot(Product p) {
        ProductSnapshotDTO dto = new ProductSnapshotDTO();
        dto.setId(p.getId());
        dto.setSku(p.getSku());
        dto.setEan(p.getEan());
        dto.setNome(p.getNome());
        dto.setDescrizione(p.getDescrizione());
        dto.setPrezzoBase(p.getPrezzoBase());
        dto.setPrezzoOfferta(p.getPrezzoOfferta());
        dto.setPrezzoFinale(p.getPrezzoFinale());
        dto.setInOfferta(p.getInOfferta());
        dto.setAumentoPercentuale(p.getAumentoPercentuale());
        dto.setCategoriaId(p.getCategoria() != null ? p.getCategoria().getId() : null);
        dto.setSupplierId(p.getFornitore() != null ? p.getFornitore().getId() : null);
        dto.setContati(p.getContati());
        dto.setDisponibilita(p.getDisponibilita());
        return dto;
    }

    private void restoreFromSnapshot(ProductSnapshotDTO snap, ImportLog importLog, ObjectMapper mapper) {
        Product product = productRepository.findById(snap.getId()).orElse(null);
        if (product == null) return;

        product.setEan(snap.getEan());
        product.setNome(snap.getNome());

        // Descrizione: la ripristiniamo dallo snapshot solo se l'utente NON l'ha modificata manualmente
        // dopo l'ora in cui l'import è stato applicato.
        boolean descrizioneEditedAfterImport = hasDescrizioneChangedAfterImport(product.getId(), importLog, product, mapper);
        if (!descrizioneEditedAfterImport) {
            product.setDescrizione(snap.getDescrizione());
        }

        product.setPrezzoBase(snap.getPrezzoBase());
        product.setPrezzoOfferta(snap.getPrezzoOfferta());
        product.setPrezzoFinale(snap.getPrezzoFinale());
        product.setInOfferta(Boolean.TRUE.equals(snap.getInOfferta()));
        product.setAumentoPercentuale(snap.getAumentoPercentuale());
        product.setContati(snap.getContati());
        product.setDisponibilita(snap.getDisponibilita());
        if (snap.getCategoriaId() != null) {
            categoryRepository.findById(snap.getCategoriaId()).ifPresent(product::setCategoria);
        } else {
            product.setCategoria(null);
        }
        if (snap.getSupplierId() != null) {
            supplierRepository.findById(snap.getSupplierId()).ifPresent(product::setFornitore);
        } else {
            product.setFornitore(null);
        }
        productService.save(product);
    }

    private boolean hasDescrizioneChangedAfterImport(Long productId, ImportLog importLog, Product currentProduct, ObjectMapper mapper) {
        if (importLog == null || importLog.getAppliedAt() == null) return false;
        if (productId == null) return false;

        LocalDateTime appliedAt = importLog.getAppliedAt();

        // Le revisioni sono ordinate decrescente per createdAt, quindi interrompiamo quando arriviamo al limite.
        List<ProductRevision> revisions = productRevisionRepository.findByProductIdOrderByCreatedAtDesc(
                productId, PageRequest.of(0, 50));

        for (ProductRevision rev : revisions) {
            if (rev == null || rev.getCreatedAt() == null) continue;
            if (!rev.getCreatedAt().isAfter(appliedAt)) {
                // questa e tutte le successive (più vecchie) non sono dopo l'import
                break;
            }
            try {
                if (rev.getSnapshotJson() == null) return true;
                ProductRevisionDTO revSnap = mapper.readValue(rev.getSnapshotJson(), ProductRevisionDTO.class);
                // Se la descrizione attuale NON coincide con quella "prima dell'update" salvata nella revisione,
                // significa che la descrizione è cambiata a valle dell'import.
                if (!Objects.equals(currentProduct.getDescrizione(), revSnap.getDescrizione())) {
                    return true;
                }
            } catch (Exception e) {
                // Se non possiamo interpretare la revisione, preferiamo preservare la descrizione corrente.
                return true;
            }
        }

        return false;
    }

    private boolean hasManualImages(Product product) {
        if (product == null || product.getDocumenti() == null) return false;
        return product.getDocumenti().stream().anyMatch(d -> {
            if (d == null) return false;
            if (d.getTipo() != null && "immagine_manual".equalsIgnoreCase(d.getTipo())) return true;
            String url = d.getUrl();
            return url != null && url.contains("/manual_");
        });
    }

    /**
     * Prodotto bloccato da modifiche import automatiche:
     * - ha immagini manuali, oppure
     * - ha almeno una revisione (modifica manuale salvata dall'utente).
     */
    private boolean isManualLocked(Product product) {
        if (product == null || product.getId() == null) return false;
        if (hasManualImages(product)) return true;
        return productRevisionRepository.existsByProductId(product.getId());
    }

    /**
     * Se esiste un prodotto soft-deleted con stesso SKU/EAN, lo riattiva e lo riusa.
     * Evita violazioni di unique(sku) quando si re-importa un articolo precedentemente soft-deleted.
     */
    private Optional<Product> reviveSoftDeletedProduct(String sku, String ean) {
        Optional<Product> bySku = sku != null ? productRepository.findBySkuIncludingDeleted(sku) : Optional.empty();
        if (bySku.isPresent() && Boolean.TRUE.equals(bySku.get().getDeleted())) {
            Product p = bySku.get();
            p.setDeleted(false);
            p.setDeletedAt(null);
            return Optional.of(productRepository.save(p));
        }
        Optional<Product> byEan = ean != null ? productRepository.findByEanIncludingDeleted(ean) : Optional.empty();
        if (byEan.isPresent() && Boolean.TRUE.equals(byEan.get().getDeleted())) {
            Product p = byEan.get();
            p.setDeleted(false);
            p.setDeletedAt(null);
            return Optional.of(productRepository.save(p));
        }
        return Optional.empty();
    }

    private List<ProductImportDTO> parseProductRowsFromBytes(byte[] bytes, String filename, String contentType) throws Exception {
        if (isExcelFile(filename, contentType)) {
            return parseProductsXlsxOrXlsBytes(bytes, "Prodotti (Excel): header atteso almeno: sku,nome_prodotto,categoria,prezzo.");
        }
        if (isExcelXmlFile(filename, contentType)) {
            return parseProductsSpreadsheetMlBytes(bytes, "Prodotti (Excel XML): header atteso almeno: sku,nome_prodotto,categoria,prezzo.");
        }
        return parseCsvBytes(bytes, ProductImportDTO.class, "Prodotti (CSV): header atteso almeno: sku,nome_prodotto,categoria.");
    }

    /**
     * Se il nome del file (senza estensione) coincide con una categoria esistente (match ignorando maiuscole/minuscole),
     * quella categoria viene usata per TUTTI i prodotti dell'import.
     *
     * Eccezione/alias: la categoria seed è `Best sellers` (plurale), quindi accetta anche file `best seller`.
     * Se nessuna categoria corrisponde, ritorna empty e si usa la categoria per riga (colonna/mapping).
     */
    private Optional<Category> resolveCategoryFromFilename(String filename) {
        if (filename == null || filename.isBlank()) return Optional.empty();
        int lastDot = filename.lastIndexOf('.');
        String baseName = lastDot > 0 ? filename.substring(0, lastDot).trim() : filename.trim();
        if (baseName.isEmpty()) return Optional.empty();

        // Normalizza per alias "best seller" -> "Best sellers"
        String normalized = baseName
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        // startsWith per gestire nomi tipo "best seller_2024.csv" o "Best sellers - gennaio.xlsx"
        String canonicalName = normalized.startsWith("best seller") ? "Best sellers" : baseName;

        // Prima match esatto, poi ignore case
        return categoryRepository.findByNome(canonicalName)
                .or(() -> categoryRepository.findByNomeIgnoreCase(canonicalName));
    }

    private void processProductRows(List<ProductImportDTO> rows, Supplier supplier, String filename, Map<String, Boolean> autoOfferByKey) throws Exception {
        Optional<Category> categoryFromFilename = resolveCategoryFromFilename(filename);
        boolean promotionImport = isPromotionFileName(filename);
        for (ProductImportDTO dto : rows) {
            checkCancelled();
            String sku = normalize(dto.getSku());
            if (sku == null) continue;
            sku = truncate(sku, 255);
            final String skuFinal = sku;
            final String eanFinal = normalize(dto.getEan());
            String nomeProdotto = normalize(dto.getNome());
            if (nomeProdotto == null) nomeProdotto = sku;
            nomeProdotto = truncate(nomeProdotto, 255);
            Category category;
            if (categoryFromFilename.isPresent()) {
                category = categoryFromFilename.get();
            } else {
                String rawNomeCategoria = normalize(dto.getNomeCategoria());
                String nomeCategoria;
                if (rawNomeCategoria != null && !rawNomeCategoria.isBlank()
                        && categoryRepository.findByNome(rawNomeCategoria).isPresent()) {
                    nomeCategoria = rawNomeCategoria;
                } else {
                    String mappedCategoria = mapToMainCategory(rawNomeCategoria, nomeProdotto);
                    nomeCategoria = mappedCategoria != null ? mappedCategoria : "Accessori";
                }
                category = categoryRepository.findByNome(nomeCategoria)
                        .orElseGet(() -> {
                            Category c = new Category();
                            c.setNome(nomeCategoria);
                            return categoryRepository.save(c);
                        });
            }
            Product product = productMatchingService.findProductBySkuOnly(skuFinal).getProduct()
                    .or(() -> reviveSoftDeletedProduct(skuFinal, eanFinal))
                    .orElseGet(Product::new);
            boolean isNewProduct = product.getId() == null;
            if (!isNewProduct) {
                // Ricarica il prodotto completo (con associazioni) per valutare eventuali blocchi manuali.
                product = productRepository.findByIdWithAssociations(product.getId()).orElse(product);
            }
            String originalDescrizione = isNewProduct ? null : product.getDescrizione();
            Long originalCategoriaId = (!isNewProduct && product.getCategoria() != null) ? product.getCategoria().getId() : null;

            // Sempre: manteniamo SKU coerente
            product.setSku(skuFinal);

            if (isNewProduct) {
                // Per i prodotti nuovi importiamo tutti i dati dal CSV
                product.setNome(nomeProdotto);
                BigDecimal importedPrice = dto.getPrezzoBase() != null ? BigDecimal.valueOf(dto.getPrezzoBase()) : null;
                if (promotionImport) {
                    // Per i nuovi prodotti in un CSV "offerte" usiamo il confronto (prezzo corrente < import precedente)
                    // e NON includiamo a prescindere quelli col prezzo più alto.
                    product.setPrezzoOfferta(importedPrice);
                    Boolean autoOffer = resolveAutoOfferFlag(autoOfferByKey, dto, skuFinal, eanFinal, nomeProdotto);
                    boolean inOffer = Boolean.TRUE.equals(autoOffer);
                    product.setInOfferta(inOffer);
                    if (!inOffer) {
                        product.setPrezzoOfferta(null);
                    }
                } else {
                    product.setPrezzoBase(importedPrice != null ? importedPrice : BigDecimal.ZERO);
                }

                // Categoria: assegna quella dell'import solo per prodotti nuovi o senza categoria.
                if (!promotionImport && product.getCategoria() == null) {
                    product.setCategoria(category);
                }

                product.setFornitore(supplier);
                String eanVal = normalize(dto.getEan());
                product.setEan(eanVal != null ? truncate(eanVal, 32) : skuFinal);
                product.setMarca(truncate(normalize(dto.getMarca()), 128));
                product.setCodiceProduttore(truncate(normalize(dto.getCodiceProduttore()), 64));
                String descrizioneVal = normalize(dto.getDescrizione());
                if (descrizioneVal != null && !descrizioneVal.isBlank()) {
                    product.setDescrizione(descrizioneVal);
                }
            } else {
                // Prodotto già esistente: dal CSV aggiorniamo solo disponibilità CS e prezzo base.
                // Tutti gli altri campi (nome, categoria, descrizione, marca, codiceProduttore, fornitore, ecc.)
                // restano come nel database.
                if (dto.getPrezzoBase() != null) {
                    BigDecimal importedPrice = BigDecimal.valueOf(dto.getPrezzoBase());
                    if (promotionImport) {
                        // Import promozionale: in offerta solo se prezzo promo < prezzo base attuale.
                        applyPromotionPrice(product, importedPrice, false);
                    } else {
                        product.setPrezzoBase(importedPrice);
                    }
                }
                // Assicuriamoci comunque di non perdere una descrizione già presente.
                if (originalDescrizione != null && (product.getDescrizione() == null || product.getDescrizione().isBlank())) {
                    product.setDescrizione(originalDescrizione);
                }
            }
            // In ogni caso aggiorniamo la disponibilità CS dal CSV.
            product.setDisponibilita(truncate(normalize(dto.getDisponibilita()), 64));
            if (!promotionImport) {
                Boolean autoOffer = resolveAutoOfferFlag(autoOfferByKey, dto, skuFinal, eanFinal, nomeProdotto);
                if (autoOffer != null) {
                    product.setInOfferta(Boolean.TRUE.equals(autoOffer));
                }
            }
            // Hard guard: su prodotto esistente NON cambiare categoria durante import.
            if (!isNewProduct) {
                if (originalCategoriaId != null) {
                    categoryRepository.findById(originalCategoriaId).ifPresent(product::setCategoria);
                } else {
                    product.setCategoria(null);
                }
            }
            productService.save(product);
        }
    }

    private void importProductsFromBytes(byte[] bytes,
                                        String originalFilename,
                                        String contentType,
                                        Long supplierId,
                                        boolean createImportLog) throws Exception {
        resetCancel();
        List<ProductImportDTO> rows;
        if (isExcelFile(originalFilename, contentType)) {
            rows = parseProductsXlsxOrXlsBytes(bytes, "Prodotti (Excel .xlsx/.xls): header atteso almeno: sku,nome_prodotto,categoria,prezzo.");
        } else if (isExcelXmlFile(originalFilename, contentType)) {
            rows = parseProductsSpreadsheetMlBytes(bytes, "Prodotti (Excel XML): header atteso almeno: sku,nome_prodotto,categoria,prezzo.");
        } else {
            rows = parseCsvBytes(bytes, ProductImportDTO.class, "Prodotti (CSV): header atteso almeno: sku,nome_prodotto,categoria (delimitatore ',', ';' o '|').");
        }

        Supplier supplier = null;
        if (supplierId != null) {
            supplier = supplierRepository.findById(supplierId)
                    .orElseThrow(() -> new IllegalArgumentException("Fornitore non trovato (supplierId=" + supplierId + ")."));
        }

        int totalRowsHint = rows != null ? rows.size() : 0;
        log.info("Import prodotti avviato: file='{}', supplierId={}, righe={}",
                originalFilename != null ? originalFilename : "sconosciuto",
                supplierId,
                totalRowsHint);

        // Se il nome del file (senza estensione) coincide con una categoria, tutti i prodotti vanno in quella categoria
        Optional<Category> categoryFromFilename = resolveCategoryFromFilename(originalFilename);
        boolean promotionImport = isPromotionFileName(originalFilename);
        if (categoryFromFilename.isPresent()) {
            log.info("Import prodotti: categoria forzata dal nome file '{}' -> tutti i prodotti in categoria '{}'",
                    originalFilename, categoryFromFilename.get().getNome());
        }
        Map<String, Boolean> autoOfferByKey = computeAutoOfferFlagsForSupplier(supplierId, null, rows);

        int rowNumber = 1; // 1-based rispetto alle righe dati (escluso header)
        for (ProductImportDTO dto : rows) {
            checkCancelled();
            String codiceRaw = normalize(dto.getSku());
            if (codiceRaw == null) {
                codiceRaw = normalize(dto.getEan());
            }
            if (codiceRaw == null) {
                rowNumber++;
                continue; // salta righe senza codice (righe vuote o dati incompleti)
            }

            String eanFromCol = normalize(dto.getEan());
            String digitsOnly = ProductMatchingService.normalizeEan(codiceRaw);
            boolean isCodiceEan = digitsOnly != null && digitsOnly.length() >= 8 && digitsOnly.length() <= 14;

            String sku;
            String ean;
            if (isCodiceEan) {
                // Codice è un EAN: usalo solo come EAN, SKU = "EAN-xxx" (mai uguali)
                ean = eanFromCol != null ? truncate(eanFromCol, 32) : truncate(codiceRaw, 255);
                sku = "EAN-" + ean;
                sku = truncate(sku, 255);
            } else {
                // Codice è uno SKU (alfanumerico): usalo come SKU, EAN solo da colonna
                sku = truncate(codiceRaw, 255);
                ean = eanFromCol != null ? truncate(eanFromCol, 32) : null;
            }

            // `Product.nome` è NOT NULL: se manca, usiamo EAN o SKU come fallback
            String nomeProdotto = normalize(dto.getNome());
            if (nomeProdotto == null) {
                nomeProdotto = (ean != null ? ean : sku);
            }
            nomeProdotto = truncate(nomeProdotto, 255);

            Category category;
            if (categoryFromFilename.isPresent()) {
                category = categoryFromFilename.get();
            } else {
                String rawNomeCategoria = normalize(dto.getNomeCategoria());
                String nomeCategoria;
                if (rawNomeCategoria != null && !rawNomeCategoria.isBlank()
                        && categoryRepository.findByNome(rawNomeCategoria).isPresent()) {
                    nomeCategoria = rawNomeCategoria;
                } else {
                    String mappedCategoria = mapToMainCategory(rawNomeCategoria, nomeProdotto);
                    nomeCategoria = mappedCategoria != null ? mappedCategoria : "Accessori";
                }
                category = categoryRepository
                        .findByNome(nomeCategoria)
                        .orElseGet(() -> {
                            Category c = new Category();
                            c.setNome(nomeCategoria);
                            return categoryRepository.save(c);
                        });
            }

            // Match: se codice era EAN, cerca per EAN; altrimenti per SKU
            final String skuFinal = sku;
            final String eanFinal = ean;
            Product product = (isCodiceEan
                    ? productMatchingService.findProduct(null, ean)
                    : productMatchingService.findProductBySku(sku))
                    .getProduct()
                    .or(() -> reviveSoftDeletedProduct(skuFinal, eanFinal))
                    .orElseGet(Product::new);
            boolean isNewProduct = product.getId() == null;
            if (!isNewProduct) {
                // Ricarica il prodotto completo dal repository per avere categoria, immagini e dati manuali aggiornati.
                product = productRepository.findByIdWithAssociations(product.getId()).orElse(product);
            }
            String originalDescrizione = isNewProduct ? null : product.getDescrizione();
            Long originalCategoriaId = (!isNewProduct && product.getCategoria() != null) ? product.getCategoria().getId() : null;

            // Sempre: manteniamo identificatori coerenti
            product.setSku(sku);
            product.setEan(ean);

            if (isNewProduct) {
                // Prodotto nuovo: importiamo tutti i dati dal CSV
                product.setNome(nomeProdotto);
                BigDecimal importedPrice = dto.getPrezzoBase() != null
                        ? BigDecimal.valueOf(dto.getPrezzoBase())
                        : null;
                if (promotionImport) {
                    product.setPrezzoOfferta(importedPrice);
                    Boolean autoOffer = resolveAutoOfferFlag(autoOfferByKey, dto, sku, ean, nomeProdotto);
                    boolean inOffer = Boolean.TRUE.equals(autoOffer);
                    product.setInOfferta(inOffer);
                    if (!inOffer) {
                        product.setPrezzoOfferta(null);
                    }
                } else {
                    product.setPrezzoBase(importedPrice != null ? importedPrice : BigDecimal.ZERO);
                }

                // Categoria solo se non presente (nuovo o senza categoria)
                if (!promotionImport && product.getCategoria() == null) {
                    product.setCategoria(category);
                }

                if (supplier != null) {
                    product.setFornitore(supplier);
                }

                product.setMarca(truncate(normalize(dto.getMarca()), 128));
                product.setCodiceProduttore(truncate(normalize(dto.getCodiceProduttore()), 64));
                String descrizioneVal = normalize(dto.getDescrizione());
                if (descrizioneVal != null && !descrizioneVal.isBlank()) {
                    product.setDescrizione(descrizioneVal);
                }
            } else {
                // Prodotto esistente: non tocchiamo nome, categoria, descrizione, marca, ecc.
                // Dal CSV aggiorniamo solo disponibilità CS e prezzo base.
                if (dto.getPrezzoBase() != null) {
                    BigDecimal importedPrice = BigDecimal.valueOf(dto.getPrezzoBase());
                    if (promotionImport) {
                        applyPromotionPrice(product, importedPrice, false);
                    } else {
                        product.setPrezzoBase(importedPrice);
                    }
                }
                // Se esisteva già una descrizione, assicuriamoci di non perderla.
                if (originalDescrizione != null && (product.getDescrizione() == null || product.getDescrizione().isBlank())) {
                    product.setDescrizione(originalDescrizione);
                }
            }

            // Aggiorna sempre la disponibilità CS dal CSV.
            product.setDisponibilita(truncate(normalize(dto.getDisponibilita()), 64));
            if (!promotionImport) {
                Boolean autoOffer = resolveAutoOfferFlag(autoOfferByKey, dto, sku, ean, nomeProdotto);
                if (autoOffer != null) {
                    product.setInOfferta(Boolean.TRUE.equals(autoOffer));
                }
            }
            // Hard guard: su prodotto esistente NON cambiare categoria durante import.
            if (!isNewProduct) {
                if (originalCategoriaId != null) {
                    categoryRepository.findById(originalCategoriaId).ifPresent(product::setCategoria);
                } else {
                    product.setCategoria(null);
                }
            }

            productService.save(product);
            rowNumber++;

            // Progress log ogni 200 righe (evita silenzio su import lunghi)
            if ((rowNumber - 1) % 200 == 0) {
                log.info("Import prodotti: progresso {}/{} (file='{}')",
                        (rowNumber - 1),
                        totalRowsHint,
                        originalFilename != null ? originalFilename : "sconosciuto");
            }
        }

        // Log dell'import prodotti per fornitore (se specificato)
        if (createImportLog && supplier != null) {
            ImportLog log = new ImportLog();
            log.setSupplier(supplier);
            log.setTipo("PRODOTTI");
            log.setFileName(originalFilename != null ? originalFilename : "sconosciuto");
            log.setFileContent(bytes);
            log.setFileContentType(contentType);
            log.setImportedAt(LocalDateTime.now());
            importLogRepository.save(log);
        }

        // Dopo ogni import nel catalogo virtuale, lancia (opzionale) la sync Icecat
        // per aggiornare immagini/descrizioni dei prodotti interessati.
        if (syncIcecatAfterApply) {
            List<ProductImportDTO> rowsForSync = rows;
            CompletableFuture.runAsync(() -> {
                try {
                    for (ProductImportDTO dto : rowsForSync) {
                        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                            throw new CancellationException("Sync Icecat annullata dall'utente.");
                        }
                        String sku = normalize(dto.getSku());
                        if (sku == null) continue;
                        String skuTruncated = truncate(sku, 255);
                        productMatchingService.findProductBySkuOnly(skuTruncated).getProduct().ifPresent(p -> {
                            try {
                                Product full = productRepository.findByIdWithAssociations(p.getId()).orElse(p);
                                if (isManualLocked(full)) return;
                                int added = icecatService.syncImagesForProduct(p.getId());
                                if (added > 0) {
                                    ImportService.log.info("Import catalogo (virtuale): Icecat ha aggiunto {} immagini per prodotto {} (SKU: {})", added, p.getId(), skuTruncated);
                                }
                            } catch (Exception e) {
                                ImportService.log.warn("Import catalogo (virtuale): sync Icecat fallito per prodotto {} (SKU: {}): {}", p.getId(), skuTruncated, e.getMessage());
                            }
                        });
                    }
                } catch (CancellationException ce) {
                    ImportService.log.info("Import catalogo (virtuale): sync Icecat interrotta: {}", ce.getMessage());
                } catch (Exception e) {
                    ImportService.log.warn("Import catalogo (virtuale): sync Icecat background fallita: {}", e.getMessage());
                }
            });
        }

        log.info("Import prodotti completato: file='{}', salvati={} righe, supplierId={}",
                originalFilename != null ? originalFilename : "sconosciuto",
                (rowNumber - 1),
                supplierId);
    }

    public void importDocuments(MultipartFile file, Long supplierId) throws Exception {
        List<DocumentImportDTO> rows = parseCsv(file, DocumentImportDTO.class, "Documenti: header atteso: sku (o ean),tipo_documento,url_documento (delimitatore ',' o ';').");

        Supplier supplier = null;
        if (supplierId != null) {
            supplier = supplierRepository.findById(supplierId)
                    .orElseThrow(() -> new IllegalArgumentException("Fornitore non trovato (supplierId=" + supplierId + ")."));
        }

        for (DocumentImportDTO dto : rows) {
            String sku = ProductMatchingService.normalizeIdentifier(dto.getSku());
            String ean = ProductMatchingService.normalizeEan(dto.getEan());
            if ((sku == null || sku.isBlank()) && (ean == null || ean.isBlank())) {
                continue;
            }
            String identifier = sku != null ? sku : ean;
            Product product = productMatchingService.findProductForDocument(sku, ean)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Prodotto non trovato per SKU/EAN: \"" + identifier + "\". "
                                    + "Verifica che il prodotto esista nel catalogo (match: SKU esatto, normalizzato, EAN)."));

            Document document = new Document();
            document.setTipo(normalize(dto.getTipoDocumento()));
            document.setUrl(normalize(dto.getUrlDocumento()));
            document.setProduct(product);

            documentRepository.save(document);
        }

        // Log dell'import documenti per fornitore (se specificato)
        if (supplier != null) {
            ImportLog log = new ImportLog();
            log.setSupplier(supplier);
            log.setTipo("DOCUMENTI");
            log.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "sconosciuto");
            try {
                log.setFileContent(file.getBytes());
            } catch (Exception ignored) {
                log.setFileContent(null);
            }
            log.setFileContentType(file.getContentType());
            log.setImportedAt(LocalDateTime.now());
            importLogRepository.save(log);
        }
    }

    public void importSuppliers(MultipartFile file) throws Exception {
        List<SupplierImportDTO> rows = parseCsv(file, SupplierImportDTO.class, "Fornitori: header atteso: nome,codice,email,telefono,note (delimitatore ',' o ';').");

        for (SupplierImportDTO dto : rows) {
            String nome = normalize(dto.getNome());
            if (nome == null) {
                continue; // skip righe vuote
            }
            Supplier supplier = supplierRepository.findByNome(nome)
                    .orElseGet(Supplier::new);
            supplier.setNome(nome);
            supplier.setCodice(normalize(dto.getCodice()));
            supplier.setEmail(normalize(dto.getEmail()));
            supplier.setTelefono(normalize(dto.getTelefono()));
            supplier.setNote(normalize(dto.getNote()));

            supplierRepository.save(supplier);
        }
    }

    private <T> List<T> parseCsv(MultipartFile file, Class<T> type, String hint) {
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) {
                throw new IllegalArgumentException("File CSV vuoto. " + hint);
            }

            String csv = new String(bytes, StandardCharsets.UTF_8);
            csv = stripBom(csv);
            csv = lstripBlankLines(csv);
            if (csv.isBlank()) {
                throw new IllegalArgumentException("File CSV vuoto. " + hint);
            }

            char separator = detectSeparator(csv);
            csv = normalizeHeaderLine(csv, separator);

            try (CSVReader csvReader = new CSVReaderBuilder(new StringReader(csv))
                    .withCSVParser(buildCsvParser(separator))
                    .build()) {
                return new CsvToBeanBuilder<T>(csvReader)
                        .withType(type)
                        .withIgnoreLeadingWhiteSpace(true)
                        .build()
                        .parse();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            String csv = safePreview(file);
            char separator = detectSeparator(csv);
            List<String> cols = extractHeaderColumns(normalizeHeaderLine(csv, separator), separator);
            throw new IllegalArgumentException(
                    "Impossibile leggere l'header del CSV (separatore rilevato: '" + separator + "'). " +
                            "Colonne viste = [" + String.join(", ", cols) + "]. " + hint,
                    e
            );
        }
    }

    private <T> List<T> parseCsvBytes(byte[] bytes, Class<T> type, String hint) {
        try {
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("File CSV vuoto. " + hint);
            }

            String csv = new String(bytes, StandardCharsets.UTF_8);
            csv = stripBom(csv);
            csv = lstripBlankLines(csv);
            if (csv.isBlank()) {
                throw new IllegalArgumentException("File CSV vuoto. " + hint);
            }

            char separator = detectSeparator(csv);
            if (separator == '|' && ProductImportDTO.class.equals(type)) {
                csv = prependSyntheticPipeProductHeaderIfNeeded(csv);
            }
            csv = normalizeHeaderLine(csv, separator);

            try (CSVReader csvReader = new CSVReaderBuilder(new StringReader(csv))
                    .withCSVParser(buildCsvParser(separator))
                    .build()) {
                return new CsvToBeanBuilder<T>(csvReader)
                        .withType(type)
                        .withIgnoreLeadingWhiteSpace(true)
                        .build()
                        .parse();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            String csv = bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
            csv = stripBom(csv);
            csv = lstripBlankLines(csv);
            char separator = detectSeparator(csv);
            List<String> cols = extractHeaderColumns(normalizeHeaderLine(csv, separator), separator);
            throw new IllegalArgumentException(
                    "Impossibile leggere l'header del CSV (separatore rilevato: '" + separator + "'). " +
                            "Colonne viste = [" + String.join(", ", cols) + "]. " + hint,
                    e
            );
        }
    }

    private com.opencsv.CSVParser buildCsvParser(char separator) {
        CSVParserBuilder builder = new CSVParserBuilder().withSeparator(separator);
        // Alcuni listini pipe-delimited contengono apici doppi non escapati (es. 10", 65")
        // che rompono il parser standard CSV. In modalità pipe, disabilitiamo il quote parsing.
        if (separator == '|') {
            builder = builder.withQuoteChar('\0');
        }
        return builder.build();
    }

    private String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    private String lstripBlankLines(String s) {
        int i = 0;
        while (i < s.length()) {
            int lineEnd = s.indexOf('\n', i);
            if (lineEnd == -1) lineEnd = s.length();
            String line = s.substring(i, lineEnd).trim();
            if (!line.isEmpty()) {
                return s.substring(i);
            }
            i = Math.min(lineEnd + 1, s.length());
        }
        return "";
    }

    private char detectSeparator(String csv) {
        String firstLine = csv.split("\\R", 2)[0];
        int semicolons = countChar(firstLine, ';');
        int commas = countChar(firstLine, ',');
        int tabs = countChar(firstLine, '\t');
        int pipes = countChar(firstLine, '|');

        int max = Math.max(Math.max(semicolons, commas), Math.max(tabs, pipes));
        if (max == 0) {
            return ','; // fallback
        }
        if (max == semicolons) return ';';
        if (max == commas) return ',';
        if (max == tabs) return '\t';
        return '|';
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    /**
     * Listini pipe-delimited senza riga header: la prima riga è già un prodotto (codice|categoria|marca|nome|prezzo|...).
     * Senza questa patch la prima riga viene trattata come intestazione e sku/nome/prezzo restano sempre null → righe saltate.
     */
    private String prependSyntheticPipeProductHeaderIfNeeded(String csv) {
        String[] split = csv.split("\\R", 2);
        String firstLine = split[0];
        String rest = split.length > 1 ? split[1] : "";
        if (firstLine == null || firstLine.isBlank()) {
            return csv;
        }
        if (csvFirstLineLooksLikeProductHeader(firstLine, '|')) {
            return csv;
        }
        String[] cols = firstLine.split(Pattern.quote("|"), -1);
        int n = cols.length;
        if (n < 5) {
            return csv;
        }
        String headerRow = buildSyntheticPipeProductHeaderRow(n);
        log.info("Import prodotti: CSV pipe senza header standard — aggiunta intestazione sintetica ({} colonne).", n);
        if (rest.isEmpty()) {
            return headerRow + "\n" + firstLine;
        }
        return headerRow + "\n" + firstLine + "\n" + rest;
    }

    private String buildSyntheticPipeProductHeaderRow(int colCount) {
        String[] h = new String[colCount];
        h[0] = "sku";
        h[1] = "categoria";
        h[2] = "marca";
        h[3] = "nome_prodotto";
        h[4] = "prezzo";
        for (int i = 5; i < colCount; i++) {
            if (i == 7) {
                h[i] = "disponibilita";
            } else if (colCount >= 10 && i == colCount - 2) {
                h[i] = "codice_produttore";
            } else if (colCount >= 10 && i == colCount - 1) {
                h[i] = "__ignored_end";
            } else {
                h[i] = "__ignored_" + i;
            }
        }
        return String.join("|", h);
    }

    /**
     * True se almeno una colonna della prima riga, dopo alias, corrisponde a campi attesi dall'import prodotti.
     */
    private boolean csvFirstLineLooksLikeProductHeader(String firstLine, char sep) {
        String[] cols = firstLine.split(Pattern.quote(String.valueOf(sep)), -1);
        for (String raw : cols) {
            String col = normalizeHeaderCellForAlias(raw);
            String mapped = aliasHeader(col);
            if ("sku".equals(mapped) || "ean".equals(mapped) || "nome_prodotto".equals(mapped)
                    || "categoria".equals(mapped) || "prezzo".equals(mapped)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeHeaderCellForAlias(String col) {
        if (col == null) return "";
        col = stripBom(col).trim();
        if (col.startsWith("\"") && col.endsWith("\"") && col.length() >= 2) {
            col = col.substring(1, col.length() - 1);
        }
        col = col.trim().toLowerCase(Locale.ROOT);
        col = col.replaceAll("\\s+", "_");
        return col;
    }

    private String normalizeHeaderLine(String csv, char separator) {
        String[] parts = csv.split("\\R", 2);
        String header = parts[0];
        String rest = parts.length > 1 ? parts[1] : "";

        StringBuilder normalized = new StringBuilder();
        String[] cols = header.split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < cols.length; i++) {
            String col = cols[i];
            col = stripBom(col);
            col = col.trim();
            if (col.startsWith("\"") && col.endsWith("\"") && col.length() >= 2) {
                col = col.substring(1, col.length() - 1);
            }
            col = col.trim().toLowerCase();
            col = col.replaceAll("\\s+", "_");
            String mapped = aliasHeader(col);
            if (mapped != null && !mapped.isBlank()) {
                if (seen.contains(mapped)) {
                    mapped = "__ignored_" + i;
                } else {
                    seen.add(mapped);
                }
            }
            normalized.append(mapped != null ? mapped : "");
            if (i < cols.length - 1) {
                normalized.append(separator);
            }
        }
        normalized.append("\n");
        normalized.append(rest);
        return normalized.toString();
    }

    private List<String> extractHeaderColumns(String csv, char separator) {
        if (csv == null || csv.isBlank()) return List.of();
        String header = csv.split("\\R", 2)[0];
        String[] cols = header.split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
        return Arrays.stream(cols)
                .map(this::stripBom)
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    private String safePreview(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) return "";
            String csv = new String(bytes, StandardCharsets.UTF_8);
            csv = stripBom(csv);
            csv = lstripBlankLines(csv);
            return csv;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String aliasHeader(String col) {
        if (col == null) return null;
        col = col.replace("\u00A0", " ").replaceAll("\\s+", " ").trim();
        col = col.replaceAll("\\p{M}", "");
        if (col.equals("cs") || col.equals("c.s") || col.equals("c.s.") || col.matches("cs[_(\\(\\-].*")) {
            return "disponibilita";
        }
        if (col.contains("disponibil") || col.contains("stock") || col.contains("giacenza") || col.contains("availability")) {
            return "disponibilita";
        }
        return switch (col) {
            // sku (codice interno; barcode va in ean quando c'è colonna sku separata)
            case "codice", "code", "product_code", "productcode", "sku_prodotto",
                    "codice_articolo", "codicearticolo", "art_code", "item_code",
                    "cod_articolo", "cod_art", "articolo_cod", "ref", "reference" -> "sku";
            // ean / barcode (per Icecat; barcode = codice a barre = EAN)
            case "ean", "gtin", "ean13", "ean_upc", "barcode" -> "ean";

            // prodotti - nome
            case "nome" -> "nome_prodotto";
            case "nomeprodotto", "nome_del_prodotto", "prodotto", "articolo",
                    "titolo", "title", "denominazione" -> "nome_prodotto";
            case "name", "product_name", "productname", "product", "item_name" -> "nome_prodotto";

            // prodotti - descrizione (testo lungo; non confondere con nome)
            case "descrizione", "description", "long_description", "desc", "product_description", "testo" -> "descrizione";

            // prodotti - prezzo (CON/contanti/contati = prezzo in contanti dal fornitore -> prezzo base)
            case "prezzo_base", "prezzo_listino", "prezzobase", "prezzo_di_listino",
                    "price", "prezzo_unitario", "prezzo_unit", "listino",
                    "prezzo_netto", "prezzonetto", "prezzo_vendita", "prezzo_vendita_netto",
                    "pv", "p.v.", "p_v", "costo", "prezzo_acq", "prezzo_acquisto",
                    "eur", "euro", "prezzo_eur", "contanti", "contati", "con" -> "prezzo";

            // prodotti - categoria
            case "category", "cat", "nome_categoria", "categoria_nome", "categories", "category_name",
                    "categoria_prodotto", "macrocategoria", "tipologia", "famiglia" -> "categoria";
            // marca / brand (per fallback Icecat prod_id+vendor)
            case "marca", "brand", "vendor", "fabricante", "manufacturer" -> "marca";
            case "codice_produttore", "codiceproduttore", "prod_id", "product_id", "cod_produttore",
                    "codice_prodotto", "codice_produttore_icecat" -> "codice_produttore";

            // prodotti - disponibilità (stessa logica del prezzo: molti alias per file diversi)
            case "cs", "c.s.", "c_s", "c.s", "cs.", "cs_cosenza", "cs_(cosenza)", "cs_(c)",
                    "disponibilita", "disponibilità", "disp", "disponibilità_cs", "disponibilita_cs",
                    "disponibilità_(cs)", "disp_cs", "cs_disp", "stock_cs", "giacenza", "availability",
                    "disponibile", "quantità", "quantita", "qty", "stock", "stock_disponibile",
                    "qtà", "quantità_disponibile", "pezzi", "magazzino", "in_stock" -> "disponibilita";

            // documenti
            case "tipo", "tipo_doc", "tipodocumento" -> "tipo_documento";
            case "url", "link", "urldocumento" -> "url_documento";

            // fornitori
            case "ragione_sociale" -> "nome";
            default -> col;
        };
    }

    /**
     * Tronca una stringa al numero massimo di caratteri indicato.
     * Utile per rispettare i limiti VARCHAR(n) del database.
     */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private boolean isPromotionFileName(String filename) {
        if (filename == null || filename.isBlank()) return false;
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.contains("promo")
                || lower.contains("promoz")
                || lower.contains("offert")
                || lower.contains("offer");
    }

    /**
     * Regola import promozionale:
     * - prodotto esistente: entra in offerta solo se prezzo promo < prezzo base corrente;
     * - prodotto nuovo: la marcatura in offerta dipende dal confronto (vedi chiamanti);
     */
    private void applyPromotionPrice(Product product, BigDecimal importedPrice, boolean isNewProduct) {
        if (product == null || importedPrice == null) return;
        if (isNewProduct) {
            // Decisione su `inOfferta` demandata al chiamante (serve confronto esterno).
            product.setPrezzoOfferta(importedPrice);
            product.setInOfferta(false);
            return;
        }
        BigDecimal base = product.getPrezzoBase();
        if (base != null && importedPrice.compareTo(base) < 0) {
            product.setPrezzoOfferta(importedPrice);
            product.setInOfferta(true);
        } else {
            product.setPrezzoOfferta(null);
            product.setInOfferta(false);
        }
    }

    private boolean isExcelFile(String originalFilename, String contentType) {
        String name = originalFilename != null ? originalFilename.toLowerCase() : "";
        String ct = contentType != null ? contentType.toLowerCase() : "";
        if (name.endsWith(".xml")) return false; // .xml -> Excel XML parser
        if (name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".xsl")) return true;
        if (ct.contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) return true;
        return ct.contains("application/vnd.ms-excel");
    }

    private boolean isExcelXmlFile(String originalFilename, String contentType) {
        String name = originalFilename != null ? originalFilename.toLowerCase() : "";
        String ct = contentType != null ? contentType.toLowerCase() : "";
        if (name.endsWith(".xml")) return true;
        return ct.contains("application/xml") || ct.contains("text/xml");
    }

    private List<ProductImportDTO> parseProductsXlsxOrXlsBytes(byte[] bytes, String hint) {
        try {
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("File Excel vuoto. " + hint);
            }

            try (Workbook workbook = WorkbookFactory.create(new BufferedInputStream(new ByteArrayInputStream(bytes)))) {
                if (workbook.getNumberOfSheets() <= 0) {
                    throw new IllegalArgumentException("File Excel senza fogli. " + hint);
                }
                Sheet sheet = workbook.getSheetAt(0);
                if (sheet == null) {
                    throw new IllegalArgumentException("Impossibile leggere il primo foglio Excel. " + hint);
                }

                DataFormatter formatter = new DataFormatter();
                int firstRowNum = sheet.getFirstRowNum();
                int lastRowNum = Math.min(sheet.getLastRowNum(), firstRowNum + 20);
                Row headerRow = null;
                Map<String, Integer> headerToIndex = new HashMap<>();

                int bestScore = 0;
                boolean usedSubHeader = false;
                for (int r = firstRowNum; r <= lastRowNum; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Map<String, Integer> candidate = new HashMap<>();
                    int lastCellNum = row.getLastCellNum();
                    for (int c = 0; c < lastCellNum; c++) {
                        Cell cell = row.getCell(c);
                        if (cell == null) continue;
                        String raw = formatter.formatCellValue(cell);
                        String col = normalizeExcelHeaderCell(raw);
                        if (col != null && !col.isBlank()) {
                            candidate.put(col, c);
                        }
                    }
                    // Unisce colonne dalla riga sotto (header su 2 righe: es. riga1=Category,Codice,CON, riga2=SA,CE,FG,CS)
                    Row nextRow = sheet.getRow(r + 1);
                    if (nextRow != null) {
                        for (int c = 0; c < nextRow.getLastCellNum(); c++) {
                            Cell cell = nextRow.getCell(c);
                            if (cell == null) continue;
                            String raw = formatter.formatCellValue(cell);
                            String col = normalizeExcelHeaderCell(raw);
                            if (col != null && !col.isBlank() && !candidate.containsKey(col)) {
                                candidate.put(col, c);
                            }
                        }
                    }
                    Integer skuIdx = candidate.get("sku");
                    Integer catIdx = candidate.get("categoria");
                    if (skuIdx == null || catIdx == null) continue;
                    // Salta righe che sembrano indirizzi (es. "Via N. Da Conti...")
                    String firstCell = row.getCell(0) != null ? formatter.formatCellValue(row.getCell(0)).trim() : "";
                    if (firstCell.length() > 60 && (firstCell.toLowerCase().contains("via ") || firstCell.contains("tel") || firstCell.contains("fax") || firstCell.contains("@") || firstCell.contains("c.da"))) {
                        continue;
                    }
                    int score = 1;
                    if (candidate.containsKey("nome_prodotto")) score++;
                    if (candidate.containsKey("prezzo")) score++;
                    if (candidate.containsKey("disponibilita")) score++;
                    if (score > bestScore) {
                        bestScore = score;
                        // Se la riga corrente è vuota/indirizzo e l'header viene dalla riga sotto (merge), usa la riga sotto
                        boolean currentRowHasHeader = skuIdx != null && row.getCell(skuIdx) != null
                                && "sku".equals(normalizeExcelHeaderCell(formatter.formatCellValue(row.getCell(skuIdx))));
                        headerRow = (nextRow != null && !currentRowHasHeader && nextRow.getCell(skuIdx) != null
                                && "sku".equals(normalizeExcelHeaderCell(formatter.formatCellValue(nextRow.getCell(skuIdx)))))
                                ? nextRow : row;
                        headerToIndex.clear();
                        headerToIndex.putAll(candidate);
                        Integer dispIdx = candidate.get("disponibilita");
                        usedSubHeader = dispIdx != null && dispIdx >= row.getLastCellNum();
                    }
                }

                if (headerRow == null) {
                    List<String> rawFirstRow = new java.util.ArrayList<>();
                    Row firstRow = sheet.getRow(firstRowNum);
                    if (firstRow != null) {
                        for (Cell cell : firstRow) {
                            rawFirstRow.add("'" + formatter.formatCellValue(cell) + "'");
                        }
                    }
                    String rawPreview = rawFirstRow.isEmpty()
                            ? "(prima riga vuota o senza celle)"
                            : String.join(", ", rawFirstRow);
                    throw new IllegalArgumentException(
                            "Header Excel non valido. La prima riga contiene: [" + rawPreview + "]. "
                            + "Serve una riga con colonne: sku (o codice/codice_articolo), categoria (o category), nome_prodotto, prezzo. " + hint
                    );
                }

                Integer skuIdx = headerToIndex.get("sku");
                Integer nomeIdx = headerToIndex.get("nome_prodotto");
                Integer catIdx = headerToIndex.get("categoria");
                Integer prezzoIdx = headerToIndex.get("prezzo");
                Integer disponibilitaIdx = headerToIndex.get("disponibilita");
                Integer eanIdx = headerToIndex.get("ean");
                Integer marcaIdx = headerToIndex.get("marca");
                Integer codiceProduttoreIdx = headerToIndex.get("codice_produttore");
                Integer descrizioneIdx = headerToIndex.get("descrizione");
                int firstDataRow = headerRow.getRowNum() + (usedSubHeader ? 2 : 1);
                // Controllo esplicito colonna 33 (formato con molte colonne: Category,Brand,Codice,...,CS,PZ)
                if (headerRow.getLastCellNum() > COLONNA_CS_ALTERNATIVA) {
                    Cell cell33 = headerRow.getCell(COLONNA_CS_ALTERNATIVA);
                    if (cell33 != null) {
                        String raw33 = formatter.formatCellValue(cell33);
                        String norm33 = raw33 != null ? raw33.trim().replaceAll("[\\s\\u00A0._-]+", "").replaceAll("\\p{M}", "") : "";
                        if (norm33.equalsIgnoreCase("cs") || "disponibilita".equals(normalizeExcelHeaderCell(raw33))) {
                            disponibilitaIdx = COLONNA_CS_ALTERNATIVA;
                            firstDataRow = FIRST_DATA_ROW_ALTERNATIVA; // dati da riga 15 Excel
                        }
                    }
                }
                if (disponibilitaIdx == null) {
                    // Cerca nella riga header e fino alla 35ª riga (0-based=34)
                    int maxScanRow = 34;
                    for (int scanR = headerRow.getRowNum(); scanR <= maxScanRow && disponibilitaIdx == null; scanR++) {
                        Row rowToScan = sheet.getRow(scanR);
                        if (rowToScan == null) continue;
                        for (int c = 0; c < rowToScan.getLastCellNum(); c++) {
                            Cell cell = rowToScan.getCell(c);
                            if (cell == null) continue;
                            String raw = formatter.formatCellValue(cell);
                            if (raw == null) continue;
                            String rawNorm = raw.trim().replaceAll("[\\s\\u00A0._-]+", "").replaceAll("\\p{M}", "");
                            String col = normalizeExcelHeaderCell(raw);
                            if ((col != null && col.equals("disponibilita")) || rawNorm.equalsIgnoreCase("cs")) {
                                disponibilitaIdx = c;
                                if (scanR > headerRow.getRowNum()) {
                                    firstDataRow = Math.max(firstDataRow, scanR + 1);
                                }
                                break;
                            }
                        }
                    }
                }
                if (disponibilitaIdx == null) {
                    // Fallback: usa colonna 33 (CS) se il file ha abbastanza colonne
                    int maxCol = headerRow.getLastCellNum() - 1;
                    if (maxCol >= COLONNA_CS_ALTERNATIVA) {
                        disponibilitaIdx = COLONNA_CS_ALTERNATIVA;
                        firstDataRow = Math.max(firstDataRow, FIRST_DATA_ROW_ALTERNATIVA);
                    } else if (maxCol >= COLONNA_CS_INDEX) {
                        disponibilitaIdx = COLONNA_CS_INDEX;
                    }
                }

                int lastRow = sheet.getLastRowNum();
                java.util.ArrayList<ProductImportDTO> rows = new java.util.ArrayList<>();
                for (int r = firstDataRow; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    // Riga tipo "-EAN:8057284620150" sulla riga sotto al prodotto: assegna EAN al prodotto precedente
                    String eanFromRow = extractEanFromRow(row, formatter);
                    if (eanFromRow != null) {
                        if (!rows.isEmpty()) {
                            rows.get(rows.size() - 1).setEan(eanFromRow);
                        }
                        continue;
                    }

                    String sku = readCellAsString(row, skuIdx, formatter);
                    String categoria = readCellAsString(row, catIdx, formatter);

                    // saltiamo righe vuote
                    if (sku == null && categoria == null) continue;

                    String nome = readCellAsString(row, nomeIdx, formatter);
                    Double prezzo = null;
                    if (prezzoIdx != null) {
                        prezzo = readNumericCellAsDouble(row.getCell(prezzoIdx), formatter);
                    }
                    String disponibilita = readCellAsString(row, disponibilitaIdx, formatter);
                    String ean = eanIdx != null ? readCellAsString(row, eanIdx, formatter) : null;
                    String marca = marcaIdx != null ? readCellAsString(row, marcaIdx, formatter) : null;
                    String codiceProduttore = codiceProduttoreIdx != null ? readCellAsString(row, codiceProduttoreIdx, formatter) : null;
                    String descrizione = descrizioneIdx != null ? readCellAsString(row, descrizioneIdx, formatter) : null;

                    ProductImportDTO dto = new ProductImportDTO();
                    dto.setSku(sku);
                    dto.setEan(ean);
                    dto.setNome(nome);
                    dto.setNomeCategoria(categoria);
                    dto.setPrezzoBase(prezzo);
                    dto.setDisponibilita(disponibilita);
                    dto.setMarca(marca);
                    dto.setCodiceProduttore(codiceProduttore);
                    dto.setDescrizione(descrizione);
                    rows.add(dto);
                }
                return rows;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Impossibile leggere il file Excel (.xlsx/.xls). " + hint, e);
        }
    }

    private List<ProductImportDTO> parseProductsSpreadsheetMlBytes(byte[] bytes, String hint) {
        try {
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("File XML vuoto. " + hint);
            }
            String xml = new String(bytes, StandardCharsets.UTF_8);
            xml = stripBom(xml);
            xml = stripExcelXmlProlog(xml);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setIgnoringElementContentWhitespace(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Element workbook = doc.getDocumentElement();
            if (workbook == null || !"Workbook".equals(workbook.getLocalName())) {
                throw new IllegalArgumentException("File XML non valido: elemento Workbook non trovato. " + hint);
            }

            NodeList worksheets = workbook.getElementsByTagNameNS("urn:schemas-microsoft-com:office:spreadsheet", "Worksheet");
            if (worksheets == null || worksheets.getLength() == 0) {
                worksheets = workbook.getElementsByTagName("Worksheet");
            }
            if (worksheets == null || worksheets.getLength() == 0) {
                throw new IllegalArgumentException("File XML senza fogli Worksheet. " + hint);
            }

            Element firstSheet = (Element) worksheets.item(0);
            NodeList tables = firstSheet.getElementsByTagNameNS("urn:schemas-microsoft-com:office:spreadsheet", "Table");
            if (tables == null || tables.getLength() == 0) {
                tables = firstSheet.getElementsByTagName("Table");
            }
            if (tables == null || tables.getLength() == 0) {
                throw new IllegalArgumentException("File XML senza Table. " + hint);
            }

            Element table = (Element) tables.item(0);
            NodeList rows = table.getElementsByTagNameNS("urn:schemas-microsoft-com:office:spreadsheet", "Row");
            if (rows == null || rows.getLength() == 0) {
                rows = table.getElementsByTagName("Row");
            }
            if (rows == null || rows.getLength() == 0) {
                throw new IllegalArgumentException("File XML senza righe. " + hint);
            }

            Map<String, Integer> headerToIndex = new HashMap<>();
            java.util.ArrayList<ProductImportDTO> result = new java.util.ArrayList<>();
            int headerRowIdx = -1;

            for (int i = 0; i < rows.getLength(); i++) {
                Element rowEl = (Element) rows.item(i);
                List<String> cellValues = extractRowCells(rowEl);
                if (headerRowIdx < 0) {
                    Map<String, Integer> candidate = new HashMap<>();
                    for (int c = 0; c < cellValues.size(); c++) {
                        String col = normalizeExcelHeaderCell(cellValues.get(c));
                        if (col != null && !col.isBlank()) candidate.put(col, c);
                    }
                    if (candidate.get("sku") == null || candidate.get("categoria") == null) {
                        String firstCell = cellValues.isEmpty() ? "" : (cellValues.get(0) != null ? cellValues.get(0).trim() : "");
                        if (firstCell.length() > 60 && (firstCell.toLowerCase().contains("via ") || firstCell.contains("@") || firstCell.contains("c.da"))) {
                            continue;
                        }
                        throw new IllegalArgumentException(
                                "Header XML non valido alla riga " + (i + 1) + ". Colonne viste = [" + String.join(", ", candidate.keySet()) + "]. " + hint
                        );
                    }
                    headerToIndex.putAll(candidate);
                    if (!headerToIndex.containsKey("disponibilita")) {
                        // Cerca "CS" nella riga header e nelle righe successive (header su più righe)
                        for (int scanR = i; scanR < Math.min(i + 35, rows.getLength()) && !headerToIndex.containsKey("disponibilita"); scanR++) {
                            Element scanRow = (Element) rows.item(scanR);
                            List<String> scanCells = extractRowCells(scanRow);
                            for (int c = 0; c < scanCells.size(); c++) {
                                String cellVal = scanCells.get(c);
                                if (cellVal == null) continue;
                                String norm = cellVal.trim().replaceAll("[\\s\\u00A0._-]+", "").replaceAll("\\p{M}", "");
                                String col = normalizeExcelHeaderCell(cellVal);
                                if ((col != null && col.equals("disponibilita")) || norm.equalsIgnoreCase("cs")) {
                                    headerToIndex.put("disponibilita", c);
                                    break;
                                }
                            }
                        }
                        if (!headerToIndex.containsKey("disponibilita") && cellValues.size() > COLONNA_CS_ALTERNATIVA) {
                            headerToIndex.put("disponibilita", COLONNA_CS_ALTERNATIVA);
                        } else if (!headerToIndex.containsKey("disponibilita") && cellValues.size() > COLONNA_CS_INDEX) {
                            headerToIndex.put("disponibilita", COLONNA_CS_INDEX);
                        }
                    }
                    headerRowIdx = i;
                    continue;
                }
                {
                    // Riga tipo "-EAN:8057284620150" sulla riga sotto al prodotto
                    String eanFromRow = null;
                    for (String cellVal : cellValues) {
                        eanFromRow = extractEanFromString(cellVal);
                        if (eanFromRow != null) break;
                    }
                    if (eanFromRow != null) {
                        if (!result.isEmpty()) result.get(result.size() - 1).setEan(eanFromRow);
                        continue;
                    }


                    Integer skuIdx = headerToIndex.get("sku");
                    Integer nomeIdx = headerToIndex.get("nome_prodotto");
                    Integer catIdx = headerToIndex.get("categoria");
                    Integer prezzoIdx = headerToIndex.get("prezzo");
                    Integer disponibilitaIdx = headerToIndex.get("disponibilita");
                    Integer eanIdx = headerToIndex.get("ean");
                    Integer marcaIdx = headerToIndex.get("marca");
                    Integer codiceProduttoreIdx = headerToIndex.get("codice_produttore");
                    Integer descrizioneIdx = headerToIndex.get("descrizione");

                    String sku = skuIdx != null && cellValues.size() > skuIdx ? normalize(cellValues.get(skuIdx)) : null;
                    String categoria = catIdx != null && cellValues.size() > catIdx ? normalize(cellValues.get(catIdx)) : null;
                    if (sku == null && categoria == null) continue;

                    String nome = nomeIdx != null && cellValues.size() > nomeIdx ? normalize(cellValues.get(nomeIdx)) : null;
                    Double prezzo = null;
                    if (prezzoIdx != null && cellValues.size() > prezzoIdx) {
                        prezzo = parsePriceFromString(normalize(cellValues.get(prezzoIdx)));
                    }
                    String disponibilita = disponibilitaIdx != null && cellValues.size() > disponibilitaIdx ? normalize(cellValues.get(disponibilitaIdx)) : null;
                    String ean = eanIdx != null && cellValues.size() > eanIdx ? normalize(cellValues.get(eanIdx)) : null;
                    String marca = marcaIdx != null && cellValues.size() > marcaIdx ? normalize(cellValues.get(marcaIdx)) : null;
                    String codiceProduttore = codiceProduttoreIdx != null && cellValues.size() > codiceProduttoreIdx ? normalize(cellValues.get(codiceProduttoreIdx)) : null;
                    String descrizione = descrizioneIdx != null && cellValues.size() > descrizioneIdx ? normalize(cellValues.get(descrizioneIdx)) : null;

                    ProductImportDTO dto = new ProductImportDTO();
                    dto.setSku(sku);
                    dto.setEan(ean);
                    dto.setMarca(marca);
                    dto.setCodiceProduttore(codiceProduttore);
                    dto.setNome(nome);
                    dto.setNomeCategoria(categoria);
                    dto.setPrezzoBase(prezzo);
                    dto.setDisponibilita(disponibilita);
                    dto.setDescrizione(descrizione);
                    result.add(dto);
                }
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Impossibile leggere il file Excel XML. " + hint, e);
        }
    }

    private String stripExcelXmlProlog(String s) {
        if (s == null) return "";
        int start = 0;
        if (s.startsWith("\uFEFF")) start = 1;
        int xmlDecl = s.indexOf("<?xml");
        if (xmlDecl >= 0) {
            int endDecl = s.indexOf("?>", xmlDecl);
            if (endDecl >= 0) start = Math.max(start, endDecl + 2);
        }
        int mso = s.indexOf("<?mso-application");
        if (mso >= 0) {
            int endMso = s.indexOf("?>", mso);
            if (endMso >= 0) start = Math.max(start, endMso + 2);
        }
        return s.substring(start).trim();
    }

    private List<String> extractRowCells(Element row) {
        List<String> cells = new java.util.ArrayList<>();
        NodeList cellNodes = row.getElementsByTagNameNS("urn:schemas-microsoft-com:office:spreadsheet", "Cell");
        if (cellNodes == null || cellNodes.getLength() == 0) {
            cellNodes = row.getElementsByTagName("Cell");
        }
        for (int i = 0; i < cellNodes.getLength(); i++) {
            Element cell = (Element) cellNodes.item(i);
            String indexAttr = cell.getAttributeNS("urn:schemas-microsoft-com:office:spreadsheet", "Index");
            if (indexAttr == null || indexAttr.isEmpty()) {
                indexAttr = cell.getAttribute("ss:Index");
            }
            if (indexAttr != null && !indexAttr.isEmpty()) {
                try {
                    int idx = Integer.parseInt(indexAttr);
                    while (cells.size() < idx - 1) cells.add("");
                } catch (NumberFormatException ignored) {}
            }
            String value = getCellDataValue(cell);
            cells.add(value != null ? value : "");
        }
        return cells;
    }

    private String getCellDataValue(Element cell) {
        NodeList dataNodes = cell.getElementsByTagNameNS("urn:schemas-microsoft-com:office:spreadsheet", "Data");
        if (dataNodes == null || dataNodes.getLength() == 0) {
            dataNodes = cell.getElementsByTagName("Data");
        }
        if (dataNodes == null || dataNodes.getLength() == 0) return null;
        Node data = dataNodes.item(0);
        return data.getTextContent();
    }

    private String normalizeExcelHeaderCell(String raw) {
        if (raw == null) return null;
        String col = raw.trim().toLowerCase();
        if (col.startsWith("\"") && col.endsWith("\"") && col.length() >= 2) {
            col = col.substring(1, col.length() - 1);
        }
        col = col.trim();
        col = col.replaceAll("\\s+", "_");
        col = col.replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        // Rimuove caratteri di combinazione (es. U+0332 underline) che possono impedire il match con "CS"
        col = col.replaceAll("\\p{M}", "");
        return aliasHeader(col);
    }

    /**
     * Estrae EAN da riga tipo "-EAN:8057284620150" (EAN sulla riga sotto al prodotto).
     * Scansiona tutte le celle della riga. Ritorna null se non trova il pattern.
     */
    private String extractEanFromRow(Row row, DataFormatter formatter) {
        if (row == null) return null;
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell == null) continue;
            String raw = formatter.formatCellValue(cell);
            String ean = extractEanFromString(raw);
            if (ean != null) return ean;
        }
        return null;
    }

    /** Estrae EAN da stringa tipo "-EAN:8057284620150" o "EAN:8057284620150". Ritorna null se non valido. */
    private String extractEanFromString(String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.isEmpty()) return null;
        // Cerca pattern EAN: o -EAN: seguito da cifre (anche in mezzo alla stringa)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)-?EAN:\\s*(\\d{8,14})").matcher(raw);
        if (m.find()) return m.group(1);
        return null;
    }

    /** Legge una cella come stringa, gestendo celle null o sparse. Per .xls gestisce anche celle numeriche. */
    private String readCellAsString(Row row, Integer colIndex, DataFormatter formatter) {
        if (row == null || colIndex == null || colIndex < 0) return null;
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double n = cell.getNumericCellValue();
                if (n == (long) n) return normalize(String.valueOf((long) n));
                return normalize(String.valueOf(n));
            }
            return normalize(formatter.formatCellValue(cell));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double readNumericCellAsDouble(Cell cell, DataFormatter formatter) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return cell.getNumericCellValue();
            }
            if (cell.getCellType() == CellType.STRING) {
                String s = normalize(cell.getStringCellValue());
                return parsePriceFromString(s);
            }
            String formatted = normalize(formatter.formatCellValue(cell));
            return parsePriceFromString(formatted);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Estrae un numero da stringhe come "10,50", "10.50", "10,50 €", "€ 10,50", "10,50 EUR". */
    private Double parsePriceFromString(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim()
                .replace("€", "")
                .replace("EUR", "")
                .replace("euro", "")
                .replaceAll("[^0-9,.-]", "")
                .trim();
        if (s.isEmpty()) return null;
        s = s.replace(",", ".");
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isBlank() ? null : v;
    }

    /**
     * Mappa la categoria proveniente dal CSV (più il nome prodotto)
     * su una delle macro-categorie ammesse nel catalogo:
     *
     * Computer, Networking, Elettronica, Multimedia, Cavi,
     * Ufficio, Accessori, Scuola e Laboratori, Best sellers, Videosorveglianza.
     *
     * Se nessuna regola viene soddisfatta, restituisce null
     * e il chiamante applica un fallback.
     */
    private String mapToMainCategory(String rawCategory, String productName) {
        String base = rawCategory != null ? rawCategory : "";
        String nome = productName != null ? productName : "";
        String text = (base + " " + nome).toLowerCase();

        // Best sellers: se nel CSV compare esplicitamente
        if (containsAny(text, "best seller", "bestseller", "best_seller")) {
            return "Best sellers";
        }

        // Videosorveglianza (include IPC = IP Camera / telecamere IP)
        if (containsAny(text, "videosorveglianza", "telecamera", "videocamera", "dvr", "nvr", "kit videosorveglianza", "ipc", "ip camera", "ip-camera", "telecamera ip")) {
            return "Videosorveglianza";
        }

        // Networking
        if (containsAny(text, "router", "switch", "access point", "access-point", "modem", "rete", "networking", "lan", "wifi")) {
            return "Networking";
        }

        // Computer
        if (containsAny(text, "computer", "pc ", " pc", "notebook", "laptop", "desktop", "all in one", "all-in-one", "monitor", "workstation")) {
            return "Computer";
        }

        // Multimedia
        if (containsAny(text, "tv ", " tv", "televisore", "televisori", "soundbar", "casse", "altoparlante", "altoparlanti", "audio", "video", "proiettore")) {
            return "Multimedia";
        }

        // Cavi
        if (containsAny(text, "cavo", "cavi", "hdmi", "usb", "ethernet", "patch cord", "patch-cord", "alimentazione", "prolunga")) {
            return "Cavi";
        }

        // Ufficio
        if (containsAny(text, "ufficio", "stampante", "scanner", "fax", "multifunzione", "etichettatrice", "distruggidocumenti", "rilegatrice")) {
            return "Ufficio";
        }

        // Elettronica (prima di Accessori per match più specifici)
        if (containsAny(text, "elettronica", "hardware", "storage", "ssd", "hdd", "disco", "memoria", "ram", "box estern", "enclosure", "usb3", "usb 3")) {
            return "Elettronica";
        }

        // Accessori (catch-all per box, custodie, adattatori, ecc.)
        if (containsAny(text, "accessorio", "accessori", "custodia", "zaino", "borsa", "supporto", "stand", "adattatore", "adapter", "hub", "dock", "docking", "box", "estern")) {
            return "Accessori";
        }

        // Scuola e Laboratori
        if (containsAny(text, "scuola", "laboratorio", "laboratori", "didattica", "didattico", "lim", "microscopio", "kit elettronica", "kit didattico")) {
            return "Scuola e Laboratori";
        }

        // Nessuna regola ha fatto match: Accessori come categoria generica
        return "Accessori";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String k : keywords) {
            if (k != null && !k.isEmpty() && text.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Boolean> computeAutoOfferFlagsFromPreviousImport(ImportLog currentImport, List<ProductImportDTO> currentRows) {
        if (currentImport == null || currentImport.getSupplier() == null) return Map.of();
        return computeAutoOfferFlagsForSupplier(
                currentImport.getSupplier().getId(),
                currentImport.getId(),
                currentRows
        );
    }

    private Map<String, Boolean> computeAutoOfferFlagsForSupplier(Long supplierId,
                                                                   Long currentImportId,
                                                                   List<ProductImportDTO> currentRows) {
        if (supplierId == null || currentRows == null || currentRows.isEmpty()) return Map.of();
        try {
            List<ImportLog> previousImports = importLogRepository.findPreviousProductImportsForSupplier(
                    supplierId,
                    currentImportId != null ? currentImportId : -1L,
                    PageRequest.of(0, 1)
            );
            if (previousImports.isEmpty()) return Map.of();
            ImportLog prev = previousImports.get(0);
            if (prev.getFileContent() == null || prev.getFileContent().length == 0) return Map.of();

            List<ProductImportDTO> previousRows = parseProductRowsFromBytes(
                    prev.getFileContent(),
                    prev.getFileName(),
                    prev.getFileContentType()
            );
            Map<String, Double> prevPriceByKey = new HashMap<>();
            for (ProductImportDTO dto : previousRows) {
                String key = offerComparisonKey(dto.getSku(), dto.getEan(), dto.getNome());
                if (key == null || prevPriceByKey.containsKey(key) || dto.getPrezzoBase() == null) continue;
                prevPriceByKey.put(key, dto.getPrezzoBase());
            }

            Map<String, Boolean> out = new HashMap<>();
            for (ProductImportDTO dto : currentRows) {
                String key = offerComparisonKey(dto.getSku(), dto.getEan(), dto.getNome());
                if (key == null || dto.getPrezzoBase() == null) continue;
                Double prevPrice = prevPriceByKey.get(key);
                if (prevPrice == null) continue;
                out.put(key, dto.getPrezzoBase() < prevPrice);
            }
            return out;
        } catch (Exception e) {
            log.warn("Auto-offerte: impossibile calcolare confronto con import precedente (supplierId={}): {}",
                    supplierId, e.getMessage());
            return Map.of();
        }
    }

    private String offerComparisonKey(String sku, String ean, String nome) {
        String s = normalize(sku);
        if (s != null) return "SKU:" + truncate(s, 255);
        String e = normalize(ean);
        if (e != null) return "EAN:" + truncate(e, 255);
        String n = normalize(nome);
        if (n != null) return "NOME:" + truncate(n, 255);
        return null;
    }

    private Boolean resolveAutoOfferFlag(Map<String, Boolean> autoOfferByKey,
                                         ProductImportDTO dto,
                                         String normalizedSku,
                                         String normalizedEan,
                                         String normalizedNome) {
        if (autoOfferByKey == null || autoOfferByKey.isEmpty()) return null;
        String[] keys = new String[] {
                offerComparisonKey(dto != null ? dto.getSku() : null, dto != null ? dto.getEan() : null, dto != null ? dto.getNome() : null),
                offerComparisonKey(normalizedSku, null, null),
                offerComparisonKey(null, normalizedEan, null),
                offerComparisonKey(null, null, normalizedNome)
        };
        for (String key : keys) {
            if (key != null && autoOfferByKey.containsKey(key)) {
                return autoOfferByKey.get(key);
            }
        }
        return null;
    }

    /**
     * Confronta due file import PRODOTTI dello stesso fornitore.
     * Utile per decidere se applicare import automatico o gestire manualmente (es. poche offerte).
     */
    public Map<String, Object> compareProductImportLogs(ImportLog left, ImportLog right) throws Exception {
        if (left == null || right == null) {
            throw new IllegalArgumentException("Import non validi per il confronto.");
        }
        if (left.getFileContent() == null || left.getFileContent().length == 0
                || right.getFileContent() == null || right.getFileContent().length == 0) {
            throw new IllegalArgumentException("Uno dei due CSV è vuoto o non disponibile.");
        }

        List<ProductImportDTO> leftRows = parseProductRowsFromBytes(left.getFileContent(), left.getFileName(), left.getFileContentType());
        List<ProductImportDTO> rightRows = parseProductRowsFromBytes(right.getFileContent(), right.getFileName(), right.getFileContentType());

        Map<String, ProductImportDTO> leftByKey = new HashMap<>();
        Map<String, ProductImportDTO> rightByKey = new HashMap<>();
        Map<String, ProductImportDTO> rightBySku = new HashMap<>();
        Map<String, ProductImportDTO> rightByEan = new HashMap<>();
        Map<String, ProductImportDTO> rightByNome = new HashMap<>();
        Set<String> leftOffer = new HashSet<>();
        Set<String> rightOffer = new HashSet<>();

        for (ProductImportDTO dto : leftRows) {
            String key = importRowKey(dto);
            if (key == null || leftByKey.containsKey(key)) continue;
            leftByKey.put(key, dto);
            if (isOfferRow(dto)) leftOffer.add(key);
        }
        for (ProductImportDTO dto : rightRows) {
            String key = importRowKey(dto);
            if (key == null || rightByKey.containsKey(key)) continue;
            rightByKey.put(key, dto);
            if (isOfferRow(dto)) rightOffer.add(key);
            String sku = normalize(dto.getSku());
            String ean = normalize(dto.getEan());
            String nome = normalize(dto.getNome());
            if (sku != null) rightBySku.putIfAbsent(sku, dto);
            if (ean != null) rightByEan.putIfAbsent(ean, dto);
            if (nome != null) rightByNome.putIfAbsent(nome, dto);
        }

        int onlyLeft = 0;
        int onlyRight = 0;
        int unchanged = 0;
        int priceChanged = 0;
        int availabilityChanged = 0;
        int offerChanged = 0;
        List<String> newlyInOffer = new java.util.ArrayList<>();
        List<Map<String, Object>> priceDifferences = new java.util.ArrayList<>();
        Set<String> matchedRightKeys = new HashSet<>();
        List<Map<String, Object>> missingInRight = new java.util.ArrayList<>();
        List<Map<String, Object>> missingInLeft = new java.util.ArrayList<>();

        for (String key : leftByKey.keySet()) {
            ProductImportDTO l = leftByKey.get(key);
            MatchResult match = findMatchingRow(l, rightByKey, rightBySku, rightByEan, rightByNome, matchedRightKeys);
            ProductImportDTO r = match.row;
            if (r == null) {
                onlyLeft++;
                missingInRight.add(buildMissingRow(l, "Presente solo in CSV A"));
                continue;
            }
            matchedRightKeys.add(importRowKey(r));
            boolean samePrice = Objects.equals(normalizePrice(l.getPrezzoBase()), normalizePrice(r.getPrezzoBase()));
            boolean sameDisp = Objects.equals(normalize(l.getDisponibilita()), normalize(r.getDisponibilita()));
            String matchedKey = importRowKey(r);
            boolean lOffer = leftOffer.contains(key);
            boolean rOffer = matchedKey != null && rightOffer.contains(matchedKey);
            if (!samePrice) {
                priceChanged++;
                priceDifferences.add(buildPriceDifferenceRow(l, r, match.by));
            }
            if (!sameDisp) availabilityChanged++;
            if (lOffer != rOffer) {
                offerChanged++;
                if (!lOffer && rOffer) newlyInOffer.add(key);
            }
            if (samePrice && sameDisp && lOffer == rOffer) unchanged++;
        }

        for (String key : rightByKey.keySet()) {
            if (!matchedRightKeys.contains(key)) {
                onlyRight++;
                ProductImportDTO rr = rightByKey.get(key);
                if (rr != null) {
                    missingInLeft.add(buildMissingRow(rr, "Presente solo in CSV B"));
                }
            }
        }

        String recommendation;
        boolean anyDifference = onlyLeft > 0 || onlyRight > 0 || priceChanged > 0 || availabilityChanged > 0 || offerChanged > 0;
        if (!anyDifference) {
            recommendation = "I due listini sono identici.";
        } else if (newlyInOffer.isEmpty()) {
            recommendation = "Nessuna nuova offerta trovata, ma i listini NON sono uguali (ci sono variazioni di prezzo/disponibilità o articoli mancanti).";
        } else if (newlyInOffer.size() == 1) {
            recommendation = "Trovato 1 solo articolo nuovo in offerta: valuta caricamento manuale.";
        } else {
            recommendation = "Più articoli nuovi in offerta: puoi valutare import automatico.";
        }

        Map<String, Object> out = new HashMap<>();
        out.put("leftImportId", left.getId());
        out.put("rightImportId", right.getId());
        out.put("leftFileName", left.getFileName());
        out.put("rightFileName", right.getFileName());
        out.put("leftRows", leftByKey.size());
        out.put("rightRows", rightByKey.size());
        out.put("unchanged", unchanged);
        out.put("onlyLeft", onlyLeft);
        out.put("onlyRight", onlyRight);
        out.put("priceChanged", priceChanged);
        out.put("availabilityChanged", availabilityChanged);
        out.put("offerChanged", offerChanged);
        out.put("newlyInOfferCount", newlyInOffer.size());
        out.put("newlyInOfferKeys", newlyInOffer);
        out.put("priceDifferences", priceDifferences);
        out.put("missingInRight", missingInRight);
        out.put("missingInLeft", missingInLeft);
        out.put("recommendation", recommendation);
        return out;
    }

    /**
     * Confronta un CSV importato con lo stato attuale del database prodotti.
     * Match per priorità: SKU -> EAN -> NOME.
     */
    public Map<String, Object> compareImportLogWithDatabase(ImportLog csvImport) throws Exception {
        if (csvImport == null || csvImport.getFileContent() == null || csvImport.getFileContent().length == 0) {
            throw new IllegalArgumentException("CSV non valido o vuoto.");
        }
        List<ProductImportDTO> csvRows = parseProductRowsFromBytes(
                csvImport.getFileContent(),
                csvImport.getFileName(),
                csvImport.getFileContentType()
        );
        List<Product> dbProducts = productRepository.findAllWithAssociations();

        Map<String, ProductImportDTO> csvByKey = new HashMap<>();
        for (ProductImportDTO dto : csvRows) {
            String key = importRowKey(dto);
            if (key == null || csvByKey.containsKey(key)) continue;
            csvByKey.put(key, dto);
        }

        Map<String, Product> dbBySku = new HashMap<>();
        Map<String, Product> dbByEan = new HashMap<>();
        Map<String, Product> dbByNome = new HashMap<>();
        for (Product p : dbProducts) {
            String sku = normalize(p.getSku());
            String ean = normalize(p.getEan());
            String nome = normalize(p.getNome());
            if (sku != null) dbBySku.putIfAbsent(sku, p);
            if (ean != null) dbByEan.putIfAbsent(ean, p);
            if (nome != null) dbByNome.putIfAbsent(nome, p);
        }

        int unchanged = 0;
        int priceChanged = 0;
        int availabilityChanged = 0;
        int onlyCsv = 0;
        int onlyDb;
        List<Map<String, Object>> priceDifferences = new java.util.ArrayList<>();
        List<Map<String, Object>> missingInRight = new java.util.ArrayList<>(); // solo CSV
        Set<Long> matchedDbIds = new HashSet<>();

        for (ProductImportDTO left : csvByKey.values()) {
            Product right = findDatabaseMatch(left, dbBySku, dbByEan, dbByNome, matchedDbIds);
            if (right == null) {
                onlyCsv++;
                missingInRight.add(buildMissingRow(left, "Presente solo nel CSV"));
                continue;
            }
            matchedDbIds.add(right.getId());

            boolean samePrice = Objects.equals(normalizePrice(left.getPrezzoBase()), normalizePrice(toDouble(right.getPrezzoBase())));
            boolean sameDisp = Objects.equals(normalize(left.getDisponibilita()), normalize(right.getDisponibilita()));
            if (!samePrice) {
                priceChanged++;
                priceDifferences.add(buildPriceDifferenceRowCsvVsDb(left, right));
            }
            if (!sameDisp) {
                availabilityChanged++;
            }
            if (samePrice && sameDisp) {
                unchanged++;
            }
        }

        onlyDb = Math.max(0, dbProducts.size() - matchedDbIds.size());

        String recommendation = priceChanged == 0 && onlyCsv == 0
                ? "CSV allineato al database per prezzi e prodotti."
                : "Trovate differenze tra CSV e database: verifica la lista dettagliata.";

        Map<String, Object> out = new HashMap<>();
        out.put("leftImportId", csvImport.getId());
        out.put("leftFileName", csvImport.getFileName());
        out.put("rightFileName", "Database");
        out.put("leftRows", csvByKey.size());
        out.put("rightRows", dbProducts.size());
        out.put("unchanged", unchanged);
        out.put("onlyLeft", onlyCsv);
        out.put("onlyRight", onlyDb);
        out.put("priceChanged", priceChanged);
        out.put("availabilityChanged", availabilityChanged);
        out.put("offerChanged", 0);
        out.put("newlyInOfferCount", 0);
        out.put("newlyInOfferKeys", List.of());
        out.put("priceDifferences", priceDifferences);
        out.put("missingInRight", missingInRight);
        out.put("missingInLeft", List.of());
        out.put("recommendation", recommendation);
        return out;
    }

    private Product findDatabaseMatch(ProductImportDTO left,
                                      Map<String, Product> dbBySku,
                                      Map<String, Product> dbByEan,
                                      Map<String, Product> dbByNome,
                                      Set<Long> matchedDbIds) {
        String sku = normalize(left.getSku());
        if (sku != null) {
            Product p = dbBySku.get(sku);
            if (p != null && p.getId() != null && !matchedDbIds.contains(p.getId())) return p;
        }
        String ean = normalize(left.getEan());
        if (ean != null) {
            Product p = dbByEan.get(ean);
            if (p != null && p.getId() != null && !matchedDbIds.contains(p.getId())) return p;
        }
        String nome = normalize(left.getNome());
        if (nome != null) {
            Product p = dbByNome.get(nome);
            if (p != null && p.getId() != null && !matchedDbIds.contains(p.getId())) return p;
        }
        return null;
    }

    private Map<String, Object> buildPriceDifferenceRowCsvVsDb(ProductImportDTO csv, Product db) {
        Map<String, Object> row = new HashMap<>();
        Double oldPrice = csv.getPrezzoBase();
        Double newPrice = toDouble(db.getPrezzoBase());
        double delta = (newPrice != null ? newPrice : 0d) - (oldPrice != null ? oldPrice : 0d);
        row.put("sku", normalize(csv.getSku()) != null ? csv.getSku() : db.getSku());
        row.put("ean", normalize(csv.getEan()) != null ? csv.getEan() : db.getEan());
        row.put("nome", normalize(csv.getNome()) != null ? csv.getNome() : db.getNome());
        row.put("oldPrice", oldPrice);
        row.put("newPrice", newPrice);
        row.put("delta", delta);
        row.put("direction", delta > 0 ? "AUMENTATO" : (delta < 0 ? "DIMINUITO" : "VARIATO"));
        row.put("matchedBy", "CSV_vs_DB");
        return row;
    }

    private Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }

    private static class MatchResult {
        private final ProductImportDTO row;
        private final String by;

        private MatchResult(ProductImportDTO row, String by) {
            this.row = row;
            this.by = by;
        }
    }

    private MatchResult findMatchingRow(ProductImportDTO left,
                                        Map<String, ProductImportDTO> rightByKey,
                                        Map<String, ProductImportDTO> rightBySku,
                                        Map<String, ProductImportDTO> rightByEan,
                                        Map<String, ProductImportDTO> rightByNome,
                                        Set<String> alreadyMatchedRightKeys) {
        String sku = normalize(left.getSku());
        if (sku != null) {
            ProductImportDTO r = rightBySku.get(sku);
            String rk = importRowKey(r);
            if (r != null && rk != null && !alreadyMatchedRightKeys.contains(rk)) return new MatchResult(r, "SKU");
        }
        String ean = normalize(left.getEan());
        if (ean != null) {
            ProductImportDTO r = rightByEan.get(ean);
            String rk = importRowKey(r);
            if (r != null && rk != null && !alreadyMatchedRightKeys.contains(rk)) return new MatchResult(r, "EAN");
        }
        String nome = normalize(left.getNome());
        if (nome != null) {
            ProductImportDTO r = rightByNome.get(nome);
            String rk = importRowKey(r);
            if (r != null && rk != null && !alreadyMatchedRightKeys.contains(rk)) return new MatchResult(r, "NOME");
        }
        String key = importRowKey(left);
        ProductImportDTO r = key != null ? rightByKey.get(key) : null;
        String rk = importRowKey(r);
        if (r != null && rk != null && !alreadyMatchedRightKeys.contains(rk)) return new MatchResult(r, "CHIAVE");
        return new MatchResult(null, null);
    }

    private Map<String, Object> buildPriceDifferenceRow(ProductImportDTO left, ProductImportDTO right, String matchedBy) {
        Map<String, Object> row = new HashMap<>();
        Double oldPrice = left.getPrezzoBase();
        Double newPrice = right.getPrezzoBase();
        double delta = (newPrice != null ? newPrice : 0d) - (oldPrice != null ? oldPrice : 0d);
        row.put("sku", normalize(left.getSku()) != null ? left.getSku() : right.getSku());
        row.put("ean", normalize(left.getEan()) != null ? left.getEan() : right.getEan());
        row.put("nome", normalize(left.getNome()) != null ? left.getNome() : right.getNome());
        row.put("oldPrice", oldPrice);
        row.put("newPrice", newPrice);
        row.put("delta", delta);
        row.put("direction", delta > 0 ? "AUMENTATO" : (delta < 0 ? "DIMINUITO" : "VARIATO"));
        row.put("matchedBy", matchedBy);
        return row;
    }

    private Map<String, Object> buildMissingRow(ProductImportDTO dto, String note) {
        Map<String, Object> row = new HashMap<>();
        row.put("sku", dto.getSku());
        row.put("ean", dto.getEan());
        row.put("nome", dto.getNome());
        row.put("note", note);
        return row;
    }

    private String importRowKey(ProductImportDTO dto) {
        if (dto == null) return null;
        String sku = normalize(dto.getSku());
        String ean = normalize(dto.getEan());
        String nome = normalize(dto.getNome());
        String base = sku != null ? ("SKU:" + sku) : (ean != null ? ("EAN:" + ean) : (nome != null ? ("NOME:" + nome) : null));
        if (base == null) return null;
        return truncate(base, 255);
    }

    private boolean isOfferRow(ProductImportDTO dto) {
        if (dto == null) return false;
        String cat = normalize(dto.getNomeCategoria());
        if (cat == null) return false;
        String c = cat.toLowerCase();
        return c.contains("offerta") || c.contains("offer");
    }

    private String normalizePrice(Double v) {
        if (v == null) return null;
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}

