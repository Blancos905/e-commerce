package com.e_commerce.controller;

import com.e_commerce.model.Category;
import com.e_commerce.service.CategoryService;
import com.e_commerce.service.ProductService;
import com.e_commerce.repository.ProductRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final ProductRepository productRepository;
    private final ProductService productService;

    public CategoryController(CategoryService categoryService,
                              ProductRepository productRepository,
                              ProductService productService) {
        this.categoryService = categoryService;
        this.productRepository = productRepository;
        this.productService = productService;
    }

    @GetMapping
    public List<Category> list() {
        return categoryService.findAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Category category) {
        String nome = category.getNome() != null ? category.getNome().trim() : null;
        if (nome == null || nome.isBlank()) {
            return ResponseEntity.badRequest().body("Il campo 'nome' è obbligatorio.");
        }

        // Verifica duplicato
        if (categoryService.findByNome(nome).isPresent()) {
            return ResponseEntity.status(409).body("Categoria già presente con nome: " + nome);
        }

        category.setNome(nome);
        Category saved = categoryService.save(category);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/seed")
    public ResponseEntity<List<Category>> seedDefaultCategories() {
        List<String> defaults = List.of(
                "Computer",
                "Networking",
                "Elettronica",
                "Multimedia",
                "Cavi",
                "Ufficio",
                "Accessori",
                "Scuola e Laboratori",
                "Best sellers",
                "Videosorveglianza"
        );

        List<Category> result = new ArrayList<>();
        for (String nome : defaults) {
            Category category = findOrCreateCategory(nome);
            result.add(category);
        }

        // Migra prodotti da "Senza categoria" a "Accessori" (ogni prodotto deve avere una categoria)
        Category accessori = categoryService.findByNome("Accessori").orElse(null);
        if (accessori != null) {
            categoryService.findByNome("Senza categoria").ifPresent(senzaCat -> {
                productRepository.findByCategoriaId(senzaCat.getId()).forEach(p -> {
                    p.setCategoria(accessori);
                    productRepository.save(p);
                });
            });
        }

        return ResponseEntity.ok(result);
    }

    /** Trova la categoria per nome o la crea; gestisce race condition (categoria già inserita da altra richiesta). */
    private Category findOrCreateCategory(String nome) {
        return categoryService.findByNome(nome)
                .orElseGet(() -> {
                    try {
                        Category c = new Category();
                        c.setNome(nome);
                        return categoryService.save(c);
                    } catch (DataIntegrityViolationException e) {
                        return categoryService.findByNome(nome)
                                .orElseThrow(() -> new IllegalStateException("Categoria '" + nome + "' già esistente ma non trovata dopo conflitto", e));
                    }
                });
    }

    @PutMapping("/{id}")
    public ResponseEntity<Category> update(@PathVariable Long id, @RequestBody Category updated) {
        return categoryService.findById(id)
                .map(existing -> {
                    updated.setId(existing.getId());
                    return ResponseEntity.ok(categoryService.save(updated));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/increase")
    public ResponseEntity<Category> updateCategoryIncrease(@PathVariable Long id,
                                                           @RequestParam(value = "percent", required = false) Double percent) {
        return categoryService.findById(id)
                .map(category -> {
                    category.setAumentoPercentuale(percent);
                    Category saved = categoryService.save(category);
                    productService.recalculatePrezziByCategoriaId(id);
                    return ResponseEntity.ok(saved);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return categoryService.findById(id)
                .map(category -> {
                    // 1) sgancia le sottocategorie (parent -> null)
                    categoryService.findAll().stream()
                            .filter(c -> c.getParent() != null && id.equals(c.getParent().getId()))
                            .forEach(c -> {
                                c.setParent(null);
                                categoryService.save(c);
                            });

                    // 2) elimina tutti i prodotti che appartengono a questa categoria
                    productRepository.findByCategoriaId(id)
                            .forEach(productRepository::delete);

                    // 3) elimina la categoria
                    categoryService.deleteById(id);
                    return ResponseEntity.noContent().build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

