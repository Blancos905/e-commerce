package com.e_commerce.repository;

import com.e_commerce.model.ProductRevision;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRevisionRepository extends JpaRepository<ProductRevision, Long> {

    List<ProductRevision> findByProductIdOrderByCreatedAtDesc(Long productId, Pageable pageable);

    List<ProductRevision> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
