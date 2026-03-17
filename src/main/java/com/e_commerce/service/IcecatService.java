package com.e_commerce.service;

import com.e_commerce.model.Document;
import com.e_commerce.model.Product;
import com.e_commerce.repository.DocumentRepository;
import com.e_commerce.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Servizio per scaricare immagini e descrizioni da Icecat e salvarle in una cartella locale.
 * Le immagini vengono salvate in storage/product-data/{productId}/images/
 * Le descrizioni aggiornano product.descrizione e vengono salvate in descrizione.txt
 */
@Service
public class IcecatService {

    private static final Logger log = LoggerFactory.getLogger(IcecatService.class);
    private static final String ICECAT_API = "https://data.icecat.biz/xml_s3/xml_server3.cgi";
    private static final String ICECAT_INDEX_OPEN = "https://data.icecat.biz/export/freexml";
    private static final String ICECAT_INDEX_FULL = "https://data.icecat.biz/export/level4";
    private static final int MAX_IMAGES_PER_PRODUCT = 3;

    private final ProductRepository productRepository;
    private final DocumentRepository documentRepository;

    @Value("${icecat.lang:IT}")
    private String lang;

    @Value("${icecat.username:}")
    private String username;

    @Value("${icecat.password:}")
    private String password;

    @Value("${icecat.storage-path:./storage/product-data}")
    private String storagePath;

    @Value("${icecat.cache-path:./storage/icecat-cache}")
    private String cachePath;

    /** Path classpath dell'immagine di default (da public "immagine nn disponibile.png") */
    private static final String DEFAULT_IMAGE_CLASSPATH = "/static/images/immagine-non-disponibile.png";

    public IcecatService(ProductRepository productRepository, DocumentRepository documentRepository) {
        this.productRepository = productRepository;
        this.documentRepository = documentRepository;
    }

    @PostConstruct
    public void initStorage() {
        try {
            Path base = Paths.get(storagePath).toAbsolutePath();
            Files.createDirectories(base);
            Path cache = Paths.get(cachePath != null ? cachePath : "./storage/icecat-cache").toAbsolutePath();
            Files.createDirectories(cache);
            log.info("Icecat storage: {}, cache: {}", base, cache);
            boolean hasAuth = username != null && !username.isBlank() && password != null && !password.isBlank();
            log.info("Icecat auth: {} (user: {})", hasAuth ? "configurato" : "NON configurato - usa icecat.username e icecat.password in application.properties",
                    hasAuth ? username : "?");
        } catch (IOException e) {
            log.warn("Impossibile creare cartella storage Icecat: {}", e.getMessage());
        }
    }

    /**
     * Normalizza EAN: rimuove spazi, eventuale zero iniziale, e verifica formato.
     */
    private String normalizeEan(String ean) {
        if (ean == null) return null;
        String s = ean.trim().replaceAll("\\s", "");
        if (s.isEmpty()) return null;
        if (!s.matches("\\d+")) return null;
        if (s.length() == 12) s = "0" + s;
        if (s.length() >= 8 && s.length() <= 14) return s;
        return null;
    }

    /**
     * Estrae EAN valido da ean/sku (es. "EAN-8057284622826" o "8057284622826" -> "8057284622826").
     */
    private String resolveEanForLookup(String eanOrSku) {
        if (eanOrSku == null || eanOrSku.isBlank()) return null;
        String digits = eanOrSku.trim().replaceAll("\\D", "");
        return normalizeEan(digits);
    }

    /**
     * Scarica un'immagine da URL e la salva nella cartella del prodotto.
     * @return path relativo per servire l'immagine (es. /api/images/product/123/img_0.jpg) o null se fallisce
     */
    private String downloadImageToStorage(String imageUrl, Long productId, int index) {
        try {
            Path productDir = Paths.get(storagePath, String.valueOf(productId), "images");
            Files.createDirectories(productDir);

            String ext = ".jpg";
            int dot = imageUrl.lastIndexOf('.');
            if (dot > 0 && dot < imageUrl.length() - 1) {
                String e = imageUrl.substring(dot).split("[?]")[0].toLowerCase();
                if (e.matches("\\.(jpeg|jpg|png|gif|webp)")) ext = e;
            }
            String filename = "img_" + index + ext;

            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;

            byte[] body = response.body();
            if (body == null || body.length == 0) return null;

            Path filePath = productDir.resolve(filename);
            Files.write(filePath, body);

            return "/api/images/product/" + productId + "/" + filename;
        } catch (Exception e) {
            log.warn("Errore download immagine {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Copia l'immagine di default dal classpath (static/images/immagine-non-disponibile.png)
     * nella cartella storage del prodotto.
     * @return path relativo per servire l'immagine o null se fallisce
     */
    private String copyDefaultImageToStorage(Long productId) {
        try (java.io.InputStream is = getClass().getResourceAsStream(DEFAULT_IMAGE_CLASSPATH)) {
            if (is == null) {
                log.warn("Icecat: immagine di default non trovata nel classpath: {}", DEFAULT_IMAGE_CLASSPATH);
                return null;
            }
            Path productDir = Paths.get(storagePath, String.valueOf(productId), "images");
            Files.createDirectories(productDir);
            String filename = "img_0.png";
            Path filePath = productDir.resolve(filename);
            Files.copy(is, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "/api/images/product/" + productId + "/" + filename;
        } catch (IOException e) {
            log.warn("Icecat: impossibile copiare immagine di default per prodotto {}: {}", productId, e.getMessage());
            return null;
        }
    }

    /**
     * Chiave cache stabile: EAN > marca_codiceProduttore > sku.
     * Usata per evitare re-download dopo reset catalogo o re-import.
     */
    private String getCacheKey(Product p) {
        String ean = resolveEanForLookup(p.getEan() != null ? p.getEan().trim() : null);
        if (ean != null) return sanitizeCacheKey("ean_" + ean);
        String marca = p.getMarca() != null ? p.getMarca().trim() : "";
        String codice = p.getCodiceProduttore() != null ? p.getCodiceProduttore().trim() : "";
        if (!marca.isBlank() && !codice.isBlank()) {
            return sanitizeCacheKey("brand_" + marca + "_" + codice);
        }
        String sku = p.getSku() != null ? p.getSku().trim() : "";
        return sanitizeCacheKey("sku_" + (sku.isBlank() ? "unknown" : sku));
    }

    private String sanitizeCacheKey(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").replaceAll("_+", "_");
    }

    /**
     * Copia immagini dalla cache al prodotto. Ritorna numero di immagini copiate o -1 se cache vuota.
     */
    private int copyFromCacheToProduct(Product product, String cacheKey) {
        Path cacheDir = Paths.get(cachePath != null ? cachePath : "./storage/icecat-cache", cacheKey);
        if (!Files.isDirectory(cacheDir)) return -1;
        Path productDir = Paths.get(storagePath, String.valueOf(product.getId()), "images");
        // Rimuovi vecchie immagini prima di copiare dalla cache
        List<Document> toRemove = new ArrayList<>(product.getDocumenti()).stream()
                .filter(d -> d.getTipo() != null && "immagine".equalsIgnoreCase(d.getTipo()))
                .collect(Collectors.toList());
        for (Document d : toRemove) {
            product.getDocumenti().remove(d);
            documentRepository.delete(d);
        }
        int count = 0;
        for (int i = 0; i < MAX_IMAGES_PER_PRODUCT; i++) {
            Path src = findImageByIndex(cacheDir, i);
            if (src == null) break;
            try {
                Files.createDirectories(productDir);
                String ext = src.getFileName().toString().substring(src.getFileName().toString().lastIndexOf('.'));
                String filename = "img_" + i + ext;
                Path dest = productDir.resolve(filename);
                Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Document doc = new Document();
                doc.setTipo("immagine");
                doc.setUrl("/api/images/product/" + product.getId() + "/" + filename);
                doc.setOrdine(i);
                doc.setProduct(product);
                documentRepository.save(doc);
                product.getDocumenti().add(doc);
                count++;
            } catch (IOException e) {
                log.warn("Icecat cache: errore copia img {} per prodotto {}: {}", i, product.getId(), e.getMessage());
            }
        }
        if (count > 0) {
            Path descFile = cacheDir.resolve("descrizione.txt");
            if (Files.isRegularFile(descFile)) {
                try {
                    product.setDescrizione(Files.readString(descFile));
                } catch (IOException e) {
                    log.debug("Cache: descrizione non leggibile: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    /** Copia l'immagine di default nella cache per riuso dopo reset. */
    private void copyDefaultToCache(Long productId, String cacheKey) {
        if (cacheKey == null) return;
        try {
            Path src = findImageByIndex(Paths.get(storagePath, String.valueOf(productId), "images"), 0);
            if (src != null) {
                Path cacheDir = Paths.get(cachePath != null ? cachePath : "./storage/icecat-cache", cacheKey);
                Files.createDirectories(cacheDir);
                Files.copy(src, cacheDir.resolve("img_0.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.debug("Cache: impossibile salvare default per {}: {}", cacheKey, e.getMessage());
        }
    }

    /** Trova img_N.ext in una cartella (cache o product). */
    private Path findImageByIndex(Path dir, int index) {
        for (String ext : new String[]{"jpg", "jpeg", "png", "gif", "webp"}) {
            Path p = dir.resolve("img_" + index + "." + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /**
     * Copia le immagini appena scaricate dalla cartella prodotto alla cache.
     */
    private void copyProductImagesToCache(Long productId, String cacheKey, IcecatData data, int count) {
        if (cacheKey == null || count <= 0) return;
        try {
            Path productDir = Paths.get(storagePath, String.valueOf(productId), "images");
            Path cacheDir = Paths.get(cachePath != null ? cachePath : "./storage/icecat-cache", cacheKey);
            Files.createDirectories(cacheDir);
            for (int i = 0; i < count; i++) {
                Path src = findImageByIndex(productDir, i);
                if (src != null) {
                    Files.copy(src, cacheDir.resolve(src.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (data.description() != null && !data.description().isBlank()) {
                Files.writeString(cacheDir.resolve("descrizione.txt"), data.description(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Icecat cache: impossibile salvare per {}: {}", cacheKey, e.getMessage());
        }
    }


    /**
     * Aggiunge l'immagine di default quando Icecat non trova immagini.
     * Usa l'immagine "immagine non disponibile" da static/images.
     */
    private int addDefaultImageIfConfigured(Product product) {
        Long productId = product.getId();
        List<Document> toRemove = new ArrayList<>(product.getDocumenti()).stream()
                .filter(d -> d.getTipo() != null && "immagine".equalsIgnoreCase(d.getTipo()))
                .collect(Collectors.toList());
        for (Document d : toRemove) {
            product.getDocumenti().remove(d);
            documentRepository.delete(d);
        }
        String localPath = copyDefaultImageToStorage(productId);
        if (localPath != null) {
            Document doc = new Document();
            doc.setTipo("immagine");
            doc.setUrl(localPath);
            doc.setOrdine(0);
            doc.setProduct(product);
            documentRepository.save(doc);
            product.getDocumenti().add(doc);
            // Salva in cache per evitare re-copia da classpath dopo reset
            copyDefaultToCache(productId, getCacheKey(product));
            productRepository.save(product);
            log.info("Icecat: immagine di default aggiunta per prodotto {} (SKU: {})", productId, product.getSku());
            return 1;
        }
        log.warn("Icecat: impossibile copiare immagine di default per prodotto {}", productId);
        return 0;
    }

    /**
     * Recupera XML prodotto da Icecat e restituisce immagini + descrizione.
     */
    public IcecatData fetchProductData(String ean) {
        String normalized = resolveEanForLookup(ean);
        if (normalized == null) {
            log.info("Icecat: EAN non valido (estrai 8-14 cifre da ean/sku): {}", ean);
            return null;
        }
        IcecatData data = fetchProductDataWithLang(ean, normalized, lang != null ? lang : "IT");
        if (data == null) {
            data = fetchProductDataWithLang(ean, normalized, "EN");
        }
        return data;
    }

    private IcecatData fetchProductDataWithLang(String ean, String normalized, String langParam) {
        String url = ICECAT_API + "?lang=" + langParam + "&ean_upc=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8) + "&output=productxml";

        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Accept", "application/xml, text/xml, */*")
                    .header("Accept-Language", lang != null ? lang : "IT")
                    .GET();

            if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + auth);
            }

            HttpResponse<byte[]> responseBytes = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = responseBytes.statusCode();
            byte[] bodyBytes = responseBytes.body();
            // Icecat invia content-encoding: gzip - decomprimere prima di leggere come XML
            String contentEncoding = responseBytes.headers().firstValue("Content-Encoding").orElse("");
            byte[] raw = bodyBytes;
            if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip") && bodyBytes != null && bodyBytes.length > 0) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(bodyBytes);
                     GZIPInputStream gzis = new GZIPInputStream(bais);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = gzis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    raw = baos.toByteArray();
                } catch (IOException e) {
                    log.warn("Icecat: errore decompressione gzip: {}", e.getMessage());
                }
            }
            String xml = (raw != null && raw.length > 0)
                    ? new String(raw, StandardCharsets.UTF_8)
                    : "";

            if (status == 401) {
                log.warn("Icecat: 401 Unauthorized per EAN {}. Verifica credenziali Full Icecat.", normalized);
                return null;
            }
            if (status != 200) return null;
            if (xml == null || xml.isBlank()) return null;
            if (xml.toLowerCase().contains("product not found") || xml.contains("NO_SUCH_PRODUCT") || xml.contains("no results")
                    || xml.contains("Code=\"-1\"") || xml.contains("ErrorMessage=")) {
                return null;
            }
            return parseIcecatDataFromXml(xml);
        } catch (Exception e) {
            log.warn("Icecat: errore per EAN {}: {}", normalized, e.getMessage());
            return null;
        }
    }

    /**
     * Recupera XML prodotto da Icecat tramite vendor (marca) + prod_id (codice produttore).
     * Usato come fallback quando la ricerca per EAN non restituisce risultati.
     */
    public IcecatData fetchProductDataByBrandAndProdId(String vendor, String prodId) {
        if (vendor == null || vendor.isBlank() || prodId == null || prodId.isBlank()) {
            return null;
        }
        String v = vendor.trim();
        String p = prodId.trim().toUpperCase();
        String url = ICECAT_API + "?lang=" + lang + "&vendor=" + URLEncoder.encode(v, StandardCharsets.UTF_8)
                + "&prod_id=" + URLEncoder.encode(p, StandardCharsets.UTF_8) + "&output=productxml";

        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Accept", "application/xml, text/xml, */*")
                    .header("Accept-Language", lang != null ? lang : "IT")
                    .GET();

            if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + auth);
            }

            HttpResponse<byte[]> responseBytes = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = responseBytes.statusCode();
            byte[] bodyBytes = responseBytes.body();
            String contentEncoding = responseBytes.headers().firstValue("Content-Encoding").orElse("");
            byte[] raw = bodyBytes;
            if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip") && bodyBytes != null && bodyBytes.length > 0) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(bodyBytes);
                     GZIPInputStream gzis = new GZIPInputStream(bais);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = gzis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    raw = baos.toByteArray();
                } catch (IOException e) {
                    log.warn("Icecat: errore decompressione gzip (brand): {}", e.getMessage());
                }
            }
            String xml = (raw != null && raw.length > 0)
                    ? new String(raw, StandardCharsets.UTF_8)
                    : "";

            if (status == 401) {
                log.warn("Icecat: 401 Unauthorized per vendor={} prod_id={}", v, p);
                return null;
            }
            if (status != 200) {
                log.debug("Icecat: API risposta {} per vendor={} prod_id={}", status, v, p);
                return null;
            }
            if (xml == null || xml.isBlank()) return null;
            if (xml.toLowerCase().contains("product not found") || xml.contains("NO_SUCH_PRODUCT") || xml.contains("no results")) {
                return null;
            }
            if (xml.contains("Code=\"-1\"") || xml.contains("ErrorMessage=")) return null;

            return parseIcecatDataFromXml(xml);
        } catch (Exception e) {
            log.debug("Icecat: errore per vendor={} prod_id={}: {}", v, p, e.getMessage());
            return null;
        }
    }

    /**
     * Recupera XML prodotto da Icecat tramite Icecat product ID.
     */
    public IcecatData fetchProductDataByIcecatId(long icecatId) {
        String url = ICECAT_API + "?lang=" + lang + "&icecat_id=" + icecatId + "&output=productxml";
        return fetchProductXmlFromUrl(url);
    }

    private IcecatData fetchProductXmlFromUrl(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Accept", "application/xml, text/xml, */*")
                    .header("Accept-Language", lang != null ? lang : "IT")
                    .GET();
            if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + auth);
            }
            HttpResponse<byte[]> responseBytes = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = responseBytes.statusCode();
            byte[] bodyBytes = responseBytes.body();
            String contentEncoding = responseBytes.headers().firstValue("Content-Encoding").orElse("");
            byte[] raw = bodyBytes;
            if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip") && bodyBytes != null && bodyBytes.length > 0) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(bodyBytes);
                     GZIPInputStream gzis = new GZIPInputStream(bais);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = gzis.read(buffer)) > 0) baos.write(buffer, 0, len);
                    raw = baos.toByteArray();
                } catch (IOException e) {
                    log.warn("Icecat: errore decompressione gzip: {}", e.getMessage());
                }
            }
            String xml = (raw != null && raw.length > 0) ? new String(raw, StandardCharsets.UTF_8) : "";
            if (status != 200 || xml == null || xml.isBlank()) return null;
            if (xml.toLowerCase().contains("product not found") || xml.contains("NO_SUCH_PRODUCT") || xml.contains("no results")) return null;
            if (xml.contains("Code=\"-1\"") || xml.contains("ErrorMessage=")) return null;
            return parseIcecatDataFromXml(xml);
        } catch (Exception e) {
            log.debug("Icecat: errore fetch URL: {}", e.getMessage());
            return null;
        }
    }

    /** Candidato dall'indice Icecat con punteggio di similarità al nome cercato. */
    private static final class IndexCandidate {
        final String vendor;
        final String prodId;
        final String productId;
        final String modelName;
        final double score;

        IndexCandidate(String vendor, String prodId, String productId, String modelName, double score) {
            this.vendor = vendor;
            this.prodId = prodId;
            this.productId = productId;
            this.modelName = modelName;
            this.score = score;
        }
    }

    /**
     * Similarità tra due stringhe normalizzate (0 = nessuna, 1 = identiche).
     * Basata su parole in comune e contenimento.
     */
    private double similarityScore(String searchTerm, String modelNameNormalized) {
        if (searchTerm == null || modelNameNormalized == null) return 0;
        if (modelNameNormalized.contains(searchTerm)) return 0.9 + (0.1 * searchTerm.length() / Math.max(1, modelNameNormalized.length()));
        if (searchTerm.contains(modelNameNormalized)) return 0.7 + (0.2 * modelNameNormalized.length() / Math.max(1, searchTerm.length()));
        String[] searchWords = searchTerm.split("\\s+");
        String[] modelWords = modelNameNormalized.split("\\s+");
        int match = 0;
        for (String sw : searchWords) {
            if (sw.length() < 1) continue;
            if (sw.length() == 1 && !Character.isDigit(sw.charAt(0))) continue;
            for (String mw : modelWords) {
                if (mw.contains(sw) || sw.contains(mw)) { match++; break; }
            }
        }
        if (match == 0) return 0;
        double wordScore = (2.0 * match) / (searchWords.length + modelWords.length);
        double lengthRatio = Math.min(searchTerm.length(), modelNameNormalized.length()) / (double) Math.max(searchTerm.length(), modelNameNormalized.length());
        return 0.5 * wordScore + 0.3 * lengthRatio + 0.2 * (match >= searchWords.length ? 1 : (double) match / searchWords.length);
    }

    /** Normalizza per match: minuscolo, unifica spazi, rimuove spazi tra cifre e lettere (es. "5 mp" -> "5mp"). */
    private String normalizeForMatch(String s) {
        if (s == null) return "";
        String n = s.trim().toLowerCase().replaceAll("\\s+", " ").replaceAll("[^a-z0-9\\s-]", "");
        n = n.replaceAll("(\\d)\\s+(?=[a-z])", "$1").replaceAll("(?<=[a-z])\\s+(\\d)", "$1");
        return n;
    }

    /**
     * Genera molte varianti del nome per ricerca molto permissiva: nome completo, parole singole,
     * sottostringhe, modelli, codici, prime N parole. Aggiunge coppie tipo "dome 5mp" per prodotti CCTV/telecamere.
     */
    private List<String> buildSearchVariants(String productName) {
        List<String> variants = new ArrayList<>();
        if (productName == null || productName.isBlank()) return variants;
        String norm = normalizeForMatch(productName);
        if (norm.length() < 1) return variants;
        variants.add(norm);

        String[] words = norm.split("\\s+");
        for (String w : words) {
            if (w.length() >= 2 && !variants.contains(w)) variants.add(w);
        }
        StringBuilder keyWords = new StringBuilder();
        StringBuilder modelLike = new StringBuilder();
        for (String w : words) {
            if (w.length() < 2) continue;
            if (w.matches(".*\\d+.*") || w.length() >= 3) keyWords.append(w).append(" ");
            modelLike.append(w).append(" ");
        }
        String kw = keyWords.toString().trim();
        if (kw.length() >= 2 && !variants.contains(kw)) variants.add(kw);
        String ml = modelLike.toString().trim();
        if (ml.length() >= 2 && !variants.contains(ml)) variants.add(ml);

        for (int take = 2; take <= Math.min(5, words.length); take++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < take && i < words.length; i++) {
                if (words[i].length() >= 2) sb.append(words[i]).append(" ");
            }
            String f = sb.toString().trim();
            if (f.length() >= 3 && !variants.contains(f)) variants.add(f);
        }

        if (norm.length() >= 6) {
            String first = norm.substring(0, Math.min(8, norm.length()));
            if (!variants.contains(first)) variants.add(first);
        }
        for (int i = 0; i < words.length; i++) {
            for (int j = i + 1; j < words.length; j++) {
                if (words[i].length() >= 2 && words[j].length() >= 2
                        && (words[i].matches(".*\\d+.*") || words[j].matches(".*\\d+.*") || i < 3)) {
                    String a = words[i] + " " + words[j];
                    if (!variants.contains(a)) variants.add(a);
                }
            }
        }
        return variants;
    }

    /** Match molto permissivo: qualsiasi parola, sottostringa 3+ caratteri, o overlap minimo. */
    private boolean isMatchForTerm(String searchTerm, String normModel) {
        if (searchTerm == null || normModel == null) return false;
        if (searchTerm.isEmpty() || normModel.isEmpty()) return false;
        if (normModel.contains(searchTerm) || searchTerm.contains(normModel)) return true;
        for (String w : searchTerm.split("\\s+")) {
            if (w.length() >= 2 && normModel.contains(w)) return true;
            if (w.length() == 1 && Character.isLetterOrDigit(w.charAt(0)) && normModel.contains(w)) return true;
        }
        for (int len = 3; len <= Math.min(12, searchTerm.length()); len++) {
            for (int i = 0; i <= searchTerm.length() - len; i++) {
                if (normModel.contains(searchTerm.substring(i, i + len))) return true;
            }
        }
        return false;
    }

    /** Match ultra-permissivo: qualsiasi overlap di 3+ caratteri (usato se pochi candidati). */
    private boolean isMatchVeryPermissive(String searchTerm, String normModel) {
        if (searchTerm == null || normModel == null) return false;
        String s = searchTerm.replaceAll("\\s", "");
        String m = normModel.replaceAll("\\s", "");
        for (int len = 3; len <= Math.min(10, s.length()); len++) {
            for (int i = 0; i <= s.length() - len; i++) {
                if (m.contains(s.substring(i, i + len))) return true;
            }
        }
        return false;
    }

    /**
     * Cerca nell'indice Icecat per nome prodotto (model_name). Se non trova un match esatto,
     * raccoglie i candidati più simili (anche con varianti ridotte del nome), li ordina per similarità
     * e prova fino a trovare un prodotto con immagini.
     */
    public IcecatData fetchProductDataByProductName(String productName) {
        if (productName == null || productName.isBlank()) return null;
        List<String> searchVariants = buildSearchVariants(productName);
        if (searchVariants.isEmpty()) return null;

        String langCode = (lang != null && !lang.isBlank()) ? lang : "IT";
        String baseUrl = (username != null && !username.isBlank() && password != null && !password.isBlank())
                ? ICECAT_INDEX_FULL : ICECAT_INDEX_OPEN;
        String indexUrl = baseUrl + "/" + langCode + "/files.index.csv.gz";

        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(indexUrl))
                    .timeout(java.time.Duration.ofSeconds(300))
                    .GET();
            if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + auth);
            }
            HttpResponse<java.io.InputStream> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                log.debug("Icecat: index non scaricabile {} per {}", response.statusCode(), indexUrl);
                return null;
            }

            int modelNameIdx = -1;
            int vendorIdx = -1;
            int prodIdIdx = -1;
            int productIdIdx = -1;

            try (GZIPInputStream gz = new GZIPInputStream(response.body());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8))) {
                String headerLine = reader.readLine();
                if (headerLine == null) return null;
                String[] headers = parseCsvLine(headerLine);
                for (int i = 0; i < headers.length; i++) {
                    String h = headers[i].trim().toLowerCase();
                    if ("model_name".equals(h)) modelNameIdx = i;
                    else if ("m_supplier_name".equals(h)) vendorIdx = i;
                    else if ("prod_id".equals(h)) prodIdIdx = i;
                    else if ("product_id".equals(h)) productIdIdx = i;
                }
                if (modelNameIdx < 0 || (vendorIdx < 0 && productIdIdx < 0)) return null;

                List<IndexCandidate> candidates = new ArrayList<>();
                Set<String> seenIds = new HashSet<>();
                final int maxCandidates = 300;
                final int maxLinesToScan = 800_000;
                int linesRead = 0;
                String line;

                while ((line = reader.readLine()) != null && linesRead < maxLinesToScan) {
                    linesRead++;
                    String[] cols = parseCsvLine(line);
                    if (modelNameIdx >= cols.length) continue;
                    String modelName = cols[modelNameIdx];
                    if (modelName == null || modelName.isBlank()) continue;
                    String normModel = normalizeForMatch(modelName);

                    double bestScore = 0;
                    for (String variant : searchVariants) {
                        if (isMatchForTerm(variant, normModel)) {
                            double s = similarityScore(variant, normModel);
                            if (s > bestScore) bestScore = s;
                        }
                    }
                    if (bestScore <= 0) {
                        for (String variant : searchVariants) {
                            if (isMatchVeryPermissive(variant, normModel)) {
                                bestScore = 0.15;
                                break;
                            }
                        }
                    }
                    if (bestScore <= 0) continue;

                    String v = (vendorIdx >= 0 && vendorIdx < cols.length) ? (cols[vendorIdx] != null ? cols[vendorIdx].trim() : "") : "";
                    String p = (prodIdIdx >= 0 && prodIdIdx < cols.length) ? (cols[prodIdIdx] != null ? cols[prodIdIdx].trim() : "") : "";
                    String pid = (productIdIdx >= 0 && productIdIdx < cols.length) ? (cols[productIdIdx] != null ? cols[productIdIdx].trim() : "") : "";
                    String dedupeKey = pid.matches("\\d+") ? ("id:" + pid) : ("v:" + v + "|p:" + p);
                    if (seenIds.contains(dedupeKey)) continue;
                    if (!pid.matches("\\d+") && (v.isEmpty() || p.isEmpty())) continue;
                    seenIds.add(dedupeKey);

                    candidates.add(new IndexCandidate(v, p, pid, modelName, bestScore));
                    if (candidates.size() >= maxCandidates) break;
                }

                candidates.sort(Comparator.comparingDouble((IndexCandidate c) -> c.score).reversed());

                for (IndexCandidate c : candidates) {
                    if (c.productId != null && !c.productId.isEmpty() && c.productId.matches("\\d+")) {
                        IcecatData d = fetchProductDataByIcecatId(Long.parseLong(c.productId));
                        if (d != null && !d.imageUrls().isEmpty()) {
                            log.info("Icecat: trovato per nome '{}' (più simile: '{}', score={})", productName, c.modelName, String.format("%.2f", c.score));
                            return d;
                        }
                    }
                    if (c.vendor != null && !c.vendor.isEmpty() && c.prodId != null && !c.prodId.isEmpty()) {
                        IcecatData d = fetchProductDataByBrandAndProdId(c.vendor, c.prodId);
                        if (d != null && !d.imageUrls().isEmpty()) {
                            log.info("Icecat: trovato per nome '{}' (più simile: '{}', score={})", productName, c.modelName, String.format("%.2f", c.score));
                            return d;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Icecat: ricerca per nome fallita: {}", e.getMessage());
        }
        return null;
    }

    /** Parsing CSV semplificato: supporta virgolette. */
    private String[] parseCsvLine(String line) {
        if (line == null) return new String[0];
        java.util.List<String> result = new java.util.ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) {
                result.add(sb.toString().replaceAll("^\"|\"$", "").trim());
                sb.setLength(0);
            } else sb.append(c);
        }
        result.add(sb.toString().replaceAll("^\"|\"$", "").trim());
        return result.toArray(new String[0]);
    }

    private IcecatData parseIcecatDataFromXml(String xml) {
        List<String> imageUrls = new ArrayList<>();
        String description = null;
        try {
            // Risposta errore Icecat (File does not exist, ecc.): ritorna vuoto senza log
            if (xml != null && (xml.contains("Code=\"-1\"") || xml.contains("ErrorMessage=") || xml.contains("File does not exist"))) {
                return new IcecatData(imageUrls, description);
            }
            // Prima estrai immagini con regex (affidabile su XML Icecat reale)
            Set<String> regexUrls = extractImageUrlsByRegex(xml);
            imageUrls.addAll(regexUrls);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setIgnoringElementContentWhitespace(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document xmlDoc = builder.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Element root = xmlDoc.getDocumentElement();
            if (root == null) return new IcecatData(imageUrls, description);

            Element productEl = findProductElement(root);
            if (productEl == null) {
                return new IcecatData(imageUrls, description);
            }

            // Se regex non ha trovato nulla, prova DOM (attributi Product e ProductPicture)
            if (imageUrls.isEmpty()) {
                Set<String> urls = new HashSet<>();
                for (String attr : new String[]{"HighPic", "Pic500x500", "ThumbPic", "Pic", "LowPic"}) {
                    String val = getAttribute(productEl, attr);
                    if (val != null && !val.isBlank()) urls.add(cleanUrl(val));
                }
                NodeList galleryList = findElementsByLocalName(productEl, "ProductGallery");
                for (int i = 0; i < galleryList.getLength(); i++) {
                    Node gallery = galleryList.item(i);
                    if (gallery.getNodeType() != Node.ELEMENT_NODE) continue;
                    NodeList pictures = findElementsByLocalName((Element) gallery, "ProductPicture");
                    for (int j = 0; j < pictures.getLength(); j++) {
                        Node pic = pictures.item(j);
                        if (pic.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element picEl = (Element) pic;
                        for (String attr : new String[]{"HighPic", "Pic", "Pic500x500", "ThumbPic", "Original"}) {
                            String val = getAttribute(picEl, attr);
                            if (val != null && !val.isBlank()) urls.add(cleanUrl(val));
                        }
                    }
                }
                imageUrls.addAll(urls);
            }

            // Descrizione: ProductDescription/LongDesc, attributi Product, contenuto testo
            NodeList descNodes = findElementsByLocalName(productEl, "ProductDescription");
            for (int i = 0; i < descNodes.getLength() && (description == null || description.isBlank()); i++) {
                Node dn = descNodes.item(i);
                if (dn.getNodeType() == Node.ELEMENT_NODE) {
                    description = getAttribute((Element) dn, "LongDesc");
                    if (description == null || description.isBlank()) {
                        String text = dn.getTextContent();
                        if (text != null && !text.trim().isBlank()) description = text.trim();
                    }
                }
            }
            if (description == null || description.isBlank()) {
                description = getAttribute(productEl, "LongDesc");
            }
            if (description == null || description.isBlank()) {
                description = getAttribute(productEl, "Model_Name");
            }
            if (description == null || description.isBlank()) {
                description = getAttribute(productEl, "ShortDesc");
            }
            if (description != null) {
                description = description.trim();
                if (description.length() > 10000) description = description.substring(0, 10000);
            }

            if (imageUrls.isEmpty()) {
                String preview = (xml != null && xml.length() > 0) ? xml.substring(0, Math.min(300, xml.length())).replaceAll("\\s+", " ") : "";
                log.warn("Icecat: 0 immagini. XML len={}, HighPic={}, preview=[{}]",
                        xml != null ? xml.length() : 0, xml != null && xml.contains("HighPic"), preview);
            }
        } catch (Exception e) {
            log.warn("Errore parsing XML Icecat: {}", e.getMessage());
        }
        return new IcecatData(imageUrls, description);
    }

    /** Estrae URL immagini dall'XML Icecat. Supporta images.icecat.biz, URL relativi, entity-encoding. */
    private Set<String> extractImageUrlsByRegex(String xml) {
        Set<String> urls = new LinkedHashSet<>();
        if (xml == null || xml.isBlank()) return urls;
        // Cerca vari pattern di URL Icecat
        String[] needles = {
                "https://images.icecat.biz/",
                "http://images.icecat.biz/",
                "//images.icecat.biz/"
        };
        for (String needle : needles) {
            int idx = 0;
            while ((idx = xml.indexOf(needle, idx)) >= 0) {
                int start = needle.startsWith("//") ? idx : idx;
                int end = idx + needle.length();
                while (end < xml.length()) {
                    char c = xml.charAt(end);
                    if (c == '"' || c == '\'' || c == ' ' || c == '<' || c == '>' || c == '\n' || c == '\r' || c == '\t') break;
                    end++;
                }
                String raw = xml.substring(start, end);
                String url = needle.startsWith("//") ? "https:" + raw : raw;
                if (url.length() > 20) urls.add(cleanUrl(url));
                idx = end;
            }
        }
        // URL relativi (es. /img/gallery/...)
        Pattern relPattern = Pattern.compile("[\"'](/img/[^\"'\\s<>]+)[\"']");
        Matcher relM = relPattern.matcher(xml);
        while (relM.find()) {
            String path = relM.group(1);
            urls.add(cleanUrl("https://images.icecat.biz" + path));
        }
        // Regex fallback per URL con entity encoding (&amp;)
        if (urls.isEmpty()) {
            Pattern p = Pattern.compile("(https?://images\\.icecat\\.biz/[^\"'\\s<>]+)");
            Matcher m = p.matcher(xml);
            while (m.find()) {
                String url = cleanUrl(m.group(1));
                if (url != null && !url.isBlank()) urls.add(url);
            }
        }
        return urls;
    }

    /** Trova l'elemento Product (gestisce namespace Icecat). */
    private Element findProductElement(Element root) {
        if (root == null) return null;
        String tag = root.getTagName();
        if (tag != null && (tag.endsWith(":Product") || "Product".equalsIgnoreCase(tag))) {
            return root;
        }
        NodeList nl = root.getElementsByTagNameNS("*", "Product");
        if (nl != null && nl.getLength() > 0) return (Element) nl.item(0);
        nl = root.getElementsByTagName("Product");
        if (nl != null && nl.getLength() > 0) return (Element) nl.item(0);
        // Cerca nell'intero documento (alcuni XML hanno struttura diversa)
        org.w3c.dom.Document doc = root.getOwnerDocument();
        if (doc != null) {
            nl = doc.getElementsByTagNameNS("*", "Product");
            if (nl != null && nl.getLength() > 0) return (Element) nl.item(0);
            nl = doc.getElementsByTagName("Product");
            if (nl != null && nl.getLength() > 0) return (Element) nl.item(0);
        }
        return null;
    }

    /** Trova elementi per nome locale (gestisce namespace). */
    private NodeList findElementsByLocalName(Element parent, String localName) {
        NodeList nl = parent.getElementsByTagNameNS("*", localName);
        if (nl.getLength() > 0) return nl;
        return parent.getElementsByTagName(localName);
    }

    private String getAttribute(Element el, String name) {
        String val = el.getAttribute(name);
        if (val == null || val.isBlank()) {
            val = el.getAttributeNS(null, name);
        }
        return (val != null && !val.isBlank()) ? val : null;
    }

    private String cleanUrl(String url) {
        if (url == null) return null;
        url = url.trim();
        if (url.startsWith("<") && url.endsWith(">")) {
            url = url.substring(1, url.length() - 1);
        }
        // Decodifica entity XML comuni negli URL
        url = url.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        return url;
    }

    /**
     * Sincronizza immagini e descrizione Icecat per un prodotto: prima controlla la cache,
     * poi Icecat. La cache evita re-download dopo reset catalogo o re-import.
     */
    public int syncImagesForProduct(Long productId) {
        Product product = productRepository.findByIdWithAssociations(productId).orElse(null);
        if (product == null) return 0;

        String cacheKey = getCacheKey(product);
        int fromCache = copyFromCacheToProduct(product, cacheKey);
        if (fromCache > 0) {
            productRepository.save(product);
            log.info("Icecat: {} immagini da cache per prodotto {} (SKU: {}, key: {})", fromCache, productId, product.getSku(), cacheKey);
            return fromCache;
        }

        String eanRaw = product.getEan() != null ? product.getEan().trim() : product.getSku();
        String ean = resolveEanForLookup(eanRaw);
        // Priorità: con EAN → EAN poi nome poi marca; senza EAN → nome poi marca
        IcecatData data = null;
        if (ean != null) {
            log.info("Icecat: ricerca per EAN {}", ean);
            data = fetchProductData(eanRaw);
        }
        if (data == null || data.imageUrls().isEmpty()) {
            String nome = product.getNome() != null ? product.getNome().trim() : null;
            String marca = product.getMarca() != null ? product.getMarca().trim() : null;
            if (nome != null && nome.length() >= 3) {
                StringBuilder sb = new StringBuilder();
                if (marca != null && !marca.isBlank()) sb.append(marca).append(" ");
                sb.append(nome);
                if (ean == null && eanRaw != null && eanRaw.matches("^[A-Za-z0-9_-]{4,32}$") && !nome.toLowerCase().contains(eanRaw.toLowerCase())) {
                    sb.append(" ").append(eanRaw);
                }
                String searchName = sb.toString().trim();
                log.info("Icecat: nessun risultato per EAN, ricerca per nome '{}'", searchName);
                data = fetchProductDataByProductName(searchName);
            }
        }
        if (data == null || data.imageUrls().isEmpty()) {
            String marca = product.getMarca() != null ? product.getMarca().trim() : null;
            String codiceProduttore = product.getCodiceProduttore() != null ? product.getCodiceProduttore().trim() : null;
            if ((codiceProduttore == null || codiceProduttore.isBlank()) && eanRaw != null && eanRaw.matches("^[A-Za-z0-9_-]{4,32}$")) {
                codiceProduttore = eanRaw.trim();
            }
            if (marca != null && !marca.isBlank() && codiceProduttore != null && !codiceProduttore.isBlank()) {
                log.info("Icecat: nessun risultato per EAN/nome, fallback su marca={} codiceProduttore={}", marca, codiceProduttore);
                data = fetchProductDataByBrandAndProdId(marca, codiceProduttore);
            }
        }
        if (data == null || data.imageUrls().isEmpty()) {
            log.info("Icecat: prodotto {} senza risultati (nome/EAN/marca+codice), uso immagine di default", productId);
            return addDefaultImageIfConfigured(product);
        }

        // Rimuovi vecchie immagini (Icecat URL o locali)
        List<Document> toRemove = new ArrayList<>(product.getDocumenti()).stream()
                .filter(d -> d.getTipo() != null && "immagine".equalsIgnoreCase(d.getTipo()))
                .collect(Collectors.toList());
        for (Document d : toRemove) {
            product.getDocumenti().remove(d);
            documentRepository.delete(d);
        }

        // Scarica immagini e salva path locale (max 3, ordine 0, 1, 2 per poter scegliere la principale)
        int added = 0;
        int limit = Math.min(data.imageUrls().size(), MAX_IMAGES_PER_PRODUCT);
        for (int i = 0; i < limit; i++) {
            String localPath = downloadImageToStorage(data.imageUrls().get(i), productId, i);
            if (localPath != null) {
                Document doc = new Document();
                doc.setTipo("immagine");
                doc.setUrl(localPath);
                doc.setOrdine(i);
                doc.setProduct(product);
                documentRepository.save(doc);
                product.getDocumenti().add(doc);
                added++;
            }
        }

        // Aggiorna descrizione
        if (data.description() != null && !data.description().isBlank()) {
            product.setDescrizione(data.description());
            try {
                Path descFile = Paths.get(storagePath, String.valueOf(productId), "descrizione.txt");
                Files.createDirectories(descFile.getParent());
                Files.writeString(descFile, data.description(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Impossibile salvare descrizione su file: {}", e.getMessage());
            }
        }

        // Salva in cache per evitare re-download dopo reset catalogo o re-import
        copyProductImagesToCache(productId, cacheKey, data, added);

        productRepository.save(product);
        log.info("Icecat: scaricate {} immagini e descrizione per prodotto {} (EAN {}), salvate in cache", added, product.getSku(), ean);
        return added;
    }

    /**
     * Sincronizza per tutti i prodotti con nome (≥3 caratteri) e/o EAN valido.
     * Priorità: nome → EAN → marca+codiceProduttore.
     */
    public int syncImagesForAllProducts() {
        List<Product> products = productRepository.findAllWithAssociations();
        int total = 0;
        for (Product p : products) {
            String nome = p.getNome() != null ? p.getNome().trim() : null;
            String eanRaw = p.getEan() != null ? p.getEan().trim() : p.getSku();
            boolean hasName = nome != null && nome.length() >= 3;
            boolean hasEan = resolveEanForLookup(eanRaw) != null;
            if (hasName || hasEan) {
                total += syncImagesForProduct(p.getId());
            }
        }
        return total;
    }

    /** Per fetchImageUrls (endpoint test) - mantiene compatibilità */
    public List<String> fetchImageUrls(String ean) {
        IcecatData data = fetchProductData(ean);
        return data != null ? data.imageUrls() : List.of();
    }

    /** Debug: recupera XML grezzo da Icecat (max 5000 caratteri) per diagnostica. */
    public String fetchRawXmlForDebug(String ean) {
        String normalized = resolveEanForLookup(ean);
        if (normalized == null) return "EAN non valido: " + ean;
        String apiUrl = ICECAT_API + "?lang=" + lang + "&ean_upc=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8) + "&output=productxml";
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(apiUrl)).timeout(java.time.Duration.ofSeconds(15)).GET();
            if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + auth);
            }
            HttpResponse<byte[]> responseBytes = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = responseBytes.statusCode();
            byte[] bodyBytes = responseBytes.body();
            String contentEncoding = responseBytes.headers().firstValue("Content-Encoding").orElse("");
            byte[] raw = bodyBytes;
            if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip") && bodyBytes != null && bodyBytes.length > 0) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(bodyBytes);
                     GZIPInputStream gzis = new GZIPInputStream(bais);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = gzis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    raw = baos.toByteArray();
                } catch (IOException e) {
                    log.warn("Icecat fetchRawXml: errore decompressione gzip: {}", e.getMessage());
                }
            }
            String body = (raw != null && raw.length > 0)
                    ? new String(raw, StandardCharsets.UTF_8)
                    : "";
            if (body == null || body.isBlank()) return "Risposta vuota (HTTP " + status + ")";
            int maxLen = Math.min(5000, body.length());
            return "HTTP " + status + ", length=" + body.length() + "\n\n" + body.substring(0, maxLen) + (body.length() > maxLen ? "\n\n... (troncato)" : "");
        } catch (Exception e) {
            return "Errore: " + e.getMessage();
        }
    }

    /**
     * Diagnostica: testa la connessione Icecat e restituisce dettagli utili per il debug.
     */
    public IcecatDiagnostic diagnose(String ean) {
        String normalized = resolveEanForLookup(ean);
        if (normalized == null) {
            return new IcecatDiagnostic(ean, null, "EAN non valido: estrai 8-14 cifre da ean/sku (es. EAN-8057284622826). Usato: " + ean);
        }

        String apiUrl = ICECAT_API + "?lang=" + lang + "&ean_upc=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8) + "&output=productxml";

        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Accept", "application/xml, text/xml, */*")
                    .GET();

            boolean hasAuth = username != null && !username.isBlank() && password != null && !password.isBlank();
            if (hasAuth) {
                String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + auth);
            }

            HttpResponse<byte[]> responseBytes = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = responseBytes.statusCode();
            byte[] bodyBytes = responseBytes.body();
            // Icecat invia content-encoding: gzip - decomprimere prima di leggere come XML
            String contentEncoding = responseBytes.headers().firstValue("Content-Encoding").orElse("");
            byte[] raw = bodyBytes;
            if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip") && bodyBytes != null && bodyBytes.length > 0) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(bodyBytes);
                     GZIPInputStream gzis = new GZIPInputStream(bais);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = gzis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    raw = baos.toByteArray();
                } catch (IOException e) {
                    log.warn("Icecat diagnose: errore decompressione gzip: {}", e.getMessage());
                }
            }
            String body = (raw != null && raw.length > 0)
                    ? new String(raw, StandardCharsets.UTF_8)
                    : "";

            if (status == 401) {
                return new IcecatDiagnostic(normalized, status,
                        "401 Unauthorized. Open Icecat richiede registrazione gratuita su https://icecat.biz - aggiungi icecat.username e icecat.password in application.properties");
            }
            if (status != 200) {
                return new IcecatDiagnostic(normalized, status, "HTTP " + status);
            }
            if (body == null || body.isBlank()) {
                return new IcecatDiagnostic(normalized, status, "Risposta vuota");
            }
            if (body.toLowerCase().contains("product not found") || body.contains("NO_SUCH_PRODUCT")) {
                return new IcecatDiagnostic(normalized, status, "Prodotto non trovato nel catalogo Icecat (EAN " + normalized + ")");
            }
            if (body.contains("Code=\"-1\"") || body.contains("ErrorMessage=")) {
                String hint = "Prodotto non disponibile. Se Postman funziona con lo stesso EAN, verifica che application.properties abbia icecat.username e icecat.password IDENTICI a quelli usati in Postman (da https://icecat.biz/myIcecat). Altrimenti: prova EAN da https://icecat.biz/ssr/menu/partners oppure passa a Full Icecat.";
                if (body.contains("File does not exist")) {
                    return new IcecatDiagnostic(normalized, status, hint);
                }
                if (body.contains("Full Icecat") || body.contains("not allowed to have Full Icecat")) {
                    return new IcecatDiagnostic(normalized, status, "Prodotto solo in Full Icecat. L'account Open Icecat (gratuito) non può accedere. Upgrade su https://icecat.biz");
                }
                int idx = body.indexOf("ErrorMessage=\"");
                if (idx >= 0) {
                    int end = body.indexOf("\"", idx + 14);
                    String err = end > idx ? body.substring(idx + 14, end) : "prodotto assente";
                    return new IcecatDiagnostic(normalized, status, err + ". " + hint);
                }
                return new IcecatDiagnostic(normalized, status, hint);
            }

            IcecatData data = parseIcecatDataFromXml(body);
            int imgCount = data != null ? data.imageUrls().size() : 0;
            return new IcecatDiagnostic(normalized, status, "OK: " + imgCount + " immagini trovate");
        } catch (Exception e) {
            return new IcecatDiagnostic(normalized, null, "Errore: " + e.getMessage());
        }
    }

    /** Path assoluto della cartella storage (per mostrarlo all'utente). */
    public String getStoragePathAbsolute() {
        return Paths.get(storagePath).toAbsolutePath().toString();
    }

    /** True se icecat.username e icecat.password sono configurati in application.properties. */
    public boolean isAuthConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    /**
     * Test connessione: effettua la STESSA chiamata che fai in Postman.
     * Se Postman funziona ma questo no, le credenziali in application.properties
     * sono diverse da quelle in Postman. Copia username e password dalla tab Authorization di Postman.
     */
    public java.util.Map<String, Object> testConnection(String ean) {
        String normalized = resolveEanForLookup(ean);
        if (normalized == null) {
            return java.util.Map.of("ok", false, "error", "EAN non valido", "url", "", "authSent", false);
        }
        String url = ICECAT_API + "?lang=" + lang + "&ean_upc=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8) + "&output=productxml";
        boolean authSent = username != null && !username.isBlank() && password != null && !password.isBlank();
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Accept", "application/xml, text/xml, */*")
                    .header("Accept-Language", lang != null ? lang : "IT")
                    .GET();
            if (authSent) {
                String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + auth);
            }
            HttpResponse<byte[]> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] raw = resp.body();
            String enc = resp.headers().firstValue("Content-Encoding").orElse("");
            if (enc.toLowerCase().contains("gzip") && raw != null && raw.length > 0) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(raw);
                     GZIPInputStream gz = new GZIPInputStream(bais);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = gz.read(buf)) > 0) baos.write(buf, 0, n);
                    raw = baos.toByteArray();
                }
            }
            String body = raw != null && raw.length > 0 ? new String(raw, StandardCharsets.UTF_8) : "";
            boolean hasProduct = body != null && !body.contains("Code=\"-1\"") && !body.contains("File does not exist") && body.contains("Product");
            var map = new java.util.HashMap<String, Object>();
            map.put("ok", hasProduct);
            map.put("url", url);
            map.put("authSent", authSent);
            map.put("authUser", authSent ? username : null);
            map.put("httpStatus", resp.statusCode());
            map.put("bodyLength", body != null ? body.length() : 0);
            map.put("hasProduct", hasProduct);
            if (!hasProduct && body != null && body.length() > 0) {
                map.put("bodyPreview", body.substring(0, Math.min(600, body.length())));
            }
            if (!authSent) {
                map.put("hint", "Aggiungi icecat.username e icecat.password in application.properties (le STESSE credenziali della tab Authorization di Postman)");
            } else if (!hasProduct) {
                map.put("hint", "Postman funziona ma il progetto no? Le credenziali in application.properties (user: " + username + ") sono DIVERSE da quelle in Postman. Copia username e password dalla tab Authorization di Postman.");
            }
            return map;
        } catch (Exception e) {
            var map = new java.util.HashMap<String, Object>();
            map.put("ok", false);
            map.put("url", url);
            map.put("authSent", authSent);
            map.put("error", e.getMessage());
            return map;
        }
    }

    public record IcecatData(List<String> imageUrls, String description) {}
    public record IcecatDiagnostic(String ean, Integer httpStatus, String message) {}

    /** Diagnostica estesa: restituisce anche xmlPreview e imageUrls. Usa icecat-raw per xmlPreview. */
    public IcecatDiagnosticVerbose diagnoseVerbose(String ean) {
        IcecatDiagnostic base = diagnose(ean);
        IcecatData data = fetchProductData(ean);
        List<String> urls = data != null ? data.imageUrls() : List.of();
        String raw = fetchRawXmlForDebug(ean);
        String xmlPreview = null;
        if (raw != null && raw.length() > 50) {
            int xmlStart = raw.indexOf("\n\n");
            String xmlPart = xmlStart >= 0 ? raw.substring(xmlStart + 2) : raw;
            xmlPreview = xmlPart.substring(0, Math.min(2000, xmlPart.length()));
        }
        return new IcecatDiagnosticVerbose(base.ean(), base.httpStatus(), base.message(), xmlPreview, urls);
    }

    public record IcecatDiagnosticVerbose(String ean, Integer httpStatus, String message, String xmlPreview, List<String> imageUrls) {}
}
