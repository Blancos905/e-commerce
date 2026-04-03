package com.e_commerce.service;

import com.e_commerce.dto.ProductUpdateRequest;
import com.e_commerce.model.Category;
import com.e_commerce.model.PriceSettings;
import com.e_commerce.model.Product;
import com.e_commerce.repository.CategoryRepository;
import com.e_commerce.repository.PriceSettingsRepository;
import com.e_commerce.repository.ProductRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Locale;
import java.time.LocalDateTime;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final PriceSettingsRepository priceSettingsRepository;
    private final PriceService priceService;
    private final CategoryRepository categoryRepository;
    private final ProductRevisionService productRevisionService;

    public ProductService(ProductRepository productRepository,
                          PriceSettingsRepository priceSettingsRepository,
                          PriceService priceService,
                          CategoryRepository categoryRepository,
                          @Lazy ProductRevisionService productRevisionService) {
        this.productRepository = productRepository;
        this.priceSettingsRepository = priceSettingsRepository;
        this.priceService = priceService;
        this.categoryRepository = categoryRepository;
        this.productRevisionService = productRevisionService;
    }

    public long count() {
        return productRepository.countByDeletedFalse();
    }

    public List<Product> findAll() {
        return productRepository.findAllWithAssociations();
    }

    public Optional<Product> findById(Long id) {
        return productRepository.findByIdWithAssociations(id);
    }

    public Optional<Product> findByIdWithAssociations(Long id) {
        return productRepository.findByIdWithAssociations(id);
    }

    public List<Product> findByFornitoreId(Long fornitoreId) {
        return productRepository.findByFornitoreId(fornitoreId);
    }

    public List<Product> search(String nome, String sku, String ean, String categoria, String fornitore) {
        List<Product> all = productRepository.findAllWithAssociations();
        final String offertaCategoriaNorm = normalizeCategoryName("In offerta");
        String nomeFilter = (nome != null && !nome.isBlank()) ? nome.trim() : null;
        String skuFilter = (sku != null && !sku.isBlank()) ? sku.trim() : null;
        String eanFilter = (ean != null && !ean.isBlank()) ? ean.trim() : null;
        String fornitoreFilter = (fornitore != null && !fornitore.isBlank()) ? fornitore.trim() : null;
        String categoriaFilter = (categoria != null && !categoria.isBlank()) ? categoria.trim() : null;
        String categoriaNorm = normalizeCategoryName(categoriaFilter);
        final String nuoviProdottiCategoriaNorm = normalizeCategoryName("Nuovi prodotti");
        final boolean isNuoviProdottiVirtual =
                categoriaNorm != null
                        && nuoviProdottiCategoriaNorm != null
                        && nuoviProdottiCategoriaNorm.equalsIgnoreCase(categoriaNorm);
        final boolean isOffertaVirtual =
                categoriaNorm != null
                        && offertaCategoriaNorm != null
                        && offertaCategoriaNorm.equalsIgnoreCase(categoriaNorm);

        // Se il filtro categoria usa un nome che nel DB differisce anche solo per whitespace/unicode,
        // è più robusto confrontare per ID categoria. Ricaviamo l'ID matching dal nome normalizzato.
        final Long categoriaIdNorm = categoriaNorm == null
                ? null
                : categoryRepository.findAll().stream()
                .filter(c -> c != null && c.getNome() != null)
                .filter(c -> categoriaNorm.equalsIgnoreCase(normalizeCategoryName(c.getNome())))
                .map(c -> c.getId())
                .findFirst()
                .orElse(null);

        return all.stream()
                // Filtro "nome": anche marca e codice produttore (es. listino con "APPLE" in marca e "IPHONE" nel nome)
                .filter(p -> nomeFilter == null
                        || matchesSearchText(p.getNome(), nomeFilter)
                        || (p.getMarca() != null && matchesSearchText(p.getMarca(), nomeFilter))
                        || (p.getCodiceProduttore() != null && matchesSearchText(p.getCodiceProduttore(), nomeFilter)))
                // Filtro "SKU": se l'utente incolla il codice va su p.getSku(),
                // ma se incolla una descrizione (nome prodotto) proviamo anche su p.getNome().
                .filter(p -> skuFilter == null ||
                        matchesSearchText(p.getSku(), skuFilter) ||
                        matchesSearchText(p.getNome(), skuFilter))
                .filter(p -> eanFilter == null || matchesSearchText(p.getEan(), eanFilter))
                .filter(p -> categoriaFilter == null ||
                        (isNuoviProdottiVirtual
                                ? Boolean.TRUE.equals(p.getNuovoManuale())
                                : (isOffertaVirtual
                                ? (Boolean.TRUE.equals(p.getInOfferta())
                                // Compat legacy: in passato "In offerta" era una categoria reale sostitutiva
                                || (p.getCategoria() != null
                                && p.getCategoria().getNome() != null
                                && offertaCategoriaNorm != null
                                && offertaCategoriaNorm.equalsIgnoreCase(normalizeCategoryName(p.getCategoria().getNome()))))
                                : (p.getCategoria() != null &&
                                        p.getCategoria().getNome() != null &&
                                        (categoriaIdNorm != null
                                                ? categoriaIdNorm.equals(p.getCategoria().getId())
                                                : categoriaNorm.equalsIgnoreCase(normalizeCategoryName(p.getCategoria().getNome())))))))
                .filter(p -> fornitoreFilter == null || (p.getFornitore() != null &&
                        matchesSearchText(p.getFornitore().getNome(), fornitoreFilter)))
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

    private String normalizeCategoryName(String value) {
        if (value == null) return null;
        // Normalizza anche NBSP (U+00A0) e sequenze di spazi.
        String v = value.replace('\u00A0', ' ').trim();
        v = v.replaceAll("\\s+", " ");
        return v;
    }

    public Product save(Product product) {
        if (product.getDeleted() == null) product.setDeleted(false);
        PriceSettings settings = priceSettingsRepository.findById(1L).orElse(null);
        applyPrezzoFinale(product, settings);
        return productRepository.save(product);
    }

    /** Aggiorna un prodotto da ProductUpdateRequest, tutto in un'unica transazione. */
    public Optional<Product> updateProduct(Long id, ProductUpdateRequest req) {
        return productRepository.findByIdWithAssociations(id)
                .map(existing -> {
                    productRevisionService.saveRevisionBeforeUpdate(existing);
                    existing.setNome(req.getNome());
                    existing.setDescrizione(req.getDescrizione());
                    existing.setDisponibilita(req.getDisponibilita() != null && !req.getDisponibilita().trim().isEmpty()
                            ? req.getDisponibilita().trim() : null);
                    existing.setEan(req.getEan() != null && !req.getEan().trim().isEmpty()
                            ? req.getEan().trim() : null);
                    existing.setMarca(req.getMarca() != null && !req.getMarca().trim().isEmpty()
                            ? req.getMarca().trim() : null);
                    existing.setCodiceProduttore(req.getCodiceProduttore() != null && !req.getCodiceProduttore().trim().isEmpty()
                            ? req.getCodiceProduttore().trim() : null);
                    if (req.getPrezzoBase() != null) {
                        existing.setPrezzoBase(req.getPrezzoBase());
                    }
                    existing.setPrezzoOfferta(req.getPrezzoOfferta());
                    existing.setAumentoPercentuale(req.getAumentoPercentuale());
                    if (req.getCategoriaId() != null) {
                        Category cat = categoryRepository.findById(req.getCategoriaId()).orElse(null);
                        // "In offerta" è categoria virtuale: non deve sostituire la categoria principale.
                        if (cat != null && cat.getNome() != null
                                && "In offerta".equalsIgnoreCase(normalizeCategoryName(cat.getNome()))) {
                            existing.setInOfferta(true);
                        } else {
                            existing.setCategoria(cat);
                        }
                    }
                    if (req.getInOfferta() != null) {
                        existing.setInOfferta(Boolean.TRUE.equals(req.getInOfferta()));
                    }
                    return save(existing);
                });
    }

    public void deleteById(Long id) {
        productRevisionService.deleteRevisionsByProductId(id);
        // Carichiamo le associazioni per garantire che i cascade (es. documenti) vengano applicati correttamente.
        productRepository.findByIdWithAssociations(id).ifPresent(productRepository::delete);
    }

    /** Rimuove solo il flag "In offerta" senza cancellare il prodotto. */
    public Optional<Product> removeFromOfferta(Long id) {
        return productRepository.findByIdWithAssociations(id).map(p -> {
            p.setInOfferta(false);
            p.setPrezzoOfferta(null);
            return save(p);
        });
    }

    /** Rimuove solo il flag "Nuovi prodotti" senza cancellare il prodotto. */
    public Optional<Product> removeFromNuoviProdotti(Long id) {
        return productRepository.findByIdWithAssociations(id).map(p -> {
            p.setNuovoManuale(false);
            return save(p);
        });
    }

    /** Soft delete singolo prodotto (recuperabile). */
    public void softDeleteById(Long id) {
        productRepository.findByIdWithAssociations(id).ifPresent(p -> {
            p.setDeleted(true);
            p.setDeletedAt(LocalDateTime.now());
            productRepository.save(p);
        });
    }

    /**
     * Svuota i contenuti associati a una categoria:
     * categorie reali: soft-delete di tutti i prodotti con quella categoria principale (vanno nel cestino);
     * "In offerta" (virtuale): rimuove il flag offerta e prezzo offerta da tutti i prodotti;
     * "Nuovi prodotti" (virtuale): rimuove il flag nuovo manuale da tutti i prodotti.
     *
     * @return numero di prodotti elaborati
     */
    public int emptyCategoryContents(Long categoryId) {
        if (categoryId == null) {
            throw new IllegalArgumentException("categoryId obbligatorio");
        }
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Categoria non trovata"));
        String nomeCat = normalizeCategoryName(category.getNome());
        String offertaNorm = normalizeCategoryName("In offerta");
        String nuoviNorm = normalizeCategoryName("Nuovi prodotti");

        if (offertaNorm != null && nomeCat != null && offertaNorm.equalsIgnoreCase(nomeCat)) {
            List<Product> list = productRepository.findActiveWithInOffertaTrue();
            int n = 0;
            for (Product p : list) {
                if (p.getId() == null) continue;
                removeFromOfferta(p.getId());
                n++;
            }
            return n;
        }
        if (nuoviNorm != null && nomeCat != null && nuoviNorm.equalsIgnoreCase(nomeCat)) {
            List<Product> list = productRepository.findActiveWithNuovoManualeTrue();
            int n = 0;
            for (Product p : list) {
                if (p.getId() == null) continue;
                removeFromNuoviProdotti(p.getId());
                n++;
            }
            return n;
        }
        List<Product> inCat = productRepository.findByCategoriaId(categoryId);
        for (Product p : inCat) {
            if (p.getId() == null) continue;
            softDeleteById(p.getId());
        }
        return inCat.size();
    }

    public List<Product> findSoftDeleted() {
        return productRepository.findAllDeletedWithAssociations();
    }

    public Optional<Product> restoreSoftDeleted(Long id) {
        return productRepository.findDeletedById(id).map(p -> {
            p.setDeleted(false);
            p.setDeletedAt(null);
            return productRepository.save(p);
        });
    }

    /** Svuota definitivamente il cestino (hard delete dei prodotti soft-deleted). */
    public int emptyTrash() {
        List<Product> deleted = productRepository.findAllDeletedWithAssociations();
        int count = 0;
        for (Product p : deleted) {
            if (p == null || p.getId() == null) continue;
            productRevisionService.deleteRevisionsByProductId(p.getId());
            productRepository.delete(p);
            count++;
        }
        return count;
    }

    public void deleteAll() {
        productRevisionService.deleteAllRevisions();
        productRepository.deleteAll();
    }

    /**
     * Reset catalogo "soft": cancella tutti i prodotti tranne quelli appartenenti alla categoria indicata.
     * Serve per preservare i prodotti creati manualmente (es. "Nuovi prodotti").
     */
    public void deleteAllExceptCategoryName(String protectedCategoryName) {
        if (protectedCategoryName == null || protectedCategoryName.isBlank()) {
            deleteAll();
            return;
        }

        var protectedCategory = categoryRepository.findByNomeIgnoreCase(protectedCategoryName.trim())
                .orElse(null);
        if (protectedCategory == null) {
            deleteAll();
            return;
        }

        boolean preserveNuoviProdottiManuali =
                "Nuovi prodotti".equalsIgnoreCase(protectedCategoryName.trim());

        Long protectedCategoryId = protectedCategory.getId();
        // Carichiamo anche le associazioni (documenti) perché la logica di preservazione
        // dipende dalle immagini manuali eventualmente presenti sul prodotto.
        List<Product> allProducts = productRepository.findAllWithAssociations();
        List<Long> idsToDelete = new ArrayList<>();
        for (Product p : allProducts) {
            if (p == null || p.getId() == null) {
                continue;
            }
            // Preserva sempre i prodotti con immagini caricate manualmente:
            // in questo modo le modifiche dell'utente non vengono cancellate da reset catalogo.
            if (hasManualImages(p)) {
                // Soft delete invece di hard-delete: recuperabile in seguito.
                p.setDeleted(true);
                p.setDeletedAt(LocalDateTime.now());
                productRepository.save(p);
                continue;
            }
            // Inoltre, preserva i prodotti che hanno una descrizione compilata:
            // questo evita di perdere descrizioni inserite o modificate manualmente.
            if (p.getDescrizione() != null && !p.getDescrizione().trim().isEmpty()) {
                // Soft delete invece di hard-delete: recuperabile in seguito.
                p.setDeleted(true);
                p.setDeletedAt(LocalDateTime.now());
                productRepository.save(p);
                continue;
            }

            if (preserveNuoviProdottiManuali) {
                boolean isManualNew = Boolean.TRUE.equals(p.getNuovoManuale());
                if (isManualNew) {
                    continue;
                }
            } else {
                Long catId = p.getCategoria() != null ? p.getCategoria().getId() : null;
                if (protectedCategoryId.equals(catId)) {
                    continue;
                }
            }
            idsToDelete.add(p.getId());
        }
        for (Long id : idsToDelete) {
            deleteById(id);
        }
    }

    private boolean hasManualImages(Product product) {
        if (product == null || product.getDocumenti() == null) return false;
        return product.getDocumenti().stream().anyMatch(d -> {
            if (d == null) return false;
            if (d.getTipo() != null && "immagine_manual".equalsIgnoreCase(d.getTipo())) return true;
            String url = d.getUrl();
            // Backward compat: vecchie immagini manuali usavano ancora tipo="immagine" ma il filename iniziava con "manual_".
            return url != null && url.contains("/manual_");
        });
    }

    /**
     * Ricalcola prezzoFinale per tutti i prodotti della categoria data.
     * Usato quando viene modificato l'aumento percentuale della categoria.
     */
    public void recalculatePrezziByCategoriaId(Long categoriaId) {
        PriceSettings settings = priceSettingsRepository.findById(1L).orElse(null);
        productRepository.findByCategoriaId(categoriaId).forEach(p -> {
            applyPrezzoFinale(p, settings);
            productRepository.save(p);
        });
    }

    private void applyPrezzoFinale(Product product, PriceSettings settings) {
        if (product == null) return;
        if (Boolean.TRUE.equals(product.getInOfferta()) && product.getPrezzoOfferta() != null) {
            product.setPrezzoFinale(product.getPrezzoOfferta());
            return;
        }
        if (product.getPrezzoBase() != null) {
            product.setPrezzoFinale(priceService.calcolaPrezzoFinale(product, settings));
        } else {
            product.setPrezzoFinale(null);
        }
    }
}

