package com.e_commerce.service;

import com.e_commerce.dto.ProductRevisionDTO;
import com.e_commerce.model.Product;
import com.e_commerce.model.ProductRevision;
import com.e_commerce.repository.CategoryRepository;
import com.e_commerce.repository.ProductRevisionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ProductRevisionService {

    private static final int MAX_REVISIONS_PER_PRODUCT = 50;
    private static final int MAX_ALL_REVISIONS = 200;

    private final ProductRevisionRepository revisionRepository;
    private final CategoryRepository categoryRepository;
    private final ProductService productService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProductRevisionService(ProductRevisionRepository revisionRepository,
                                  CategoryRepository categoryRepository,
                                  ProductService productService) {
        this.revisionRepository = revisionRepository;
        this.categoryRepository = categoryRepository;
        this.productService = productService;
    }

    /** Salva lo snapshot del prodotto prima di una modifica manuale. */
    @Transactional
    public void saveRevisionBeforeUpdate(Product product) {
        try {
            ProductRevisionDTO snapshot = toSnapshot(product);
            String json = objectMapper.writeValueAsString(snapshot);

            ProductRevision rev = new ProductRevision();
            rev.setProduct(product);
            rev.setSnapshotJson(json);
            rev.setCreatedAt(LocalDateTime.now());
            revisionRepository.save(rev);

            // Mantieni al massimo MAX_REVISIONS_PER_PRODUCT per prodotto
            List<ProductRevision> all = revisionRepository.findByProductIdOrderByCreatedAtDesc(
                    product.getId(), PageRequest.of(0, MAX_REVISIONS_PER_PRODUCT + 10));
            if (all.size() > MAX_REVISIONS_PER_PRODUCT) {
                for (int i = MAX_REVISIONS_PER_PRODUCT; i < all.size(); i++) {
                    revisionRepository.delete(all.get(i));
                }
            }
        } catch (Exception e) {
            // Non bloccare l'update se il salvataggio della revisione fallisce
            org.slf4j.LoggerFactory.getLogger(ProductRevisionService.class)
                    .warn("Impossibile salvare revisione per prodotto {}: {}", product.getId(), e.getMessage());
        }
    }

    public List<ProductRevisionDTO> getRevisions(Long productId) {
        List<ProductRevision> revisions = revisionRepository.findByProductIdOrderByCreatedAtDesc(
                productId, PageRequest.of(0, MAX_REVISIONS_PER_PRODUCT));
        return revisions.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /** Cronologia modifiche globale (tutti i prodotti, ordine data decrescente). */
    public List<ProductRevisionDTO> getAllRevisions() {
        List<ProductRevision> revisions = revisionRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(0, MAX_ALL_REVISIONS));
        return revisions.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /** Risultato del revert: prodotto ripristinato (se esiste) e se la revisione è stata eliminata. */
    public record RevertResult(Product product, boolean revisionDeleted) {}

    /**
     * Ripristina il prodotto e rimuove la revisione dalla cronologia.
     * Se il prodotto non esiste più, elimina comunque la revisione orfana.
     */
    @Transactional
    public Optional<RevertResult> revertToRevision(Long productId, Long revisionId) {
        ProductRevision rev = revisionRepository.findById(revisionId).orElse(null);
        if (rev == null) return Optional.empty();
        try {
            Long revProductId = rev.getProduct() != null ? rev.getProduct().getId() : null;
            if (revProductId != null && !revProductId.equals(productId)) {
                return Optional.empty();
            }
            Product reverted = null;
            if (productService.findByIdWithAssociations(productId).isPresent()) {
                try {
                    ProductRevisionDTO snapshot = objectMapper.readValue(
                            rev.getSnapshotJson(), ProductRevisionDTO.class);
                    reverted = productService.findByIdWithAssociations(productId)
                            .map(existing -> {
                                applySnapshot(existing, snapshot);
                                return productService.save(existing);
                            })
                            .orElse(null);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // Snapshot corrotto o formato non valido (es. vecchio dato): elimina la revisione senza revert
                }
            }
            revisionRepository.deleteById(revisionId);
            revisionRepository.flush();
            return Optional.of(new RevertResult(reverted, true));
        } catch (Exception e) {
            throw new RuntimeException("Errore nel ripristino: " + e.getMessage());
        }
    }

    private ProductRevisionDTO toSnapshot(Product p) {
        ProductRevisionDTO dto = new ProductRevisionDTO();
        dto.setNome(p.getNome());
        dto.setDescrizione(p.getDescrizione());
        dto.setDisponibilita(p.getDisponibilita());
        dto.setEan(p.getEan());
        dto.setMarca(p.getMarca());
        dto.setCodiceProduttore(p.getCodiceProduttore());
        dto.setPrezzoBase(p.getPrezzoBase());
        dto.setPrezzoFinale(p.getPrezzoFinale());
        dto.setAumentoPercentuale(p.getAumentoPercentuale());
        dto.setCategoriaId(p.getCategoria() != null ? p.getCategoria().getId() : null);
        return dto;
    }

    private void applySnapshot(Product product, ProductRevisionDTO snapshot) {
        product.setNome(snapshot.getNome());
        product.setDescrizione(snapshot.getDescrizione());
        product.setDisponibilita(snapshot.getDisponibilita());
        product.setEan(snapshot.getEan());
        product.setMarca(snapshot.getMarca());
        product.setCodiceProduttore(snapshot.getCodiceProduttore());
        product.setPrezzoBase(snapshot.getPrezzoBase());
        product.setPrezzoFinale(snapshot.getPrezzoFinale());
        product.setAumentoPercentuale(snapshot.getAumentoPercentuale());
        if (snapshot.getCategoriaId() != null) {
            categoryRepository.findById(snapshot.getCategoriaId()).ifPresent(product::setCategoria);
        } else {
            product.setCategoria(null);
        }
    }

    private ProductRevisionDTO toDTO(ProductRevision r) {
        ProductRevisionDTO dto = new ProductRevisionDTO();
        dto.setId(r.getId());
        dto.setProductId(r.getProduct() != null ? r.getProduct().getId() : null);
        dto.setCreatedAt(r.getCreatedAt());
        try {
            ProductRevisionDTO snap = objectMapper.readValue(r.getSnapshotJson(), ProductRevisionDTO.class);
            dto.setNome(snap.getNome());
            dto.setDescrizione(snap.getDescrizione());
            dto.setDisponibilita(snap.getDisponibilita());
            dto.setEan(snap.getEan());
            dto.setMarca(snap.getMarca());
            dto.setCodiceProduttore(snap.getCodiceProduttore());
            dto.setPrezzoBase(snap.getPrezzoBase());
            dto.setPrezzoFinale(snap.getPrezzoFinale());
            dto.setAumentoPercentuale(snap.getAumentoPercentuale());
            dto.setCategoriaId(snap.getCategoriaId());
        } catch (Exception e) {
            // ignore
        }
        return dto;
    }
}
