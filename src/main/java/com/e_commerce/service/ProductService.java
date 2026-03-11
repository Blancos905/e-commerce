package com.e_commerce.service;

import com.e_commerce.dto.ProductUpdateRequest;
import com.e_commerce.model.Category;
import com.e_commerce.model.PriceSettings;
import com.e_commerce.model.Product;
import com.e_commerce.repository.CategoryRepository;
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
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository,
                          PriceSettingsRepository priceSettingsRepository,
                          PriceService priceService,
                          CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.priceSettingsRepository = priceSettingsRepository;
        this.priceService = priceService;
        this.categoryRepository = categoryRepository;
    }

    public List<Product> findAll() {
        return productRepository.findAllWithAssociations();
    }

    public Optional<Product> findById(Long id) {
        return productRepository.findById(id);
    }

    public Optional<Product> findByIdWithAssociations(Long id) {
        return productRepository.findByIdWithAssociations(id);
    }

    public List<Product> findByFornitoreId(Long fornitoreId) {
        return productRepository.findByFornitoreId(fornitoreId);
    }

    public List<Product> search(String nome, String sku, String categoria) {
        List<Product> all = productRepository.findAllWithAssociations();
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
        } else {
            product.setPrezzoFinale(null);
        }
        return productRepository.save(product);
    }

    /** Aggiorna un prodotto da ProductUpdateRequest, tutto in un'unica transazione. */
    public Optional<Product> updateProduct(Long id, ProductUpdateRequest req) {
        return productRepository.findByIdWithAssociations(id)
                .map(existing -> {
                    existing.setNome(req.getNome());
                    existing.setDescrizione(req.getDescrizione());
                    existing.setPrezzoBase(req.getPrezzoBase());
                    existing.setAumentoPercentuale(req.getAumentoPercentuale());
                    if (req.getCategoriaId() != null) {
                        Category cat = categoryRepository.findById(req.getCategoriaId()).orElse(null);
                        existing.setCategoria(cat);
                    } else {
                        existing.setCategoria(null);
                    }
                    return save(existing);
                });
    }

    public void deleteById(Long id) {
        productRepository.deleteById(id);
    }

    public void deleteAll() {
        productRepository.deleteAll();
    }

    /**
     * Ricalcola prezzoFinale per tutti i prodotti della categoria data.
     * Usato quando viene modificato l'aumento percentuale della categoria.
     */
    public void recalculatePrezziByCategoriaId(Long categoriaId) {
        PriceSettings settings = priceSettingsRepository.findById(1L).orElse(null);
        productRepository.findByCategoriaId(categoriaId).forEach(p -> {
            if (p.getPrezzoBase() != null) {
                p.setPrezzoFinale(priceService.calcolaPrezzoFinale(p, settings));
                productRepository.save(p);
            } else {
                p.setPrezzoFinale(null);
                productRepository.save(p);
            }
        });
    }
}

