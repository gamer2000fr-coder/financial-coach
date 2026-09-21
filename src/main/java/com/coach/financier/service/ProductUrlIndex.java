package com.coach.financier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Index des URLs OFFICIELLES des produits, extraites des fiches catalogue
 * ({@code data/catalogue/*.json}). Le backend ne fabrique jamais d'URL : cette classe
 * fournit l'unique source d'URL produit autorisée pour le dossier de suivi.
 * <p>
 * L'extraction est structurelle : on parcourt récursivement chaque fiche et on associe
 * à tout objet portant un {@code id} et une {@code url} le couple (identifiant → url).
 * Elle fonctionne donc aussi bien pour les offres à plat ({@code offres[]}) que pour les
 * assurances organisées en {@code produit} + {@code formules[]}.
 * <p>
 * Les URL de SOUSCRIPTION déclarées par une fiche ({@code url_souscription}) sont également
 * whitelistées — le Coach les cite comme prochaine étape après une simulation — sans remplacer
 * l'URL de référence du produit.
 */
@Service
public class ProductUrlIndex {
    private static final Logger log = LoggerFactory.getLogger(ProductUrlIndex.class);
    private static final String HTTP_PREFIX = "http";

    private final Map<String, String> urlById = new LinkedHashMap<>();
    private final Map<String, String> nameById = new LinkedHashMap<>();
    /** URL de souscription déclarées par les fiches ({@code url_souscription}), whitelistées à part. */
    private final Map<String, String> subscriptionUrlById = new LinkedHashMap<>();

    public ProductUrlIndex(ObjectMapper objectMapper,
                           @Value("${app.data.dir:./data}") String dataDir) {
        Path catalogueDir = Path.of(dataDir, "catalogue").toAbsolutePath().normalize();
        loadFromFileSystem(objectMapper, catalogueDir);
        if (urlById.isEmpty()) {
            loadFromClasspath(objectMapper);
        }
    }

    /** URL officielle d'un produit (ou {@code null} si la fiche n'en fournit pas). */
    public String urlFor(String productId) {
        return productId == null ? null : urlById.get(productId);
    }

    /** Nom officiel d'un produit tel que déclaré dans la fiche catalogue (ou {@code null}). */
    public String nameFor(String productId) {
        return productId == null ? null : nameById.get(productId);
    }

    public boolean isKnown(String productId) {
        return productId != null && (urlById.containsKey(productId) || nameById.containsKey(productId));
    }

    /** Toutes les URLs officielles connues (pour la whitelist anti-invention). */
    public java.util.Set<String> allUrls() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(urlById.values());
        all.addAll(subscriptionUrlById.values());
        return java.util.Set.copyOf(all);
    }

    private void loadFromFileSystem(ObjectMapper objectMapper, Path catalogueDir) {
        if (!Files.isDirectory(catalogueDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(catalogueDir, "*.json")) {
            for (Path file : stream) {
                try (InputStream in = Files.newInputStream(file)) {
                    collect(objectMapper.readTree(in));
                } catch (IOException e) {
                    log.warn("Fiche catalogue illisible {} : {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Catalogue introuvable dans {} : {}", catalogueDir, e.getMessage());
        }
    }

    private void loadFromClasspath(ObjectMapper objectMapper) {
        // Repli pour les exécutions packagées : mêmes fiches embarquées dans les resources.
        for (String file : new String[]{"credit_conso", "credit_immo", "epargne",
                "assurance_auto", "assurance_habitation", "assurance_emprunteur_immo"}) {
            try (InputStream in = new ClassPathResource("data/catalogue/" + file + ".json").getInputStream()) {
                collect(objectMapper.readTree(in));
            } catch (Exception ignored) {
                // Fiche absente du classpath : on continue.
            }
        }
    }

    private void collect(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            String id = text(node, "id");
            String url = text(node, "url");
            if (id != null && url != null && url.startsWith(HTTP_PREFIX)) {
                urlById.putIfAbsent(id, url);
                String name = firstText(node, "nom", "name");
                if (name != null) {
                    nameById.putIfAbsent(id, name);
                }
            }
            // URL de souscription déclarée par la fiche : whitelistée SANS écraser l'URL de la fiche.
            String subscriptionUrl = text(node, "url_souscription");
            if (id != null && subscriptionUrl != null && subscriptionUrl.startsWith(HTTP_PREFIX)) {
                subscriptionUrlById.putIfAbsent(id, subscriptionUrl);
            }
            node.fields().forEachRemaining(entry -> collect(entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(this::collect);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
