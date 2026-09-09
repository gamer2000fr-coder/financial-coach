package com.coach.financier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Résout les demandes de données de l'IA à partir du catalogue data.json :
 * <ul>
 *   <li>{@link #catalog()} renvoie la liste {path, description} à envoyer à l'IA à chaque tour ;</li>
 *   <li>{@link #fetch(List)} renvoie, pour les paths demandés, une liste [{description, data}]
 *   lue dans le répertoire de données consolidé ./data.</li>
 * </ul>
 * Seuls les paths présents dans le catalogue data.json sont acceptés (sécurité anti-traversal).
 */
@Service
public class DataRequestService {
    private static final Logger log = LoggerFactory.getLogger(DataRequestService.class);
    private static final String DATA_JSON = "data.json";

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final boolean cascadeEnabled;
    private final JsonNode catalogNode;
    private final Map<String, String> descriptionByPath = new LinkedHashMap<>();

    public DataRequestService(ObjectMapper objectMapper,
                              @Value("${app.data.dir:./data}") String dataDir,
                              @Value("${cascade:false}") boolean cascadeEnabled) throws IOException {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        this.cascadeEnabled = cascadeEnabled;
        this.catalogNode = loadCatalog();
    }

    /** Catalogue ({path, description}) des données disponibles, envoyé à l'IA à chaque tour. */
    public JsonNode catalog() {
        return catalogNode;
    }

    /** Entrées du catalogue sous forme de liste de {path, description}. */
    public List<Map<String, String>> catalogEntries() {
        List<Map<String, String>> entries = new ArrayList<>();
        if (catalogNode != null && catalogNode.isArray()) {
            for (JsonNode node : catalogNode) {
                String path = node.path("path").asText(null);
                String description = node.path("description").asText("");
                if (path == null || path.isBlank()) {
                    continue;
                }
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("path", path);
                entry.put("description", description);
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Renvoie le contenu des fichiers demandés par l'IA au format [{description, data}].
     * Les paths inconnus, déjà vus ou hors du répertoire de données sont ignorés.
     */
    public List<Map<String, Object>> fetch(List<String> paths) {
        return fetch(paths, null);
    }

    /**
     * Variante avec restriction : seuls les paths présents dans {@code allowedPaths} sont acceptés.
     * Permet d'empêcher structurellement l'IA d'accéder à des produits hors périmètre.
     */
    public List<Map<String, Object>> fetch(List<String> paths, Set<String> allowedPaths) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> cascadeSeen = new LinkedHashSet<>();
        if (paths != null) {
            for (String path : paths) {
                String normalized = normalizePath(path);
                if (normalized == null || !descriptionByPath.containsKey(normalized) || !seen.add(normalized)) {
                    continue;
                }
                if (allowedPaths != null && !allowedPaths.contains(normalized)) {
                    continue;
                }
                Object content = readFileContent(normalized);
                if (content != null) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("description", descriptionByPath.get(normalized));
                    entry.put("data", content);
                    result.add(entry);
                    appendCascade(normalized, result, cascadeSeen);
                }
            }
        }
        return result;
    }

    /**
     * Lit un fichier de CONFIANCE (chemins déclarés d'un agent dans agents.json, pas demandés
     * par l'IA) sous forme {description, data}. Description issue du catalogue data.json quand
     * le path y figure, sinon libellé lisible dérivé du nom de fichier.
     */
    public Map<String, Object> readEntry(String path) {
        String normalized = normalizePath(path);
        if (normalized == null) {
            return null;
        }
        Object content = readFileContent(normalized);
        if (content == null) {
            return null;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("description", descriptionByPath.getOrDefault(normalized, readableName(normalized)));
        entry.put("data", content);
        return entry;
    }

    private static String readableName(String path) {
        String base = path;
        if (base.startsWith("/data/")) {
            base = base.substring("/data/".length());
        }
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.endsWith(".json")) {
            base = base.substring(0, base.length() - ".json".length());
        } else if (base.endsWith(".txt")) {
            base = base.substring(0, base.length() - ".txt".length());
        }
        String label = base.replace('_', ' ').trim();
        return path != null && path.contains("/cascade/") ? "Règles de décision " + label : label;
    }

    /**
     * Si la cascade est activée et que le path demandé est un JSON du catalogue,
     * ajoute le contenu du fichier cascade associé (même nom de base, extension .txt,
     * dans /data/catalogue/cascade/).
     */
    private void appendCascade(String catalogPath, List<Map<String, Object>> result, Set<String> seen) {
        if (!cascadeEnabled || catalogPath == null || !catalogPath.startsWith("/data/catalogue/")
                || !catalogPath.endsWith(".json")) {
            return;
        }
        String stem = catalogPath.substring("/data/catalogue/".length());
        stem = stem.substring(0, stem.length() - ".json".length()); // ex. "epargne"
        String cascadeKey = "/data/catalogue/cascade/" + stem + ".txt";
        if (!seen.add(cascadeKey)) {
            return;
        }
        String relative = "catalogue/cascade/" + stem + ".txt";
        try {
            Path file = dataDir.resolve(relative).normalize();
            if (!file.startsWith(dataDir) || !Files.isRegularFile(file)) {
                log.debug("Cascade introuvable pour {} : {}", catalogPath, relative);
                return;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("description", "Cascade " + stem.replace('_', ' '));
            entry.put("data", text);
            result.add(entry);
        } catch (IOException e) {
            log.warn("Lecture impossible de la cascade {} : {}", cascadeKey, e.getMessage());
        }
    }

    /** Résout un path "/data/..." vers un fichier sous dataDir et le lit (JSON parsé ou texte brut). */
    private Object readFileContent(String catalogPath) {
        String relative = catalogPath.startsWith("/data/") ? catalogPath.substring("/data/".length()) : catalogPath;
        try {
            Path file = dataDir.resolve(relative).normalize();
            if (!file.startsWith(dataDir) || !Files.isRegularFile(file)) {
                log.warn("Fichier demandé hors du répertoire de données ou inexistant : {}", catalogPath);
                return null;
            }
            if (relative.endsWith(".json")) {
                return objectMapper.readTree(file.toFile());
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Lecture impossible de {} : {}", catalogPath, e.getMessage());
            return null;
        }
    }

    private JsonNode loadCatalog() throws IOException {
        Path catalogFile = dataDir.resolve(DATA_JSON).normalize();
        JsonNode node = objectMapper.readTree(catalogFile.toFile());
        if (node != null && node.isArray()) {
            for (JsonNode entry : node) {
                String path = entry.path("path").asText(null);
                String description = entry.path("description").asText("");
                if (path != null && !path.isBlank()) {
                    descriptionByPath.put(normalizePath(path), description);
                }
            }
        }
        return node;
    }

    private static String normalizePath(String path) {
        if (path == null) return null;
        String p = path.trim();
        return p.isBlank() ? null : p;
    }
}
