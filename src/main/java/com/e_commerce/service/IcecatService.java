package com.e_commerce.service;

import com.e_commerce.model.Document;
import com.e_commerce.model.Product;
import com.e_commerce.repository.DocumentRepository;
import com.e_commerce.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Servizio per recuperare immagini prodotto da Icecat (Open Icecat / Full Icecat).
 * Usa l'API XML_s3 con lookup per EAN/GTIN.
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

    public IcecatService(ProductRepository productRepository, DocumentRepository documentRepository) {
        this.productRepository = productRepository;
        this.documentRepository = documentRepository;
    }

    /**
     * Recupera gli URL delle immagini per un prodotto Icecat dato l'EAN.
     *
     * @param ean EAN/GTIN del prodotto (8, 13 o 14 cifre)
     * @return lista di URL immagini (HighPic, Pic500x500, ProductGallery)
     */
    public List<String> fetchImageUrls(String ean) {
        if (ean == null || !ean.matches("\\d{8,14}")) {
            log.debug("EAN non valido per Icecat: {}", ean);
            return List.of();
        }

        String url = ICECAT_API + "?lang=" + lang + "&ean_upc=" + URLEncoder.encode(ean.trim(), StandardCharsets.UTF_8) + "&output=productxml";

        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET();

            if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + auth);
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                log.debug("Icecat API risposta {} per EAN {}", response.statusCode(), ean);
                return List.of();
            }

            String xml = response.body();
            if (xml == null || xml.isBlank() || xml.contains("product not found") || xml.contains("NO_SUCH_PRODUCT")) {
                return List.of();
            }

            return parseImageUrlsFromXml(xml);
        } catch (Exception e) {
            log.warn("Errore durante fetch Icecat per EAN {}: {}", ean, e.getMessage());
            return List.of();
        }
    }

    private List<String> parseImageUrlsFromXml(String xml) {
        Set<String> urls = new HashSet<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document xmlDoc = builder.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Element root = xmlDoc.getDocumentElement();
            if (root == null) return List.of();

            // HighPic dall'elemento Product
            String highPic = getAttribute(root, "HighPic");
            if (highPic != null && !highPic.isBlank()) {
                urls.add(cleanUrl(highPic));
            }

            // Pic500x500
            String pic500 = getAttribute(root, "Pic500x500");
            if (pic500 != null && !pic500.isBlank()) {
                urls.add(cleanUrl(pic500));
            }

            // ThumbPic
            String thumbPic = getAttribute(root, "ThumbPic");
            if (thumbPic != null && !thumbPic.isBlank()) {
                urls.add(cleanUrl(thumbPic));
            }

            // ProductGallery > ProductPicture
            NodeList galleryList = root.getElementsByTagName("ProductGallery");
            for (int i = 0; i < galleryList.getLength(); i++) {
                Node gallery = galleryList.item(i);
                if (gallery.getNodeType() != Node.ELEMENT_NODE) continue;
                NodeList pictures = ((Element) gallery).getElementsByTagName("ProductPicture");
                for (int j = 0; j < pictures.getLength(); j++) {
                    Node pic = pictures.item(j);
                    if (pic.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element picEl = (Element) pic;
                    for (String attr : new String[]{"HighPic", "Pic", "Pic500x500", "LowPic"}) {
                        String val = getAttribute(picEl, attr);
                        if (val != null && !val.isBlank()) {
                            urls.add(cleanUrl(val));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Errore parsing XML Icecat: {}", e.getMessage());
        }
        return new ArrayList<>(urls);
    }

    private String getAttribute(Element el, String name) {
        String val = el.getAttribute(name);
        return (val != null && !val.isBlank()) ? val : null;
    }

    private String cleanUrl(String url) {
        if (url == null) return null;
        url = url.trim();
        if (url.startsWith("<") && url.endsWith(">")) {
            url = url.substring(1, url.length() - 1);
        }
        return url;
    }

    /**
     * Sincronizza le immagini Icecat per un singolo prodotto.
     * Aggiunge Document con tipo "immagine" per ogni URL trovato.
     * Rimuove eventuali immagini Icecat precedenti (url che contiene "icecat").
     */
    public int syncImagesForProduct(Long productId) {
        Product product = productRepository.findByIdWithAssociations(productId).orElse(null);
        if (product == null) return 0;

        String ean = product.getEan() != null ? product.getEan().trim() : product.getSku();
        List<String> urls = fetchImageUrls(ean);
        if (urls.isEmpty()) return 0;

        // Rimuovi vecchie immagini Icecat
        List<Document> toRemove = new ArrayList<>(product.getDocumenti()).stream()
                .filter(d -> d.getTipo() != null && "immagine".equalsIgnoreCase(d.getTipo())
                        && d.getUrl() != null && d.getUrl().toLowerCase().contains("icecat"))
                .collect(Collectors.toList());
        for (Document d : toRemove) {
            product.getDocumenti().remove(d);
            documentRepository.delete(d);
        }

        int added = 0;
        for (String url : urls) {
            if (url == null || url.isBlank()) continue;
            Document doc = new Document();
            doc.setTipo("immagine");
            doc.setUrl(url);
            doc.setProduct(product);
            documentRepository.save(doc);
            product.getDocumenti().add(doc);
            added++;
        }
        productRepository.save(product);
        log.info("Sincronizzate {} immagini Icecat per prodotto {} (EAN {})", added, product.getSku(), ean);
        return added;
    }

    /**
     * Sincronizza le immagini Icecat per tutti i prodotti con EAN valido.
     */
    public int syncImagesForAllProducts() {
        List<Product> products = productRepository.findAllWithAssociations();
        int total = 0;
        for (Product p : products) {
            String ean = p.getEan() != null ? p.getEan().trim() : p.getSku();
            if (ean != null && ean.matches("\\d{8,14}")) {
                total += syncImagesForProduct(p.getId());
            }
        }
        return total;
    }
}
