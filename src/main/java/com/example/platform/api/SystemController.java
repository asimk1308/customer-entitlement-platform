package com.example.platform.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Spring generates no spec here: openapi.yaml is the contract, not a by-product of the code. */
@RestController
class SystemController {
    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    @GetMapping("/healthz")
    ResponseEntity<Map<String, String>> health() {
        return Views.json(200).body(Map.of("status", "ok"));
    }

    @GetMapping("/openapi.yaml")
    ResponseEntity<String> spec() throws IOException {
        return ResponseEntity.ok().contentType(YAML)
                .body(new ClassPathResource("openapi.yaml").getContentAsString(StandardCharsets.UTF_8));
    }
}
