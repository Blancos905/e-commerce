package com.e_commerce.repository;

import com.e_commerce.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @EntityGraph(attributePaths = {"documenti", "categoria", "fornitore"})
    @Query("SELECT p FROM Product p")
    List<Product> findAllWithAssociations();

    @EntityGraph(attributePaths = {"documenti", "categoria", "fornitore"})
    @Query("SELECT p FROM Product p WHERE p.fornitore.id = :fornitoreId")
    List<Product> findByFornitoreId(@Param("fornitoreId") Long fornitoreId);

    @Query("SELECT p FROM Product p " +
           "LEFT JOIN FETCH p.documenti " +
           "LEFT JOIN FETCH p.categoria " +
           "LEFT JOIN FETCH p.fornitore " +
           "WHERE p.id = :id")
    Optional<Product> findByIdWithAssociations(@Param("id") Long id);

    Optional<Product> findBySku(String sku);

    Optional<Product> findByEan(String ean);

    List<Product> findByCategoriaNome(String nomeCategoria);

    List<Product> findByNomeContainingIgnoreCase(String nome);

    List<Product> findBySkuContainingIgnoreCase(String sku);

    void deleteByFornitoreId(Long fornitoreId);

    boolean existsByCategoriaId(Long categoriaId);

    List<Product> findByCategoriaId(Long categoriaId);
}


