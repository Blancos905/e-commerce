package com.e_commerce.controller;

import com.e_commerce.model.PriceSettings;
import com.e_commerce.repository.PriceSettingsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/prices")
public class PriceSettingsController {

    private final PriceSettingsRepository priceSettingsRepository;

    public PriceSettingsController(PriceSettingsRepository priceSettingsRepository) {
        this.priceSettingsRepository = priceSettingsRepository;
    }

    @GetMapping("/settings")
    public PriceSettings getSettings() {
        return priceSettingsRepository.findById(1L).orElseGet(() -> {
            PriceSettings s = new PriceSettings();
            return priceSettingsRepository.save(s);
        });
    }

    @PutMapping("/settings/global")
    public ResponseEntity<PriceSettings> updateGlobalIncrease(@RequestParam("percent") Double percent) {
        PriceSettings settings = priceSettingsRepository.findById(1L).orElseGet(PriceSettings::new);
        settings.setAumentoGlobalePercentuale(percent);
        settings.setId(1L);
        return ResponseEntity.ok(priceSettingsRepository.save(settings));
    }
}

