package com.e_commerce.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class PriceSettings {

    @Id
    private Long id = 1L;

    // aumento globale in percentuale, es. 15.0 per +15%
    private Double aumentoGlobalePercentuale;
}

