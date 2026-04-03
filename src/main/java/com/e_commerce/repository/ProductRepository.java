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
    @Query("SELECT p FROM Product p WHERE (p.deleted = false OR p.deleted IS NULL)")
    List<Product> findAllWithAssociations();

    @EntityGraph(attributePaths = {"documenti", "categoria", "fornitore"})
    @Query("SELECT p FROM Product p WHERE p.deleted = true")
    List<Product> findAllDeletedWithAssociations();

    @EntityGraph(attributePaths = {"documenti", "categoria", "fornitore"})
    @Query("SELECT p FROM Product p WHERE p.fornitore.id = :fornitoreId AND (p.deleted = false OR p.deleted IS NULL)")
    List<Product> findByFornitoreId(@Param("fornitoreId") Long fornitoreId);

    @Query("SELECT p FROM Product p " +
           "LEFT JOIN FETCH p.documenti " +
           "LEFT JOIN FETCH p.categoria " +
           "LEFT JOIN FETCH p.fornitore " +
           "WHERE p.id = :id AND (p.deleted = false OR p.deleted IS NULL)")
    Optional<Product> findByIdWithAssociations(@Param("id") Long id);

    @Query("SELECT p FROM Product p WHERE p.sku = :sku AND (p.deleted = false OR p.deleted IS NULL)")
    Optional<Product> findBySku(@Param("sku") String sku);

    @Query("SELECT p FROM Product p WHERE p.sku = :sku")
    Optional<Product> findBySkuIncludingDeleted(@Param("sku") String sku);

    @Query("SELECT p FROM Product p WHERE p.ean = :ean AND (p.deleted = false OR p.deleted IS NULL)")
    Optional<Product> findByEan(@Param("ean") String ean);

    @Query("SELECT p FROM Product p WHERE p.ean = :ean")
    Optional<Product> findByEanIncludingDeleted(@Param("ean") String ean);

    @Query("SELECT p FROM Product p WHERE p.categoria.nome = :nomeCategoria AND (p.deleted = false OR p.deleted IS NULL)")
    List<Product> findByCategoriaNome(@Param("nomeCategoria") String nomeCategoria);

    @Query("SELECT p FROM Product p WHERE lower(p.nome) LIKE lower(concat('%', :nome, '%')) AND (p.deleted = false OR p.deleted IS NULL)")
    List<Product> findByNomeContainingIgnoreCase(@Param("nome") String nome);

    @Query("SELECT p FROM Product p WHERE lower(p.sku) LIKE lower(concat('%', :sku, '%')) AND (p.deleted = false OR p.deleted IS NULL)")
    List<Product> findBySkuContainingIgnoreCase(@Param("sku") String sku);

    void deleteByFornitoreId(Long fornitoreId);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Product p WHERE p.categoria.id = :categoriaId AND (p.deleted = false OR p.deleted IS NULL)")
    boolean existsByCategoriaId(@Param("categoriaId") Long categoriaId);

    @Query("SELECT p FROM Product p WHERE p.categoria.id = :categoriaId AND (p.deleted = false OR p.deleted IS NULL)")
    List<Product> findByCategoriaId(@Param("categoriaId") Long categoriaId);

    @Query("SELECT p FROM Product p WHERE p.inOfferta = true AND (p.deleted = false OR p.deleted IS NULL)")
    List<Product> findActiveWithInOffertaTrue();

    @Query("SELECT p FROM Product p WHERE p.nuovoManuale = true AND (p.deleted = false OR p.deleted IS NULL)")
    List<Product> findActiveWithNuovoManualeTrue();

    @Query("SELECT COUNT(p) FROM Product p WHERE p.categoria.id = :categoriaId AND (p.deleted = false OR p.deleted IS NULL)")
    long countActiveByCategoriaId(@Param("categoriaId") Long categoriaId);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.inOfferta = true AND (p.deleted = false OR p.deleted IS NULL)")
    long countActiveInOffertaTrue();

    @Query("SELECT COUNT(p) FROM Product p WHERE p.nuovoManuale = true AND (p.deleted = false OR p.deleted IS NULL)")
    long countActiveNuovoManualeTrue();

    @Query("SELECT p FROM Product p WHERE p.id = :id AND p.deleted = true")
    Optional<Product> findDeletedById(@Param("id") Long id);

    long countByDeletedFalse();
}


