package com.coach.financier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists the AI-generated financial synthesis (synthese_financier.json) so that
 * subsequent chat calls can send this compact summary to the model instead of the
 * full raw banking JSON (thousands of transactions).
 */
@Component
public class FinancialSynthesisStore {
    private static final Logger log = LoggerFactory.getLogger(FinancialSynthesisStore.class);

    private final ObjectMapper objectMapper;
    private final Path filePath;

    public FinancialSynthesisStore(ObjectMapper objectMapper,
                                   @Value("${app.ai.synthesis-file:./data/synthese_financier.json}") String synthesisFile) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(synthesisFile).toAbsolutePath().normalize();
    }

    public Optional<JsonNode> load() {
        JsonNode node = readFile(filePath);
        if (node != null) {
            log.info("Synthèse financière chargée depuis {}", filePath);
            return Optional.of(node);
        }
        // Repli : une synthèse placée manuellement dans resources/data est aussi acceptée.
        try (InputStream in = new ClassPathResource("data/synthese_financier.json").getInputStream()) {
            JsonNode classpath = objectMapper.readTree(in);
            if (classpath != null && classpath.isObject()) {
                log.info("Synthèse financière chargée depuis le classpath data/synthese_financier.json");
                return Optional.of(classpath);
            }
        } catch (Exception ignored) {
            // Aucune synthèse disponible ni sur disque ni sur le classpath.
        }
        return Optional.empty();
    }

    private JsonNode readFile(Path path) {
        try {
            if (Files.exists(path)) {
                JsonNode node = objectMapper.readTree(path.toFile());
                if (node != null && node.isObject()) {
                    return node;
                }
            }
        } catch (Exception e) {
            log.warn("Lecture impossible de {} : {}", path, e.getMessage());
        }
        return null;
    }

    public void save(JsonNode synthesis) {
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), synthesis);
            log.info("Synthèse financière enregistrée dans {}", filePath);
        } catch (Exception e) {
            throw new IllegalStateException("Impossible d'écrire la synthèse financière dans " + filePath, e);
        }
    }

    public Path path() {
        return filePath;
    }
}
