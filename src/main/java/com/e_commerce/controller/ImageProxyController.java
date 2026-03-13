package com.e_commerce.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Serve immagini: locali (da storage/product-data) o proxy per esterne (Icecat).
 */
@RestController
@RequestMapping("/api/images")
public class ImageProxyController {

    private final String storagePath;

    public ImageProxyController(@Value("${icecat.storage-path:./storage/product-data}") String storagePath) {
        this.storagePath = storagePath;
    }

    /** Serve immagini scaricate localmente: /api/images/product/{productId}/{filename} */
    @GetMapping("/product/{productId}/{filename}")
    public ResponseEntity<Resource> serveProductImage(
            @PathVariable Long productId,
            @PathVariable String filename) {
        try {
            Path filePath = Paths.get(storagePath, String.valueOf(productId), "images", filename);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new PathResource(filePath);
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) contentType = "image/jpeg";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private static final List<String> ALLOWED_HOSTS = List.of(
            "images.icecat.biz",
            "icecat.biz",
            "www.icecat.biz",
            "data.icecat.biz"
    );

    @GetMapping("/proxy")
    public ResponseEntity<byte[]> proxyImage(@RequestParam String url) {
        try {
            String decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);
            URI uri = URI.create(decodedUrl);
            String host = uri.getHost();
            if (host == null || ALLOWED_HOSTS.stream().noneMatch(h -> host.equalsIgnoreCase(h))) {
                return ResponseEntity.badRequest().build();
            }

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();

            java.net.http.HttpResponse<byte[]> response = client.send(
                    request,
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray()
            );

            if (response.statusCode() != 200) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }

            byte[] body = response.body();
            if (body == null || body.length == 0) {
                return ResponseEntity.noContent().build();
            }

            String contentType = response.headers().firstValue("Content-Type")
                    .orElse(MediaType.IMAGE_JPEG_VALUE);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(body);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
