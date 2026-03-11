package com.e_commerce.service;

import com.e_commerce.dto.DocumentImportDTO;
import com.e_commerce.dto.ProductImportDTO;
import com.e_commerce.dto.SupplierImportDTO;
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ImportService {

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

    private void importProductsFromBytes(byte[] bytes,
                                        String originalFilename,
                                        String contentType,
                                        Long supplierId,
                                        boolean createImportLog) throws Exception {
        List<ProductImportDTO> rows = isExcelFile(originalFilename, contentType)
                ? parseProductsXlsxBytes(bytes, "Prodotti (Excel): header atteso almeno: sku,nome_prodotto,categoria,prezzo.")
                : parseCsvBytes(bytes, ProductImportDTO.class, "Prodotti (CSV): header atteso almeno: sku,nome_prodotto,categoria (delimitatore ',', ';' o '|').");

        Supplier supplier = null;
        if (supplierId != null) {
            supplier = supplierRepository.findById(supplierId)
                    .orElseThrow(() -> new IllegalArgumentException("Fornitore non trovato (supplierId=" + supplierId + ")."));
        }

        int rowNumber = 1; // 1-based rispetto alle righe dati (escluso header)
        for (ProductImportDTO dto : rows) {
            String sku = normalize(dto.getSku());
            if (sku == null) {
                throw new IllegalArgumentException("Riga " + rowNumber + ": campo 'sku' mancante o vuoto.");
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
            // Mappiamo la categoria originale del CSV + il nome prodotto
            // su una delle macro-categorie del catalogo.
            String mappedCategoria = mapToMainCategory(rawNomeCategoria, nomeProdotto);
            final String nomeCategoria = mappedCategoria != null
                    ? mappedCategoria
                    // fallback per evitare errori DB su Category.nome NOT NULL
                    : "Senza categoria";

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
            col = col.replace(' ', '_');
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
        return switch (col) {
            // sku
            case "codice", "code", "product_code", "productcode", "sku_prodotto",
                    "codice_articolo", "codicearticolo", "art_code", "item_code" -> "sku";

            // prodotti - nome
            case "nome" -> "nome_prodotto";
            case "nomeprodotto", "nome_del_prodotto", "prodotto", "articolo", "descrizione" -> "nome_prodotto";
            case "name", "product_name", "productname", "product", "description", "item_name" -> "nome_prodotto";

            // prodotti - prezzo
            case "prezzo_base", "prezzo_listino", "prezzobase" -> "prezzo";

            // prodotti - categoria
            case "category", "cat", "nome_categoria", "categoria_nome", "categories", "category_name" -> "categoria";

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
        if (name.endsWith(".xlsx")) return true;
        return ct.contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private List<ProductImportDTO> parseProductsXlsxBytes(byte[] bytes, String hint) {
        try {
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("File Excel vuoto. " + hint);
            }

            try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                if (workbook.getNumberOfSheets() <= 0) {
                    throw new IllegalArgumentException("File Excel senza fogli. " + hint);
                }
                Sheet sheet = workbook.getSheetAt(0);
                if (sheet == null) {
                    throw new IllegalArgumentException("Impossibile leggere il primo foglio Excel. " + hint);
                }

                Row headerRow = sheet.getRow(sheet.getFirstRowNum());
                if (headerRow == null) {
                    throw new IllegalArgumentException("Header Excel mancante. " + hint);
                }

                DataFormatter formatter = new DataFormatter();
                Map<String, Integer> headerToIndex = new HashMap<>();
                for (Cell cell : headerRow) {
                    String raw = formatter.formatCellValue(cell);
                    String col = normalizeExcelHeaderCell(raw);
                    if (col != null && !col.isBlank()) {
                        headerToIndex.put(col, cell.getColumnIndex());
                    }
                }

                Integer skuIdx = headerToIndex.get("sku");
                Integer nomeIdx = headerToIndex.get("nome_prodotto");
                Integer catIdx = headerToIndex.get("categoria");
                Integer prezzoIdx = headerToIndex.get("prezzo");

                if (skuIdx == null || catIdx == null) {
                    throw new IllegalArgumentException(
                            "Header Excel non valido. Colonne viste = [" + String.join(", ", headerToIndex.keySet()) + "]. " + hint
                    );
                }

                int firstDataRow = headerRow.getRowNum() + 1;
                int lastRow = sheet.getLastRowNum();
                java.util.ArrayList<ProductImportDTO> rows = new java.util.ArrayList<>();
                for (int r = firstDataRow; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    String sku = normalize(formatter.formatCellValue(row.getCell(skuIdx)));
                    String categoria = normalize(formatter.formatCellValue(row.getCell(catIdx)));

                    // saltiamo righe vuote
                    if (sku == null && categoria == null) continue;

                    String nome = nomeIdx != null ? normalize(formatter.formatCellValue(row.getCell(nomeIdx))) : null;
                    Double prezzo = null;
                    if (prezzoIdx != null) {
                        prezzo = readNumericCellAsDouble(row.getCell(prezzoIdx), formatter);
                    }

                    ProductImportDTO dto = new ProductImportDTO();
                    dto.setSku(sku);
                    dto.setNome(nome);
                    dto.setNomeCategoria(categoria);
                    dto.setPrezzoBase(prezzo);
                    rows.add(dto);
                }
                return rows;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Impossibile leggere il file Excel. " + hint, e);
        }
    }

    private String normalizeExcelHeaderCell(String raw) {
        if (raw == null) return null;
        String col = raw.trim().toLowerCase();
        if (col.startsWith("\"") && col.endsWith("\"") && col.length() >= 2) {
            col = col.substring(1, col.length() - 1);
        }
        col = col.trim();
        col = col.replace(' ', '_');
        return aliasHeader(col);
    }

    private Double readNumericCellAsDouble(Cell cell, DataFormatter formatter) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return cell.getNumericCellValue();
            }
            if (cell.getCellType() == CellType.STRING) {
                String s = normalize(cell.getStringCellValue());
                if (s == null) return null;
                s = s.replace(",", "."); // supporto decimali IT
                return Double.parseDouble(s);
            }
            String formatted = normalize(formatter.formatCellValue(cell));
            if (formatted == null) return null;
            formatted = formatted.replace(",", ".");
            return Double.parseDouble(formatted);
        } catch (Exception ignored) {
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

        // Accessori
        if (containsAny(text, "accessorio", "accessori", "custodia", "zaino", "borsa", "supporto", "stand", "adattatore", "adapter", "hub", "dock", "docking")) {
            return "Accessori";
        }

        // Scuola e Laboratori
        if (containsAny(text, "scuola", "laboratorio", "laboratori", "didattica", "didattico", "lim", "microscopio", "kit elettronica", "kit didattico")) {
            return "Scuola e Laboratori";
        }

        // Nessuna regola ha fatto match: lascio null per permettere un fallback.
        return null;
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

