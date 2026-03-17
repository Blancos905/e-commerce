package com.e_commerce.service;

import com.e_commerce.model.PriceSettings;
import com.e_commerce.model.Product;
import com.e_commerce.repository.PriceSettingsRepository;
import com.e_commerce.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Locale;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final PriceSettingsRepository priceSettingsRepository;
    private final PriceService priceService;

    public ProductService(ProductRepository productRepository,
                          PriceSettingsRepository priceSettingsRepository,
                          PriceService priceService) {
        this.productRepository = productRepository;
        this.priceSettingsRepository = priceSettingsRepository;
        this.priceService = priceService;
    }

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    public Optional<Product> findById(Long id) {
        return productRepository.findById(id);
    }

    public List<Product> findByFornitoreId(Long fornitoreId) {
        return productRepository.findByFornitoreId(fornitoreId);
    }

    public List<Product> search(String nome, String sku, String categoria) {
        List<Product> all = productRepository.findAll();
        String nomeFilter = nome != null ? nome.trim() : null;
        String skuFilter = sku != null ? sku.trim() : null;
        return all.stream()
                .filter(p -> nomeFilter == null || matchesSearchText(p.getNome(), nomeFilter))
                .filter(p -> skuFilter == null || matchesSearchText(p.getSku(), skuFilter))
                .filter(p -> categoria == null || (p.getCategoria() != null &&
                        categoria.equalsIgnoreCase(p.getCategoria().getNome())))
                .collect(Collectors.toList());
    }

    /**
     * Confronto "furbo" che permette di trovare il prodotto
     * anche se l'utente scrive il nome "staccato" o con spazi/punteggiatura diversi.
     * Esempi:
     *  - "hp notebook" trova "HP-Notebook 15"
     *  - "multi funz" trova "Multifunzione"
     */
    private boolean matchesSearchText(String text, String query) {
        if (text == null) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }

        String normalizedText = normalizeSearchText(text);
        String normalizedQuery = normalizeSearchText(query);

        if (normalizedQuery.isBlank()) {
            return true;
        }

        String[] tokens = normalizedQuery.split("\\s+");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (!normalizedText.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private String normalizeSearchText(String s) {
        if (s == null) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ITALIAN);
        // Trasforma tutto ciò che non è lettera/numero in spazio,
        // così "hp-notebook_15" diventa "hp notebook 15"
        lower = lower.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ");
        return lower.trim();
    }

    public Product save(Product product) {
        PriceSettings settings = priceSettingsRepository.findById(1L).orElse(null);
        if (product.getPrezzoBase() != null) {
            product.setPrezzoFinale(priceService.calcolaPrezzoFinale(product, settings));
        }
        return productRepository.save(product);
    }

    public void deleteById(Long id) {
        productRepository.deleteById(id);
    }

    public void deleteAll() {
        productRepository.deleteAll();
    }
}

