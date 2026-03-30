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
     * Mappa nome categoria catalogo virtuale → ID categoria Magento.
     * Allineato alle categorie su Magento: Default Category (2), Videosorveglianza (35), Multimedia (36), Cavi (38),
     * Computer (39), Accessori (34), Best Seller (41), Elettronica (42), Scuola e laboratori (33), Ufficio (37), Networking (32).
     */
    private static final Map<String, String> CATEGORY_TO_MAGENTO_ID = Map.ofEntries(
            Map.entry("Best sellers", "41"),
            Map.entry("Best Seller", "41"),
            Map.entry("Videosorveglianza", "35"),
            Map.entry("Cavi", "38"),
            Map.entry("Computer", "39"),
            Map.entry("Accessori", "34"),
            Map.entry("Ufficio", "37"),
            Map.entry("Networking", "32"),
            Map.entry("Scuola e laboratori", "33"),
            Map.entry("Scuola e Laboratori", "33"),
            Map.entry("Elettronica", "42"),
            Map.entry("Multimedia", "36")
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

                // Per nuovi e aggiornati: carica/aggiorna l'immagine primaria (Base/Small/Thumbnail/Swatch).
                if (uploadImagesToMagento) {
                    checkCancelled();
                    boolean uploaded = syncPrimaryImageIfPresent(base, p);
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
                boolean uploaded = syncPrimaryImageIfPresent(base, p);
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

    private boolean syncPrimaryImageIfPresent(String base, Product p) throws Exception {
        if (p == null || p.getId() == null) return false;
        String sku = effectiveSkuForMagento(p);
        if (sku == null) return false;

        Path imgPath = resolvePrimaryLocalImagePath(p);
        if (imgPath == null || !Files.isRegularFile(imgPath)) {
            log.info("Magento immagine: nessuna immagine locale trovata per SKU {} (productId={}) in {}", sku, p.getId(), icecatStoragePath);
            return false;
        }

        String filename = imgPath.getFileName().toString();
        log.info("Magento immagine: preparo upload per SKU {} (productId={}) file='{}'", sku, p.getId(), filename);

        byte[] localBytes = Files.readAllBytes(imgPath);
        if (localBytes.length == 0) return false;
        String localHash = sha256Hex(localBytes);

        // Se su Magento esiste già la media con lo stesso name ma con ruoli incompleti,
        // la cancelliamo e la reinseriamo con i types corretti. Se l'immagine è identica (stesso hash) non re-importiamo.
        Set<String> requiredTypes = Set.of("image", "small_image", "thumbnail", "swatch_image");
        boolean hasCompleteTypesForThisFile = false;
        boolean identicalImageAlreadyOnMagento = false;
        List<Long> entryIdsToDelete = new ArrayList<>();

        // Verifica se su Magento esiste già una media entry con lo stesso name
        // Usiamo /rest/all/ per coprire correttamente multi-website/storeview.
        String listUrl = base + "/rest/all/V1/products/" + encode(sku) + "/media";
        String listJson = doGet(listUrl);
        if (listJson != null && !listJson.isBlank()) {
            try {
                JsonNode arr = objectMapper.readTree(listJson);
                if (arr.isArray()) {
                    for (JsonNode entry : arr) {
                        String name = entry.path("name").asText(null);
                        if (name == null || !name.equalsIgnoreCase(filename)) continue;

                        // Se l'immagine su Magento è identica (stesso contenuto), non caricare un duplicato
                        String filePath = entry.path("file").asText(null);
                        if (filePath != null && !filePath.isBlank()) {
                            byte[] magentoBytes = fetchMediaImageBytes(base, filePath);
                            if (magentoBytes != null && magentoBytes.length > 0 && localHash.equals(sha256Hex(magentoBytes))) {
                                identicalImageAlreadyOnMagento = true;
                                log.info("Magento immagine: immagine identica già presente per SKU {} (file='{}'), skip upload", sku, filename);
                                break;
                            }
                        }

                        // types potrebbe mancare o essere non array: in quel caso trattiamo l'entry come incompleta.
                        Set<String> existingTypes = new HashSet<>();
                        JsonNode typesNode = entry.path("types");
                        if (typesNode.isArray()) {
                            for (JsonNode t : typesNode) {
                                existingTypes.add(t.asText());
                            }
                        }

                        boolean hasAllRequired = existingTypes.containsAll(requiredTypes);
                        if (hasAllRequired) {
                            hasCompleteTypesForThisFile = true;
                            break;
                        }

                        long entryId = entry.path("id").asLong(-1);
                        if (entryId > 0) {
                            entryIdsToDelete.add(entryId);
                            // Log minimale: facciamo debug su cosa manca.
                            Set<String> missing = new HashSet<>(requiredTypes);
                            missing.removeAll(existingTypes);
                            log.info("Magento immagine: ruoli incompleti su SKU {} per name='{}'. Mancano: {}", sku, filename, missing);
                        } else {
                            log.warn("Magento immagine: entry senza id trovata su SKU {} (name='{}'). In fallback: non possiamo cancellare, proseguiamo con upload (potenziali duplicati).", sku, filename);
                        }
                    }
                }
            } catch (Exception ignored) {
                // se il parsing fallisce, proviamo comunque ad aggiungere
            }
        }

        if (identicalImageAlreadyOnMagento) {
            return false;
        }
        if (hasCompleteTypesForThisFile) {
            log.info("Magento immagine: ruoli completi già presenti per SKU {} (file='{}'), skip upload", sku, filename);
            return false;
        }

        // Se esiste l'entry ma è incompleta, la rimuoviamo per evitare che Magento continui a usare ruoli parziali.
        for (Long entryId : entryIdsToDelete) {
            if (entryId == null) continue;
            String delUrl = base + "/rest/all/V1/products/" + encode(sku) + "/media/" + entryId;
            int code = doDelete(delUrl);
            if (code >= 400) {
                throw new RuntimeException("Magento risposta DELETE media " + code);
            }
        }

        String mime = guessMimeTypeFromFilename(filename);
        String b64 = Base64.getEncoder().encodeToString(localBytes);

        ObjectNode content = objectMapper.createObjectNode();
        content.put("base64_encoded_data", b64);
        content.put("type", mime);
        content.put("name", filename);

        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("media_type", "image");
        entry.put("label", p.getNome() != null ? p.getNome() : filename);
        // Magento spesso assume position 1..N (più robusto di 0).
        entry.put("position", 1);
        entry.put("disabled", false);
        // Campo "file" richiesto spesso da Magento REST per gestire ruoli/types correttamente.
        entry.put("file", filename);
        ArrayNode types = objectMapper.createArrayNode();
        // Imposta l'immagine come base/small/thumbnail/swatch per tutte le viste
        types.add("image");
        types.add("small_image");
        types.add("thumbnail");
        types.add("swatch_image");
        entry.set("types", types);
        entry.set("content", content);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("entry", entry);

        String addUrl = base + "/rest/all/V1/products/" + encode(sku) + "/media";
        int code = doRequest("POST", addUrl, objectMapper.writeValueAsString(payload));
        if (code >= 400) {
            throw new RuntimeException("Magento risposta " + code);
        }
        log.info("Magento immagine: upload completato per SKU {} (file='{}')", sku, filename);
        return true;
    }

    private Path resolvePrimaryLocalImagePath(Product p) {
        // Icecat salva in: {icecat.storage-path}/{productId}/images/img_0.ext
        Path imagesDir = Paths.get(icecatStoragePath, String.valueOf(p.getId()), "images");
        if (!Files.isDirectory(imagesDir)) return null;
        for (String ext : new String[]{"jpg", "jpeg", "png", "webp", "gif"}) {
            Path candidate = imagesDir.resolve("img_0." + ext);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private String guessMimeTypeFromFilename(String filename) {
        if (filename == null) return "image/jpeg";
        String f = filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".gif")) return "image/gif";
        if (f.endsWith(".webp")) return "image/webp";
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
        stockItem.put("qty", String.valueOf(qty));
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
        return CATEGORY_TO_MAGENTO_ID.getOrDefault(p.getCategoria().getNome().trim(), MAGENTO_DEFAULT_CATEGORY_ID);
    }

    private int parseStock(String disponibilita) {
        if (disponibilita == null || disponibilita.isBlank()) {
            return 0;
        }
        String s = disponibilita.trim();
        if (s.matches("\\d+")) {
            return Integer.parseInt(s);
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
