package com.e_commerce.service;

import com.e_commerce.model.Supplier;
import com.e_commerce.repository.SupplierRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SupplierService {

    private final SupplierRepository supplierRepository;

    public SupplierService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    public List<Supplier> findAll() {
        return supplierRepository.findAll();
    }

    public Optional<Supplier> findById(Long id) {
        return supplierRepository.findById(id);
    }

    public Optional<Supplier> findByNome(String nome) {
        return supplierRepository.findByNome(nome);
    }

    public Optional<Supplier> findByCodice(String codice) {
        return supplierRepository.findByCodice(codice);
    }

    public Supplier save(Supplier supplier) {
        return supplierRepository.save(supplier);
    }

    public void deleteById(Long id) {
        supplierRepository.deleteById(id);
    }
}

