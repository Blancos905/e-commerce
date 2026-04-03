package com.e_commerce.service;

import com.e_commerce.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Servizio per sincronizzare il catalogo prodotti con Magento 2 via REST API (OAuth 1.0a).
 * <p>
 * Se su Magento compare "We can't find products matching the selection":
 * <ul>
 *   <li>Assegna i prodotti al website corretto con {@code magento.website-ids} (es. 1 o "1,2").</li>
 *   <li>Dopo la sync esegui su server Magento: {@code bin/magento indexer:reindex} e {@code bin/magento cache:flush}.</li>
 *   <li>Se usi Magento 2.4+ con Elasticsearch, verifica che Elasticsearch sia attivo e che gli indici siano popolati.</li>
 *   <li>Controlla in Admin che i prodotti siano in stato "Abilitato", visibilità "Catalogo, Ricerca" e in stock (o "Mostra prodotti esauriti" = Sì).</li>
 * </ul>
 */
@Service
public class MagentoService {

    private static final Logger log = LoggerFactory.getLogger(MagentoService.class);

    /**
     * Mappa nome categoria catalogo virtuale -> ID categoria Magento.
     * IDs forniti dal catalogo Magento:
     * - Default Category: 2
     * - Offerte: 43
     * - Elettronica: 42
     * - Best Seller: 41
     * - Computer: 39
     * - Ufficio: 37
     * - Videosorveglianza: 35
     * - Scuola e laboratori: 33
     * - Cavi: 38
     * - Multimedia: 36
     * - Accessori: 34
     * - Networking: 32
     */
    private static final Map<String, String> CATEGORY_TO_MAGENTO_ID = Map.ofEntries(
            Map.entry("offerte", "43"),
            Map.entry("in offerta", "43"),
            Map.entry("elettronica", "42"),
            Map.entry("best seller", "41"),
            Map.entry("best sellers", "41"),
            Map.entry("computer", "39"),
            Map.entry("ufficio", "37"),
            Map.entry("videosorveglianza", "35"),
            Map.entry("scuola e laboratori", "33"),
            Map.entry("cavi", "38"),
            Map.entry("multimedia", "36"),
            Map.entry("accessori", "34"),
            Map.entry("networking", "32")
    );

    /** ID categoria Magento usato quando il prodotto non ha categoria o non è nella mappa (Default Category). */
    private static final String MAGENTO_DEFAULT_CATEGORY_ID = "2";

    @Value("${magento.base-url:}")
    private String baseUrl;

    @Value("${magento.consumer-key:}")
    private String consumerKey;

    @Value("${magento.consumer-secret:}")
    private String consumerSecret;

    @Value("${magento.access-token:}")
    private String accessToken;

    @Value("${magento.access-token-secret:}")
    private String accessTokenSecret;

    @Value("${magento.attribute-set-id:4}")
    private int attributeSetId;

    @Value("${icecat.storage-path:./storage/product-data}")
    private String icecatStoragePath;

    @Value("${magento.upload-images:true}")
    private boolean uploadImagesToMagento;

    /**
     * ID website Magento a cui assegnare i prodotti (es. "1" o "1,2").
     * Se i prodotti non compaiono in categoria ("We can't find products matching the selection"),
     * verifica che il sito frontend usi uno di questi website e che dopo la sync esegui reindex e flush cache su Magento.
     */
    @Value("${magento.website-ids:1}")
    private String websiteIdsConfig;

    /** Flag per annullare in modo cooperativo le sync Magento lunghe. */
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /** Chiamato quando l'utente preme "Annulla" durante una sync Magento. */
    public void requestCancel() {
        cancelRequested.set(true);
    }

    private void resetCancel() {
        cancelRequested.set(false);
    }

    private void checkCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Operazione Magento annullata dall'utente.");
        }
    }

    /**
     * SKU usato per Magento: quello del prodotto se valorizzato, altrimenti "VC-{id}"
     * così che tutti i prodotti del catalogo virtuale possano essere esportati.
     */
    private String effectiveSkuForMagento(Product p) {
        if (p == null || p.getId() == null) return null;
        String sku = p.getSku() != null ? p.getSku().trim() : null;
        if (sku != null && !sku.isBlank()) return sku;
        return "VC-" + p.getId();
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && consumerKey != null && !consumerKey.isBlank()
                && consumerSecret != null && !consumerSecret.isBlank()
                && accessToken != null && !accessToken.isBlank()
                && accessTokenSecret != null && !accessTokenSecret.isBlank();
    }

    /**
     * Sincronizza il catalogo su Magento (by SKU):
     * - prodotti che non esistono → creati;
     * - prodotti che esistono già → aggiornati (nome, descrizione, prezzo, categorie, immagine, ecc.).
     * Non si creano duplicati: stessi SKU = aggiornamento.
     */
    public MagentoSyncResult syncCatalog(List<Product> products) {
        MagentoSyncResult result = new MagentoSyncResult();
        if (!isConfigured()) {
            result.setError("Magento non configurato. Verifica application.properties (magento.base-url, consumer-key, secret, access-token, access-token-secret).");
            return result;
        }
        resetCancel();
        String base = baseUrl.replaceAll("/$", "");
        for (Product p : products) {
            checkCancelled();
            try {
                String sku = effectiveSkuForMagento(p);
                if (sku == null) {
                    result.incrementSkipped();
                    continue;
                }
                boolean exists = productExists(base, sku);
                if (exists) {
                    updateProduct(base, p);
                    result.incrementUpdated();
                } else {
                    createProduct(base, p);
                    result.incrementCreated();
                }

                // Alcune installazioni Magento/MSI ignorano stock_item nel payload prodotto:
                // forziamo l'allineamento quantità con endpoint stock dedicato.
                try {
                    String skuForStock = effectiveSkuForMagento(p);
                    if (skuForStock != null) {
                        updateStockBySku(base, skuForStock, parseStock(p.getDisponibilita()));
                    }
                } catch (Exception stockEx) {
                    log.warn("Magento stock: update esplicito fallito per SKU {}: {}", effectiveSkuForMagento(p), stockEx.getMessage());
                }

                // Per nuovi e aggiornati: sincronizza le immagini locali (prima = principale, altre in gallery).
                if (uploadImagesToMagento) {
                    checkCancelled();
                    boolean uploaded = syncProductImagesIfPresent(base, p);
                    if (uploaded) {
                        result.incrementImagesUploaded();
                    }
                }

            } catch (Exception e) {
                log.warn("Errore sync prodotto {}: {}", effectiveSkuForMagento(p), e.getMessage());
                result.addError(effectiveSkuForMagento(p), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Sincronizza solo l'immagine principale dei prodotti già presenti su Magento.
     * Non crea né aggiorna i dati prodotto (prezzi, descrizioni, categorie).
     */
    public MagentoSyncResult syncImagesOnly(List<Product> products) {
        MagentoSyncResult result = new MagentoSyncResult();
        if (!isConfigured()) {
            result.setError("Magento non configurato. Verifica application.properties (magento.base-url, consumer-key, secret, access-token, access-token-secret).");
            return result;
        }
        if (!uploadImagesToMagento) {
            result.setError("Upload immagini verso Magento disabilitato (magento.upload-images=false).");
            return result;
        }
        resetCancel();
        String base = baseUrl.replaceAll("/$", "");
        for (Product p : products) {
            checkCancelled();
            try {
                String sku = effectiveSkuForMagento(p);
                if (sku == null) {
                    result.incrementSkipped();
                    continue;
                }
                // Evita 404: salta i prodotti che non esistono su Magento
                if (!productExists(base, sku)) {
                    result.incrementSkipped();
                    result.addError(sku, "Prodotto non presente su Magento, salto upload immagine");
                    continue;
                }
                boolean uploaded = syncProductImagesIfPresent(base, p);
                if (uploaded) {
                    result.incrementImagesUploaded();
                } else {
                    result.incrementSkipped();
                }
            } catch (Exception e) {
                log.warn("Magento immagini-only: errore per SKU {}: {}", effectiveSkuForMagento(p), e.getMessage());
                result.addError(effectiveSkuForMagento(p), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Nome file da usare su Magento: se il nome locale è già occupato da un'altra immagine,
     * aggiunge un suffisso dal hash così la nuova media non sostituisce la vecchia in galleria.
     */
    private static String decideMagentoUploadFilename(String localFilename, String contentHash, Set<String> existingNamesLower) {
        if (localFilename == null || localFilename.isBlank()) {
            return "image_" + (contentHash.length() >= 8 ? contentHash.substring(0, 8) : contentHash) + ".jpg";
        }
        if (!existingNamesLower.contains(localFilename.toLowerCase(Locale.ROOT))) {
            return localFilename;
        }
        int dot = localFilename.lastIndexOf('.');
        String base = dot > 0 ? localFilename.substring(0, dot) : localFilename;
        String ext = dot > 0 ? localFilename.substring(dot) : "";
        String suffix = contentHash.length() >= 8 ? contentHash.substring(0, 8) : contentHash;
        for (int i = 0; i < 1000; i++) {
            String candidate = i == 0 ? base + "_" + suffix + ext : base + "_" + suffix + "_" + i + ext;
            if (!existingNamesLower.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return base + "_" + System.currentTimeMillis() + ext;
    }

    private boolean syncProductImagesIfPresent(String base, Product p) throws Exception {
        if (p == null || p.getId() == null) return false;
        String sku = effectiveSkuForMagento(p);
        if (sku == null) return false;

        List<Path> imagePaths = resolveLocalImagePaths(p);
        if (imagePaths.isEmpty()) {
            log.info("Magento immagine: nessuna immagine locale trovata per SKU {} (productId={}) in {}", sku, p.getId(), icecatStoragePath);
            return false;
        }

        boolean anyUploaded = false;
        for (int i = 0; i < imagePaths.size(); i++) {
            Path imgPath = imagePaths.get(i);
            boolean setAsPrimary = (i == 0);
            boolean uploaded = uploadSingleImage(base, p, imgPath, setAsPrimary);
            if (uploaded) anyUploaded = true;
        }
        return anyUploaded;
    }

    private List<Path> resolveLocalImagePaths(Product p) {
        List<Path> result = new ArrayList<>();
        Path imagesDir = Paths.get(icecatStoragePath, String.valueOf(p.getId()), "images");
        if (!Files.isDirectory(imagesDir)) return result;

        for (String ext : new String[]{"jpg", "jpeg", "png", "webp", "gif", "svg"}) {
            Path candidate = imagesDir.resolve("img_0." + ext);
            if (Files.isRegularFile(candidate)) {
                result.add(candidate);
                break;
            }
        }
        try (var stream = Files.list(imagesDir)) {
            List<Path> all = stream
                    .filter(Files::isRegularFile)
                    .filter(pth -> {
                        String n = pth.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                                || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".svg");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path pth : all) {
                if (!result.contains(pth)) result.add(pth);
            }
        } catch (Exception e) {
            log.debug("Magento immagine: fallback list immagini fallito per productId={}: {}", p.getId(), e.getMessage());
        }
        return result;
    }

    private boolean uploadSingleImage(String base, Product p, Path imgPath, boolean setAsPrimary) throws Exception {
        String sku = effectiveSkuForMagento(p);
        if (sku == null || imgPath == null || !Files.isRegularFile(imgPath)) return false;
        String filename = imgPath.getFileName().toString();
        log.info("Magento immagine: preparo upload per SKU {} (productId={}) file='{}'", sku, p.getId(), filename);

        byte[] localBytes = Files.readAllBytes(imgPath);
        if (localBytes.length == 0) return false;
        String localHash = sha256Hex(localBytes);

        String listUrl = base + "/rest/all/V1/products/" + encode(sku) + "/media";
        String listJson = doGet(listUrl);

        Set<String> existingNamesLower = new HashSet<>();
        int maxPosition = 0;
        boolean identicalAlreadyPresent = false;

        if (listJson != null && !listJson.isBlank()) {
            try {
                JsonNode arr = objectMapper.readTree(listJson);
                if (arr.isArray()) {
                    for (JsonNode mediaEntry : arr) {
                        String name = mediaEntry.path("name").asText(null);
                        if (name != null && !name.isBlank()) {
                            existingNamesLower.add(name.toLowerCase(Locale.ROOT));
                        }
                        maxPosition = Math.max(maxPosition, mediaEntry.path("position").asInt(0));
                        String filePath = mediaEntry.path("file").asText(null);
                        if (filePath != null && !filePath.isBlank()) {
                            byte[] magentoBytes = fetchMediaImageBytes(base, filePath);
                            if (magentoBytes != null && magentoBytes.length > 0 && localHash.equals(sha256Hex(magentoBytes))) {
                                identicalAlreadyPresent = true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Magento immagine: parsing lista media fallito, proseguo con upload: {}", e.getMessage());
            }
        }

        if (identicalAlreadyPresent) {
            log.info("Magento immagine: contenuto già presente su Magento per SKU {}, skip upload", sku);
            return false;
        }
        if (existingNamesLower.contains(filename.toLowerCase(Locale.ROOT))) {
            log.info("Magento immagine: nome '{}' già presente in gallery per SKU {}, skip upload", filename, sku);
            return false;
        }

        String uploadFilename = decideMagentoUploadFilename(filename, localHash, existingNamesLower);
        String mime = guessMimeTypeFromFilename(uploadFilename);
        String b64 = Base64.getEncoder().encodeToString(localBytes);

        ObjectNode content = objectMapper.createObjectNode();
        content.put("base64_encoded_data", b64);
        content.put("type", mime);
        content.put("name", uploadFilename);

        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("media_type", "image");
        entry.put("label", p.getNome() != null ? p.getNome() : uploadFilename);
        entry.put("position", maxPosition + 1);
        entry.put("disabled", false);
        entry.put("file", uploadFilename);
        ArrayNode types = objectMapper.createArrayNode();
        if (setAsPrimary) {
            types.add("image");
            types.add("small_image");
            types.add("thumbnail");
            types.add("swatch_image");
        }
        entry.set("types", types);
        entry.set("content", content);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("entry", entry);

        String addUrl = base + "/rest/all/V1/products/" + encode(sku) + "/media";
        int code = doRequest("POST", addUrl, objectMapper.writeValueAsString(payload));
        if (code >= 400) {
            throw new RuntimeException("Magento risposta " + code);
        }
        log.info("Magento immagine: upload completato per SKU {} (file='{}')", sku, uploadFilename);
        return true;
    }

    private String guessMimeTypeFromFilename(String filename) {
        if (filename == null) return "image/jpeg";
        String f = filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".gif")) return "image/gif";
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".svg")) return "image/svg+xml";
        if (f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".jpg")) return "image/jpeg";
        return "image/jpeg";
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Scarica i byte dell'immagine da Magento (URL pubblico media/catalog/product).
     * Restituisce null in caso di errore o 404.
     */
    private byte[] fetchMediaImageBytes(String base, String filePath) {
        try {
            String path = filePath.startsWith("/") ? filePath : "/" + filePath;
            String mediaUrl = base.replaceAll("/$", "") + "/pub/media/catalog/product" + path;
            URI uri = URI.create(mediaUrl);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code != 200) {
                // Proviamo senza /pub/ (alcune installazioni usano media/ direttamente)
                mediaUrl = base.replaceAll("/$", "") + "/media/catalog/product" + path;
                uri = URI.create(mediaUrl);
                conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                code = conn.getResponseCode();
            }
            if (code != 200) return null;
            try (InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            log.debug("Impossibile scaricare immagine Magento da {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private static final Set<String> MANAGED_MAGENTO_CATEGORY_IDS =
            new HashSet<>(CATEGORY_TO_MAGENTO_ID.values());

    /**
     * Allinea le categorie Magento al catalogo virtuale: rimuove il prodotto dalle categorie
     * mappate (tranne quella attuale) e lo assegna alla categoria corrispondente alla categoria
     * del prodotto nel gestionale. Utile dopo aver spostato articoli tra categorie.
     */
    public MagentoCategorySyncResult syncCategoriesOnly(List<Product> products) {
        MagentoCategorySyncResult result = new MagentoCategorySyncResult();
        if (!isConfigured()) {
            result.setError("Magento non configurato. Verifica application.properties.");
            return result;
        }
        resetCancel();
        String base = baseUrl.replaceAll("/$", "");
        for (Product p : products) {
            checkCancelled();
            try {
                String sku = effectiveSkuForMagento(p);
                if (sku == null) {
                    result.incrementSkippedNoSku();
                    continue;
                }
                String targetId = getMagentoCategoryId(p);
                String getUrl = base + "/rest/V1/products/" + encode(sku) + "?fields=sku,extension_attributes";
                String json = doGet(getUrl);
                if (json == null) {
                    result.incrementSkippedNotOnMagento();
                    continue;
                }
                JsonNode root = objectMapper.readTree(json);
                JsonNode ext = root.path("extension_attributes");
                JsonNode links = ext.path("category_links");
                if (!links.isArray()) {
                    links = ext.path("categoryLinks");
                }
                Set<String> inManaged = new LinkedHashSet<>();
                if (links.isArray()) {
                    for (JsonNode link : links) {
                        JsonNode idNode = link.get("category_id");
                        if (idNode == null || idNode.isNull()) {
                            idNode = link.get("categoryId");
                        }
                        if (idNode != null && !idNode.isNull()) {
                            String cid = idNode.asText();
                            if (MANAGED_MAGENTO_CATEGORY_IDS.contains(cid)) {
                                inManaged.add(cid);
                            }
                        }
                    }
                }
                boolean changed = false;
                for (String cid : inManaged) {
                    checkCancelled();
                    if (!cid.equals(targetId)) {
                        int delCode = doDelete(base + "/rest/V1/categories/" + cid + "/products/" + encode(sku));
                        if (delCode == 200 || delCode == 204) {
                            changed = true;
                        }
                    }
                }
                if (!inManaged.contains(targetId)) {
                    checkCancelled();
                    assignProductToCategory(base, targetId, sku);
                    changed = true;
                }
                if (changed) {
                    result.incrementUpdated();
                } else {
                    result.incrementUnchanged();
                }
            } catch (Exception e) {
                log.warn("Magento categorie: errore per SKU {}: {}", effectiveSkuForMagento(p), e.getMessage());
                result.addError(effectiveSkuForMagento(p), e.getMessage());
            }
        }
        return result;
    }

    private String doGet(String urlString) throws Exception {
        URI uri = URI.create(urlString);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", buildOAuth1Header("GET", urlString, null));

        int code = conn.getResponseCode();
        if (code == 404) {
            return null;
        }
        if (code >= 400) {
            String err = readErrorStream(conn);
            throw new RuntimeException("HTTP " + code + ": " + err);
        }
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int doDelete(String urlString) throws Exception {
        URI uri = URI.create(urlString);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("DELETE");
        conn.setRequestProperty("Authorization", buildOAuth1Header("DELETE", urlString, null));
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private void assignProductToCategory(String base, String categoryId, String sku) throws Exception {
        String url = base + "/rest/V1/categories/" + categoryId + "/products";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("sku", sku);
        int code = doRequest("POST", url, objectMapper.writeValueAsString(body));
        if (code >= 400) {
            throw new RuntimeException("Magento assegnazione categoria risposta " + code);
        }
    }

    private boolean productExists(String base, String sku) {
        try {
            String url = base + "/rest/V1/products/" + encode(sku);
            int code = doRequest("GET", url, null);
            return code == 200;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                return false;
            }
            throw new RuntimeException("Errore verifica prodotto: " + e.getMessage());
        }
    }

    private void createProduct(String base, Product p) throws Exception {
        String url = base + "/rest/V1/products";
        ObjectNode payload = buildProductPayload(p);
        int code = doRequest("POST", url, objectMapper.writeValueAsString(payload));
        if (code >= 400) {
            throw new RuntimeException("Magento risposta " + code);
        }
    }

    private void updateProduct(String base, Product p) throws Exception {
        String sku = effectiveSkuForMagento(p);
        if (sku == null) throw new IllegalArgumentException("Prodotto senza ID");
        String url = base + "/rest/V1/products/" + encode(sku);
        ObjectNode payload = buildProductPayload(p);
        int code = doRequest("PUT", url, objectMapper.writeValueAsString(payload));
        if (code >= 400) {
            throw new RuntimeException("Magento risposta " + code);
        }
    }

    /**
     * Aggiornamento stock esplicito su endpoint Magento stockItems.
     * Riduce i casi in cui quantity resta a 0 nonostante update prodotto.
     */
    private void updateStockBySku(String base, String sku, int qty) throws Exception {
        String getUrl = base + "/rest/V1/stockItems/" + encode(sku);
        String getJson = doGet(getUrl);
        int itemId = 1;
        if (getJson != null && !getJson.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(getJson);
                int parsed = node.path("item_id").asInt(0);
                if (parsed > 0) itemId = parsed;
            } catch (Exception ignored) {
                // fallback itemId=1
            }
        }

        String putUrl = base + "/rest/V1/products/" + encode(sku) + "/stockItems/" + itemId;
        ObjectNode stockItem = objectMapper.createObjectNode();
        stockItem.put("qty", qty);
        stockItem.put("is_in_stock", qty > 0);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("stockItem", stockItem);
        payload.put("saveOptions", true);

        int code = doRequest("PUT", putUrl, objectMapper.writeValueAsString(payload));
        if (code >= 400) {
            throw new RuntimeException("Magento stockItems risposta " + code);
        }
    }

    private int doRequest(String method, String urlString, String body) throws Exception {
        URI uri = URI.create(urlString);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(body != null);

        String authHeader = buildOAuth1Header(method, urlString, body);
        conn.setRequestProperty("Authorization", authHeader);

        if (body != null) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = conn.getResponseCode();
        if (code >= 400) {
            String err = readErrorStream(conn);
            throw new RuntimeException("HTTP " + code + ": " + err);
        }
        return code;
    }

    private String buildOAuth1Header(String method, String url, String body) throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("oauth_consumer_key", consumerKey);
        params.put("oauth_nonce", String.valueOf(random.nextLong() & Long.MAX_VALUE));
        params.put("oauth_signature_method", "HMAC-SHA256");
        params.put("oauth_timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        params.put("oauth_token", accessToken);
        params.put("oauth_version", "1.0");

        String baseString = method + "&" + encode(url) + "&" + encode(encodeParams(params));
        String signingKey = encode(consumerSecret) + "&" + encode(accessTokenSecret);
        String signature = hmacSha256(signingKey, baseString);
        params.put("oauth_signature", signature);

        StringBuilder sb = new StringBuilder("OAuth ");
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 6) sb.append(", ");
            sb.append(encode(e.getKey())).append("=\"").append(encode(e.getValue())).append("\"");
        }
        return sb.toString();
    }

    private String encodeParams(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(encode(e.getKey())).append("=").append(encode(e.getValue()));
        }
        return sb.toString();
    }

    private String encode(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (Exception e) {
            return s;
        }
    }

    private String hmacSha256(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    private String readErrorStream(HttpURLConnection conn) {
        try {
            var is = conn.getErrorStream();
            if (is == null) return "";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private ObjectNode buildProductPayload(Product p) {
        String sku = effectiveSkuForMagento(p);
        if (sku == null) sku = "VC-0";
        ObjectNode product = objectMapper.createObjectNode();
        product.put("sku", sku);
        product.put("name", p.getNome() != null && !p.getNome().isBlank() ? p.getNome() : sku);
        product.put("type_id", "simple");
        product.put("attribute_set_id", attributeSetId);
        product.put("weight", "1");
        product.put("status", 1);
        product.put("visibility", 4);

        if (p.getPrezzoFinale() != null) {
            product.put("price", p.getPrezzoFinale().doubleValue());
        } else if (p.getPrezzoBase() != null) {
            product.put("price", p.getPrezzoBase().doubleValue());
        } else {
            product.put("price", 0);
        }

        ObjectNode ext = objectMapper.createObjectNode();
        ArrayNode categoryLinks = objectMapper.createArrayNode();
        String magentoCategoryId = getMagentoCategoryId(p);
        if (magentoCategoryId != null) {
            ObjectNode link = objectMapper.createObjectNode();
            link.put("category_id", magentoCategoryId);
            link.put("position", 0);
            categoryLinks.add(link);
        }
        ext.set("category_links", categoryLinks);

        // Assegnazione esplicita ai website: evita "We can't find products matching the selection" su store view
        ArrayNode websiteIds = objectMapper.createArrayNode();
        for (String idStr : (websiteIdsConfig != null ? websiteIdsConfig : "1").split("[,;\\s]+")) {
            String trimmed = idStr.trim();
            if (!trimmed.isEmpty()) {
                try {
                    websiteIds.add(Integer.parseInt(trimmed));
                } catch (NumberFormatException ignored) { }
            }
        }
        if (websiteIds.isEmpty()) {
            websiteIds.add(1);
        }
        ext.set("website_ids", websiteIds);

        int qty = parseStock(p.getDisponibilita());
        ObjectNode stockItem = objectMapper.createObjectNode();
        stockItem.put("qty", qty);
        stockItem.put("is_in_stock", qty > 0);
        ext.set("stock_item", stockItem);

        product.set("extension_attributes", ext);

        ArrayNode customAttrs = objectMapper.createArrayNode();
        // url_key univoco (da SKU effettivo) per evitare "URL key for specified store already exists"
        String urlKey = sku
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (!urlKey.isBlank()) {
            ObjectNode urlKeyAttr = objectMapper.createObjectNode();
            urlKeyAttr.put("attribute_code", "url_key");
            urlKeyAttr.put("value", urlKey);
            customAttrs.add(urlKeyAttr);
        }
        if (p.getDescrizione() != null && !p.getDescrizione().isBlank()) {
            ObjectNode desc = objectMapper.createObjectNode();
            desc.put("attribute_code", "description");
            desc.put("value", p.getDescrizione());
            customAttrs.add(desc);
        }
        // manufacturer in Magento richiede un ID (opzione select), non il nome: non inviarlo
        if (!customAttrs.isEmpty()) {
            product.set("custom_attributes", customAttrs);
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.set("product", product);
        return root;
    }

    private String getMagentoCategoryId(Product p) {
        if (p.getCategoria() == null || p.getCategoria().getNome() == null) {
            return MAGENTO_DEFAULT_CATEGORY_ID;
        }
        return CATEGORY_TO_MAGENTO_ID.getOrDefault(normalizeCategoryName(p.getCategoria().getNome()), MAGENTO_DEFAULT_CATEGORY_ID);
    }

    private String normalizeCategoryName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private int parseStock(String disponibilita) {
        if (disponibilita == null || disponibilita.isBlank()) {
            return 0;
        }
        String s = disponibilita.trim();
        if (s.matches("\\d+")) {
            return Integer.parseInt(s);
        }
        // Supporta formati reali da listino: ">20", "5+", "10 pz", "disp: 7"
        String digits = s.replaceAll("[^0-9]", "");
        if (!digits.isBlank()) {
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                // fallback sotto
            }
        }
        return s.equalsIgnoreCase("si") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("disponibile") ? 99 : 0;
    }

    public static class MagentoSyncResult {
        private int created;
        private int updated;
        private int skipped;
        private int imagesUploaded;
        private String error;
        private final Map<String, String> errorsBySku = new HashMap<>();

        public void incrementCreated() {
            created++;
        }

        public void incrementUpdated() {
            updated++;
        }

        public void incrementSkipped() {
            skipped++;
        }

        public void incrementImagesUploaded() {
            imagesUploaded++;
        }

        public void setError(String error) {
            this.error = error;
        }

        public void addError(String sku, String message) {
            errorsBySku.put(sku, message);
        }

        public int getCreated() {
            return created;
        }

        public int getUpdated() {
            return updated;
        }

        public int getSkipped() {
            return skipped;
        }

        public int getImagesUploaded() {
            return imagesUploaded;
        }

        public String getError() {
            return error;
        }

        public Map<String, String> getErrorsBySku() {
            return errorsBySku;
        }
    }

    /** Esito sincronizzazione solo categorie Magento ↔ catalogo virtuale. */
    public static class MagentoCategorySyncResult {
        private int updated;
        private int unchanged;
        private int skippedNoSku;
        private int skippedNotOnMagento;
        private String error;
        private final Map<String, String> errorsBySku = new HashMap<>();

        public void incrementUpdated() {
            updated++;
        }

        public void incrementUnchanged() {
            unchanged++;
        }

        public void incrementSkippedNoSku() {
            skippedNoSku++;
        }

        public void incrementSkippedNotOnMagento() {
            skippedNotOnMagento++;
        }

        public void setError(String error) {
            this.error = error;
        }

        public void addError(String sku, String message) {
            errorsBySku.put(sku, message);
        }

        public int getUpdated() {
            return updated;
        }

        public int getUnchanged() {
            return unchanged;
        }

        public int getSkippedNoSku() {
            return skippedNoSku;
        }

        public int getSkippedNotOnMagento() {
            return skippedNotOnMagento;
        }

        public String getError() {
            return error;
        }

        public Map<String, String> getErrorsBySku() {
            return errorsBySku;
        }
    }
}
