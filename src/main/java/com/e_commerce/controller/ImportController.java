package com.e_commerce.controller;

import com.e_commerce.service.ImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.CancellationException;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/products")
    public ResponseEntity<?> importProducts(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "supplierId", required = false) Long supplierId) throws Exception {
        try {
            importService.importProducts(file, supplierId);
            return ResponseEntity.ok().build();
        } catch (CancellationException e) {
            return ResponseEntity.status(499)
                    .body("Import prodotti annullato dall'utente.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Errore durante l'import prodotti: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @PostMapping("/documents")
    public ResponseEntity<?> importDocuments(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "supplierId", required = false) Long supplierId) throws Exception {
        try {
            importService.importDocuments(file, supplierId);
            return ResponseEntity.ok().build();
        } catch (CancellationException e) {
            return ResponseEntity.status(499)
                    .body("Import documenti annullato dall'utente.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Errore durante l'import documenti: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @PostMapping("/suppliers")
    public ResponseEntity<?> importSuppliers(@RequestParam("file") MultipartFile file) throws Exception {
        try {
            importService.importSuppliers(file);
            return ResponseEntity.ok().build();
        } catch (CancellationException e) {
            return ResponseEntity.status(499)
                    .body("Import fornitori annullato dall'utente.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Errore durante l'import fornitori: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Endpoint chiamato dal frontend quando l'utente preme il bottone "Annulla".
     * Non interrompe brutalmente la richiesta HTTP, ma segnala ai metodi di import
     * di fermarsi alla prima iterazione utile.
     */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancelCurrentImport() {
        importService.requestCancel();
        return ResponseEntity.ok().build();
    }
}

