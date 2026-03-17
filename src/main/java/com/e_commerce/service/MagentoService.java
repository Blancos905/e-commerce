package com.e_commerce.service;

import com.e_commerce.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/**
 * Servizio per sincronizzare il catalogo prodotti con Magento 2 via REST API (OAuth 1.0a).
 */
@Service
public class MagentoService {

    private static final Logger log = LoggerFactory.getLogger(MagentoService.class);

    private static final Map<String, String> CATEGORY_TO_MAGENTO_ID = Map.ofEntries(
            Map.entry("Best sellers", "12"),
            Map.entry("Videosorveglianza", "13"),
            Map.entry("Cavi", "14"),
            Map.entry("Computer", "15"),
            Map.entry("Accessori", "16"),
            Map.entry("Ufficio", "17"),
            Map.entry("Networking", "18"),
            Map.entry("Scuola e Laboratori", "19"),
            Map.entry("Elettronica", "20"),
            Map.entry("Multimedia", "21")
    );

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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && consumerKey != null && !consumerKey.isBlank()
                && consumerSecret != null && !consumerSecret.isBlank()
                && accessToken != null && !accessToken.isBlank()
                && accessTokenSecret != null && !accessTokenSecret.isBlank();
    }

    /**
     * Sincronizza tutti i prodotti del catalogo su Magento.
     * Crea prodotti nuovi o aggiorna quelli esistenti (by SKU).
     */
    public MagentoSyncResult syncCatalog(List<Product> products) {
        MagentoSyncResult result = new MagentoSyncResult();
        if (!isConfigured()) {
            result.setError("Magento non configurato. Verifica application.properties (magento.base-url, consumer-key, secret, access-token, access-token-secret).");
            return result;
        }
        String base = baseUrl.replaceAll("/$", "");
        for (Product p : products) {
            try {
                String sku = p.getSku() != null ? p.getSku().trim() : null;
                if (sku == null || sku.isBlank()) {
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
            } catch (Exception e) {
                log.warn("Errore sync prodotto {}: {}", p.getSku(), e.getMessage());
                result.addError(p.getSku(), e.getMessage());
            }
        }
        return result;
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
        String sku = p.getSku().trim();
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
        ObjectNode product = objectMapper.createObjectNode();
        product.put("sku", p.getSku());
        product.put("name", p.getNome() != null ? p.getNome() : p.getSku());
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

        int qty = parseStock(p.getDisponibilita());
        ObjectNode stockItem = objectMapper.createObjectNode();
        stockItem.put("qty", String.valueOf(qty));
        stockItem.put("is_in_stock", qty > 0);
        ext.set("stock_item", stockItem);

        product.set("extension_attributes", ext);

        ArrayNode customAttrs = objectMapper.createArrayNode();
        // url_key univoco (da SKU) per evitare "URL key for specified store already exists"
        String urlKey = (p.getSku() != null ? p.getSku() : "p" + p.getId())
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
            return "16"; // Accessori default
        }
        return CATEGORY_TO_MAGENTO_ID.getOrDefault(p.getCategoria().getNome().trim(), "16");
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

        public String getError() {
            return error;
        }

        public Map<String, String> getErrorsBySku() {
            return errorsBySku;
        }
    }
}
