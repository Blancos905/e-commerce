package com.e_commerce.service;

import com.e_commerce.dto.DocumentImportDTO;
import com.e_commerce.dto.ProductImportDTO;
import com.e_commerce.dto.ProductSnapshotDTO;
import com.e_commerce.dto.SupplierImportDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.e_commerce.model.Category;
import com.e_commerce.model.Document;
import com.e_commerce.model.ImportLog;
import com.e_commerce.model.Product;
import com.e_commerce.model.Supplier;
import com.e_commerce.repository.CategoryRepository;
import com.e_commerce.repository.DocumentRepository;
import com.e_commerce.repository.ProductRepository;
import com.e_commerce.repository.SupplierRepository;
import com.e_commerce.repository.ImportLogRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Service
public class ImportService {

    /** Indice colonna Excel CS (97ª colonna, 0-based=96), usata spesso per disponibilità. */
    private static final int COLONNA_CS_INDEX = 96;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final DocumentRepository documentRepository;
    private final SupplierRepository supplierRepository;
    private final ImportLogRepository importLogRepository;
    private final ProductService productService;

    public ImportService(ProductRepository productRepository,
                         CategoryRepository categoryRepository,
                         DocumentRepository documentRepository,
                         SupplierRepository supplierRepository,
                         ImportLogRepository importLogRepository,
                         ProductService productService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.documentRepository = documentRepository;
        this.supplierRepository = supplierRepository;
        this.importLogRepository = importLogRepository;
        this.productService = productService;
    }

    public void importProducts(MultipartFile file, Long supplierId) throws Exception {
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
     */
    public void applyImportWithSnapshot(ImportLog log) throws Exception {
        if (log == null || log.getFileContent() == null || log.getFileContent().length == 0) {
            throw new IllegalArgumentException("File non disponibile nello storico import.");
        }
        if (log.getSupplier() == null) {
            throw new IllegalArgumentException("Import senza fornitore associato.");
        }
        List<ProductImportDTO> rows = parseProductRowsFromBytes(
                log.getFileContent(),
                log.getFileName(),
                log.getFileContentType()
        );
        Map<String, ProductSnapshotDTO> snapshot = new HashMap<>();
        for (ProductImportDTO dto : rows) {
            String sku = normalize(dto.getSku());
            if (sku == null) continue;
            String skuTruncated = truncate(sku, 255);
            productRepository.findBySku(skuTruncated).ifPresent(p -> snapshot.put(skuTruncated, toSnapshot(p)));
        }
        processProductRows(rows, log.getSupplier());
        ObjectMapper mapper = new ObjectMapper();
        log.setPreviousStateJson(mapper.writeValueAsString(snapshot));
        log.setAppliedAt(LocalDateTime.now());
        importLogRepository.save(log);
    }

    /**
     * Annulla l'ultimo import: ripristina i prodotti modificati ed elimina quelli creati.
     */
    public void rollbackImport(ImportLog log) throws Exception {
        if (log == null || log.getPreviousStateJson() == null || log.getPreviousStateJson().isBlank()) {
            throw new IllegalArgumentException("Impossibile fare rollback: nessuno snapshot disponibile per questo import.");
        }
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
            String sku = normalize(dto.getSku());
            if (sku == null) continue;
            String skuTruncated = truncate(sku, 255);
            ProductSnapshotDTO snap = snapshot.get(skuTruncated);
            if (snap != null) {
                restoreFromSnapshot(snap);
            } else {
                productRepository.findBySku(skuTruncated).ifPresent(p -> productService.deleteById(p.getId()));
            }
        }
        importLogRepository.delete(log);
    }

    private ProductSnapshotDTO toSnapshot(Product p) {
        ProductSnapshotDTO dto = new ProductSnapshotDTO();
        dto.setId(p.getId());
        dto.setSku(p.getSku());
        dto.setEan(p.getEan());
        dto.setNome(p.getNome());
        dto.setDescrizione(p.getDescrizione());
        dto.setPrezzoBase(p.getPrezzoBase());
        dto.setPrezzoFinale(p.getPrezzoFinale());
        dto.setAumentoPercentuale(p.getAumentoPercentuale());
        dto.setCategoriaId(p.getCategoria() != null ? p.getCategoria().getId() : null);
        dto.setSupplierId(p.getFornitore() != null ? p.getFornitore().getId() : null);
        dto.setContati(p.getContati());
        dto.setDisponibilita(p.getDisponibilita());
        return dto;
    }

    private void restoreFromSnapshot(ProductSnapshotDTO snap) {
        Product product = productRepository.findById(snap.getId()).orElse(null);
        if (product == null) return;
        product.setEan(snap.getEan());
        product.setNome(snap.getNome());
        product.setDescrizione(snap.getDescrizione());
        product.setPrezzoBase(snap.getPrezzoBase());
        product.setPrezzoFinale(snap.getPrezzoFinale());
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

    private List<ProductImportDTO> parseProductRowsFromBytes(byte[] bytes, String filename, String contentType) throws Exception {
        if (isExcelFile(filename, contentType)) {
            return parseProductsXlsxOrXlsBytes(bytes, "Prodotti (Excel): header atteso almeno: sku,nome_prodotto,categoria,prezzo.");
        }
        if (isExcelXmlFile(filename, contentType)) {
            return parseProductsSpreadsheetMlBytes(bytes, "Prodotti (Excel XML): header atteso almeno: sku,nome_prodotto,categoria,prezzo.");
        }
        return parseCsvBytes(bytes, ProductImportDTO.class, "Prodotti (CSV): header atteso almeno: sku,nome_prodotto,categoria.");
    }

    private void processProductRows(List<ProductImportDTO> rows, Supplier supplier) throws Exception {
        for (ProductImportDTO dto : rows) {
            String sku = normalize(dto.getSku());
            if (sku == null) continue;
            sku = truncate(sku, 255);
            String nomeProdotto = normalize(dto.getNome());
            if (nomeProdotto == null) nomeProdotto = sku;
            nomeProdotto = truncate(nomeProdotto, 255);
            String rawNomeCategoria = normalize(dto.getNomeCategoria());
            String nomeCategoria;
            if (rawNomeCategoria != null && !rawNomeCategoria.isBlank()
                    && categoryRepository.findByNome(rawNomeCategoria).isPresent()) {
                nomeCategoria = rawNomeCategoria;
            } else {
                String mappedCategoria = mapToMainCategory(rawNomeCategoria, nomeProdotto);
                nomeCategoria = mappedCategoria != null ? mappedCategoria : "Accessori";
            }
            Category category = categoryRepository.findByNome(nomeCategoria)
                    .orElseGet(() -> {
                        Category c = new Category();
                        c.setNome(nomeCategoria);
                        return categoryRepository.save(c);
                    });
            Product product = productRepository.findBySku(sku).orElseGet(Product::new);
            product.setSku(sku);
            product.setNome(nomeProdotto);
            product.setPrezzoBase(dto.getPrezzoBase() != null ? BigDecimal.valueOf(dto.getPrezzoBase()) : BigDecimal.ZERO);
            product.setCategoria(category);
            product.setFornitore(supplier);
            product.setDisponibilita(truncate(normalize(dto.getDisponibilita()), 64));
            productService.save(product);
        }
    }

    private void importProductsFromBytes(byte[] bytes,
                                        String originalFilename,
                                        String contentType,
                                        Long supplierId,
                                        boolean createImportLog) throws Exception {
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

        int rowNumber = 1; // 1-based rispetto alle righe dati (escluso header)
        for (ProductImportDTO dto : rows) {
            String sku = normalize(dto.getSku());
            if (sku == null) {
                sku = normalize(dto.getEan()); // fallback: EAN come SKU (molti fornitori usano EAN come codice)
            }
            if (sku == null) {
                rowNumber++;
                continue; // salta righe senza SKU (righe vuote o dati incompleti)
            }
            // Rispetta il limite DB VARCHAR(255)
            sku = truncate(sku, 255);

            // `Product.nome` è NOT NULL: se nel CSV manca/è vuoto, usiamo lo SKU come fallback
            String nomeProdotto = normalize(dto.getNome());
            if (nomeProdotto == null) {
                nomeProdotto = sku;
            }
            // Rispetta il limite DB VARCHAR(255)
            nomeProdotto = truncate(nomeProdotto, 255);

            String rawNomeCategoria = normalize(dto.getNomeCategoria());
            // Se la categoria del CSV esiste già nel DB (anche creata dall'utente), usala direttamente.
            // Altrimenti mappiamo tramite keyword su macro-categorie standard.
            String nomeCategoria;
            if (rawNomeCategoria != null && !rawNomeCategoria.isBlank()
                    && categoryRepository.findByNome(rawNomeCategoria).isPresent()) {
                nomeCategoria = rawNomeCategoria;
            } else {
                String mappedCategoria = mapToMainCategory(rawNomeCategoria, nomeProdotto);
                nomeCategoria = mappedCategoria != null ? mappedCategoria : "Accessori";
            }

            Category category = categoryRepository
                    .findByNome(nomeCategoria)
                    .orElseGet(() -> {
                        Category c = new Category();
                        c.setNome(nomeCategoria);
                        return categoryRepository.save(c);
                    });

            Product product = productRepository.findBySku(sku)
                    .orElseGet(Product::new);
            product.setSku(sku);
            product.setNome(nomeProdotto);

            // Gestione prezzo base: il DB ha un vincolo NOT NULL sulla colonna
            // quindi se nel CSV il prezzo manca lo impostiamo a 0 per evitare l'errore.
            if (dto.getPrezzoBase() != null) {
                product.setPrezzoBase(BigDecimal.valueOf(dto.getPrezzoBase()));
            } else {
                product.setPrezzoBase(BigDecimal.ZERO);
            }

            product.setCategoria(category);

            if (supplier != null) {
                product.setFornitore(supplier);
            }

            product.setDisponibilita(truncate(normalize(dto.getDisponibilita()), 64));

            // EAN per Icecat: usa colonna ean se presente, altrimenti sku (molti fornitori usano EAN come codice)
            String eanVal = normalize(dto.getEan());
            product.setEan(eanVal != null ? truncate(eanVal, 32) : sku);

            productService.save(product);
            rowNumber++;
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
    }

    public void importDocuments(MultipartFile file, Long supplierId) throws Exception {
        List<DocumentImportDTO> rows = parseCsv(file, DocumentImportDTO.class, "Documenti: header atteso: sku,tipo_documento,url_documento (delimitatore ',' o ';').");

        Supplier supplier = null;
        if (supplierId != null) {
            supplier = supplierRepository.findById(supplierId)
                    .orElseThrow(() -> new IllegalArgumentException("Fornitore non trovato (supplierId=" + supplierId + ")."));
        }

        for (DocumentImportDTO dto : rows) {
            String sku = normalize(dto.getSku());
            if (sku == null) {
                continue;
            }
            Product product = productRepository.findBySku(sku)
                    .orElseThrow(() -> new IllegalArgumentException("Prodotto non trovato per SKU: " + sku));

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
                    .withCSVParser(new CSVParserBuilder().withSeparator(separator).build())
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
            csv = normalizeHeaderLine(csv, separator);

            try (CSVReader csvReader = new CSVReaderBuilder(new StringReader(csv))
                    .withCSVParser(new CSVParserBuilder().withSeparator(separator).build())
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

    private String normalizeHeaderLine(String csv, char separator) {
        String[] parts = csv.split("\\R", 2);
        String header = parts[0];
        String rest = parts.length > 1 ? parts[1] : "";

        StringBuilder normalized = new StringBuilder();
        String[] cols = header.split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
        for (int i = 0; i < cols.length; i++) {
            String col = cols[i];
            col = stripBom(col);
            col = col.trim();
            if (col.startsWith("\"") && col.endsWith("\"") && col.length() >= 2) {
                col = col.substring(1, col.length() - 1);
            }
            col = col.trim().toLowerCase();
            col = col.replaceAll("\\s+", "_");
            col = aliasHeader(col);
            normalized.append(col);
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
        col = col.replaceAll("\\p{M}", "");
        if (col.equals("cs") || col.matches("cs[_(\\(\\-].*")) {
            return "disponibilita";
        }
        if (col.contains("disponibil") || col.contains("stock") || col.contains("giacenza") || col.contains("availability")) {
            return "disponibilita";
        }
        return switch (col) {
            // sku
            case "codice", "code", "product_code", "productcode", "sku_prodotto",
                    "codice_articolo", "codicearticolo", "art_code", "item_code",
                    "barcode", "cod_articolo", "cod_art", "articolo_cod", "ref", "reference" -> "sku";
            // ean (per Icecat)
            case "ean", "gtin", "ean13", "ean_upc" -> "ean";

            // prodotti - nome
            case "nome" -> "nome_prodotto";
            case "nomeprodotto", "nome_del_prodotto", "prodotto", "articolo", "descrizione",
                    "desc", "titolo", "title", "denominazione" -> "nome_prodotto";
            case "name", "product_name", "productname", "product", "description", "item_name" -> "nome_prodotto";

            // prodotti - prezzo (CON/contanti/contati = prezzo in contanti dal fornitore -> prezzo base)
            case "prezzo_base", "prezzo_listino", "prezzobase", "prezzo_di_listino",
                    "price", "prezzo_unitario", "prezzo_unit", "listino",
                    "prezzo_netto", "prezzonetto", "prezzo_vendita", "prezzo_vendita_netto",
                    "pv", "p.v.", "p_v", "costo", "prezzo_acq", "prezzo_acquisto",
                    "eur", "euro", "prezzo_eur", "contanti", "contati", "con" -> "prezzo";

            // prodotti - categoria
            case "category", "cat", "nome_categoria", "categoria_nome", "categories", "category_name",
                    "categoria_prodotto", "macrocategoria", "tipologia", "famiglia" -> "categoria";

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
                    int score = 1;
                    if (candidate.containsKey("nome_prodotto")) score++;
                    if (candidate.containsKey("prezzo")) score++;
                    if (candidate.containsKey("disponibilita")) score++;
                    if (score > bestScore) {
                        bestScore = score;
                        headerRow = row;
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
                int firstDataRow = headerRow.getRowNum() + (usedSubHeader ? 2 : 1);
                if (disponibilitaIdx == null) {
                    // Cerca nella riga header e nella riga successiva (header su 2 righe)
                    Row subHeaderRow = sheet.getRow(headerRow.getRowNum() + 1);
                    for (Row rowToScan : new Row[]{headerRow, subHeaderRow}) {
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
                                if (rowToScan == subHeaderRow) {
                                    firstDataRow = headerRow.getRowNum() + 2;
                                }
                                break;
                            }
                        }
                        if (disponibilitaIdx != null) break;
                    }
                    // Fallback: scansiona le prime 25 righe per trovare una cella "CS" (o C.S., C S, ecc.)
                    if (disponibilitaIdx == null) {
                        for (int rr = firstRowNum; rr <= Math.min(firstRowNum + 24, sheet.getLastRowNum()); rr++) {
                            Row r = sheet.getRow(rr);
                            if (r == null) continue;
                            for (int c = 0; c < r.getLastCellNum(); c++) {
                                Cell cell = r.getCell(c);
                                if (cell == null) continue;
                                String raw = formatter.formatCellValue(cell);
                                if (raw == null) continue;
                                String norm = raw.trim().replaceAll("[\\s\\u00A0._-]+", "").replaceAll("\\p{M}", "");
                                if (norm.equalsIgnoreCase("cs")) {
                                    disponibilitaIdx = c;
                                    if (rr > headerRow.getRowNum()) {
                                        firstDataRow = rr + 1;
                                    }
                                    break;
                                }
                            }
                            if (disponibilitaIdx != null) break;
                        }
                    }
                    if (disponibilitaIdx == null) {
                        disponibilitaIdx = COLONNA_CS_INDEX;
                    }
                }

                int lastRow = sheet.getLastRowNum();
                java.util.ArrayList<ProductImportDTO> rows = new java.util.ArrayList<>();
                for (int r = firstDataRow; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

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

                    ProductImportDTO dto = new ProductImportDTO();
                    dto.setSku(sku);
                    dto.setEan(ean);
                    dto.setNome(nome);
                    dto.setNomeCategoria(categoria);
                    dto.setPrezzoBase(prezzo);
                    dto.setDisponibilita(disponibilita);
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
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

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
            int rowIdx = 0;

            for (int i = 0; i < rows.getLength(); i++) {
                Element rowEl = (Element) rows.item(i);
                List<String> cellValues = extractRowCells(rowEl);
                if (rowIdx == 0) {
                    for (int c = 0; c < cellValues.size(); c++) {
                        String col = normalizeExcelHeaderCell(cellValues.get(c));
                        if (col != null && !col.isBlank()) {
                            headerToIndex.put(col, c);
                        }
                    }
                    Integer skuIdx = headerToIndex.get("sku");
                    Integer catIdx = headerToIndex.get("categoria");
                    if (skuIdx == null || catIdx == null) {
                        throw new IllegalArgumentException(
                                "Header XML non valido. Colonne viste = [" + String.join(", ", headerToIndex.keySet()) + "]. " + hint
                        );
                    }
                    if (!headerToIndex.containsKey("disponibilita")) {
                        for (int c = 0; c < cellValues.size(); c++) {
                            String col = normalizeExcelHeaderCell(cellValues.get(c));
                            if (col != null && col.equals("disponibilita")) {
                                headerToIndex.put("disponibilita", c);
                                break;
                            }
                        }
                        // Fallback: colonna CS (indice 96) per disponibilità
                        if (!headerToIndex.containsKey("disponibilita") && cellValues.size() > COLONNA_CS_INDEX) {
                            headerToIndex.put("disponibilita", COLONNA_CS_INDEX);
                        }
                    }
                } else {
                    Integer skuIdx = headerToIndex.get("sku");
                    Integer nomeIdx = headerToIndex.get("nome_prodotto");
                    Integer catIdx = headerToIndex.get("categoria");
                    Integer prezzoIdx = headerToIndex.get("prezzo");
                    Integer disponibilitaIdx = headerToIndex.get("disponibilita");
                    Integer eanIdx = headerToIndex.get("ean");

                    String sku = cellValues.size() > skuIdx ? normalize(cellValues.get(skuIdx)) : null;
                    String categoria = cellValues.size() > catIdx ? normalize(cellValues.get(catIdx)) : null;
                    if (sku == null && categoria == null) continue;

                    String nome = nomeIdx != null && cellValues.size() > nomeIdx ? normalize(cellValues.get(nomeIdx)) : null;
                    Double prezzo = null;
                    if (prezzoIdx != null && cellValues.size() > prezzoIdx) {
                        prezzo = parsePriceFromString(normalize(cellValues.get(prezzoIdx)));
                    }
                    String disponibilita = disponibilitaIdx != null && cellValues.size() > disponibilitaIdx ? normalize(cellValues.get(disponibilitaIdx)) : null;
                    String ean = eanIdx != null && cellValues.size() > eanIdx ? normalize(cellValues.get(eanIdx)) : null;

                    ProductImportDTO dto = new ProductImportDTO();
                    dto.setSku(sku);
                    dto.setEan(ean);
                    dto.setNome(nome);
                    dto.setNomeCategoria(categoria);
                    dto.setPrezzoBase(prezzo);
                    dto.setDisponibilita(disponibilita);
                    result.add(dto);
                }
                rowIdx++;
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

        // Videosorveglianza
        if (containsAny(text, "videosorveglianza", "telecamera", "videocamera", "dvr", "nvr", "kit videosorveglianza")) {
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
}

