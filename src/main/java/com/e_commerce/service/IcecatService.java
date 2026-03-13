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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    public IcecatService(ProductRepository productRepository, DocumentRepository documentRepository) {
        this.productRepository = productRepository;
        this.documentRepository = documentRepository;
    }

    @PostConstruct
    public void initStorage() {
        try {
            Path base = Paths.get(storagePath).toAbsolutePath();
            Files.createDirectories(base);
            log.info("Icecat storage: {}", base);
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
     * Recupera XML prodotto da Icecat e restituisce immagini + descrizione.
     */
    public IcecatData fetchProductData(String ean) {
        String normalized = resolveEanForLookup(ean);
        if (normalized == null) {
            log.info("Icecat: EAN non valido (estrai 8-14 cifre da ean/sku): {}", ean);
            return null;
        }

        String url = ICECAT_API + "?lang=" + lang + "&ean_upc=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8) + "&output=productxml";

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
            if (status != 200) {
                log.info("Icecat: API risposta {} per EAN {}", status, normalized);
                return null;
            }
            if (xml == null || xml.isBlank()) return null;
            if (xml.toLowerCase().contains("product not found") || xml.contains("NO_SUCH_PRODUCT") || xml.contains("no results")) {
                return null;
            }
            if (xml.contains("Code=\"-1\"") || xml.contains("ErrorMessage=")) return null;

            return parseIcecatDataFromXml(xml);
        } catch (Exception e) {
            log.warn("Icecat: errore per EAN {}: {}", normalized, e.getMessage());
            return null;
        }
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
     * Sincronizza immagini e descrizione Icecat per un prodotto: scarica in storage e aggiorna DB.
     */
    public int syncImagesForProduct(Long productId) {
        Product product = productRepository.findByIdWithAssociations(productId).orElse(null);
        if (product == null) return 0;

        String eanRaw = product.getEan() != null ? product.getEan().trim() : product.getSku();
        String ean = resolveEanForLookup(eanRaw);
        if (ean == null) {
            log.info("Icecat: prodotto {} senza EAN valido (ean={}, sku={})", productId, product.getEan(), product.getSku());
            return 0;
        }
        IcecatData data = fetchProductData(eanRaw);
        if (data == null || data.imageUrls().isEmpty()) return 0;

        // Rimuovi vecchie immagini (Icecat URL o locali)
        List<Document> toRemove = new ArrayList<>(product.getDocumenti()).stream()
                .filter(d -> d.getTipo() != null && "immagine".equalsIgnoreCase(d.getTipo()))
                .collect(Collectors.toList());
        for (Document d : toRemove) {
            product.getDocumenti().remove(d);
            documentRepository.delete(d);
        }

        // Scarica immagini e salva path locale
        int added = 0;
        for (int i = 0; i < data.imageUrls().size(); i++) {
            String localPath = downloadImageToStorage(data.imageUrls().get(i), productId, i);
            if (localPath != null) {
                Document doc = new Document();
                doc.setTipo("immagine");
                doc.setUrl(localPath);
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

        productRepository.save(product);
        log.info("Icecat: scaricate {} immagini e descrizione per prodotto {} (EAN {})", added, product.getSku(), ean);
        return added;
    }

    /**
     * Sincronizza per tutti i prodotti con EAN valido.
     */
    public int syncImagesForAllProducts() {
        List<Product> products = productRepository.findAllWithAssociations();
        int total = 0;
        for (Product p : products) {
            String eanRaw = p.getEan() != null ? p.getEan().trim() : p.getSku();
            if (resolveEanForLookup(eanRaw) != null) {
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
