package com.e_commerce.service;

import com.e_commerce.model.Product;
import com.e_commerce.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Servizio centralizzato per il matching prodotti durante l'import.
 * Strategia a cascata (priorità decrescente):
 * 1. SKU esatto
 * 2. SKU normalizzato (trim, confronto case-insensitive)
 * 3. EAN (se valore numerico 8-14 cifre)
 * 4. SKU contiene / inizia con (fallback per documenti)
 */
@Service
public class ProductMatchingService {

    private static final Pattern EAN_PATTERN = Pattern.compile("\\d{8,14}");

    private final ProductRepository productRepository;

    public ProductMatchingService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Trova un prodotto per SKU e/o EAN usando la strategia a cascata.
     *
     * @param sku SKU dal file (può essere null)
     * @param ean EAN dal file (può essere null)
     * @return Optional con il prodotto trovato e il tipo di match
     */
    public MatchResult findProduct(String sku, String ean) {
        String skuNorm = normalizeIdentifier(sku);
        String eanNorm = normalizeEan(ean);

        // 1. SKU esatto
        if (skuNorm != null) {
            Optional<Product> bySku = productRepository.findBySku(skuNorm);
            if (bySku.isPresent()) {
                return new MatchResult(bySku.get(), MatchType.EXACT_SKU);
            }
        }

        // 2. SKU normalizzato (case-insensitive, confronto con tutti i prodotti)
        if (skuNorm != null) {
            Optional<Product> byNormalized = findBySkuIgnoreCase(skuNorm);
            if (byNormalized.isPresent()) {
                return new MatchResult(byNormalized.get(), MatchType.NORMALIZED_SKU);
            }
        }

        // 3. EAN (identificatore standard, priorità alta)
        if (eanNorm != null && EAN_PATTERN.matcher(eanNorm).matches()) {
            Optional<Product> byEan = productRepository.findByEan(eanNorm);
            if (byEan.isPresent()) {
                return new MatchResult(byEan.get(), MatchType.EAN);
            }
        }

        // 4. EAN come SKU (alcuni fornitori usano EAN come codice articolo)
        if (eanNorm != null && EAN_PATTERN.matcher(eanNorm).matches()) {
            Optional<Product> bySku = productRepository.findBySku(eanNorm);
            if (bySku.isPresent()) {
                return new MatchResult(bySku.get(), MatchType.EAN_AS_SKU);
            }
        }

        // 5. SKU contiene (per documenti: match parziale)
        if (skuNorm != null && skuNorm.length() >= 4) {
            List<Product> candidates = productRepository.findBySkuContainingIgnoreCase(skuNorm);
            if (candidates.size() == 1) {
                return new MatchResult(candidates.get(0), MatchType.SKU_CONTAINS);
            }
        }

        return MatchResult.notFound();
    }

    /**
     * Trova prodotto solo per SKU (import prodotti).
     * Usa match esatto + normalizzato; non usa EAN né contains (per evitare match errati).
     */
    public MatchResult findProductBySku(String sku) {
        return findProductBySkuOnly(sku);
    }

    /**
     * Trova prodotto solo per SKU, senza fallback EAN (per aggiornamento prodotti).
     */
    public MatchResult findProductBySkuOnly(String sku) {
        String skuNorm = normalizeIdentifier(sku);
        if (skuNorm == null) return MatchResult.notFound();

        Optional<Product> bySku = productRepository.findBySku(skuNorm);
        if (bySku.isPresent()) return new MatchResult(bySku.get(), MatchType.EXACT_SKU);

        Optional<Product> byNormalized = findBySkuIgnoreCase(skuNorm);
        if (byNormalized.isPresent()) return new MatchResult(byNormalized.get(), MatchType.NORMALIZED_SKU);

        return MatchResult.notFound();
    }

    /**
     * Trova prodotto per SKU o EAN (import documenti - richiede match).
     */
    public Optional<Product> findProductForDocument(String sku, String ean) {
        return findProduct(sku, ean).getProduct();
    }

    private Optional<Product> findBySkuIgnoreCase(String sku) {
        if (sku == null || sku.isBlank()) return Optional.empty();
        List<Product> list = productRepository.findBySkuContainingIgnoreCase(sku);
        return list.stream()
                .filter(p -> sku.equalsIgnoreCase(normalizeIdentifier(p.getSku())))
                .findFirst();
    }

    /** Normalizza identificatore: trim, rimuove spazi multipli. */
    public static String normalizeIdentifier(String value) {
        if (value == null) return null;
        String s = value.trim().replaceAll("\\s+", " ");
        return s.isBlank() ? null : s;
    }

    /** Normalizza EAN: solo cifre, rimuove spazi/trattini. */
    public static String normalizeEan(String value) {
        if (value == null) return null;
        String s = value.replaceAll("[^0-9]", "").trim();
        return s.isBlank() ? null : s;
    }

    public enum MatchType {
        EXACT_SKU,      // SKU esatto
        NORMALIZED_SKU, // SKU normalizzato (case-insensitive)
        EAN,            // EAN/GTIN
        EAN_AS_SKU,     // EAN usato come SKU
        SKU_CONTAINS,   // SKU contiene (match parziale)
        NOT_FOUND
    }

    public record MatchResult(Product product, MatchType matchType) {
        public static MatchResult notFound() {
            return new MatchResult(null, MatchType.NOT_FOUND);
        }

        public Optional<Product> getProduct() {
            return Optional.ofNullable(product);
        }

        public boolean isFound() {
            return product != null;
        }
    }
}
