package com.e_commerce.repository;

import com.e_commerce.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByNome(String nome);

    /** Per match dal nome file (es. "best seller.csv" → categoria "Best Seller"). */
    Optional<Category> findByNomeIgnoreCase(String nome);
}

