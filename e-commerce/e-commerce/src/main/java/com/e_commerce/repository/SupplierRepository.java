package com.e_commerce.repository;

import com.e_commerce.model.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByNome(String nome);

    Optional<Supplier> findByCodice(String codice);
}


