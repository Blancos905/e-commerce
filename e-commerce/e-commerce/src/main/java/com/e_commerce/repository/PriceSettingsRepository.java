package com.e_commerce.repository;

import com.e_commerce.model.PriceSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceSettingsRepository extends JpaRepository<PriceSettings, Long> {
}

