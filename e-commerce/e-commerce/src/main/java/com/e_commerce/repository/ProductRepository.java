package com.e_commerce.repository;

import com.e_commerce.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    List<Product> findByCategoriaNome(String nomeCategoria);

    List<Product> findByNomeContainingIgnoreCase(String nome);

    List<Product> findBySkuContainingIgnoreCase(String sku);

    List<Product> findByFornitoreId(Long fornitoreId);

    void deleteByFornitoreId(Long fornitoreId);

    boolean existsByCategoriaId(Long categoriaId);

    List<Product> findByCategoriaId(Long categoriaId);
}


