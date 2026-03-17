package com.e_commerce.service;

import com.e_commerce.model.Category;
import com.e_commerce.model.PriceSettings;
import com.e_commerce.model.Product;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PriceService {

    public BigDecimal calcolaPrezzoFinale(Product product, PriceSettings settings) {
        BigDecimal base = product.getPrezzoBase();

        // Aumento per prodotto (priorità più alta)
        if (product.getAumentoPercentuale() != null) {
            return applica(base, product.getAumentoPercentuale());
        }

        // Aumento per categoria
        Category categoria = product.getCategoria();
        if (categoria != null && categoria.getAumentoPercentuale() != null) {
            return applica(base, categoria.getAumentoPercentuale());
        }

        // Aumento globale
        if (settings != null && settings.getAumentoGlobalePercentuale() != null) {
            return applica(base, settings.getAumentoGlobalePercentuale());
        }

        return base;
    }

    private BigDecimal applica(BigDecimal base, Double percentuale) {
        BigDecimal p = BigDecimal.valueOf(percentuale)
                .divide(BigDecimal.valueOf(100));
        return base.multiply(BigDecimal.ONE.add(p));
    }
}

