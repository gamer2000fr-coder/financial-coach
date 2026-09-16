package com.coach.financier.service;

import com.coach.financier.config.PromptOptimizationProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Historique des prompts d'agents : sauvegarde le contenu EXISTANT avant tout remplacement.
 * <p>
 * C'est la garantie de RETOUR ARRIÈRE exigée par la promotion d'une version (§17) : aucune campagne ne
 * peut écraser définitivement le prompt de production sans qu'une copie datée soit conservée.
 * <p>
 * Structure (aucune base de données) :
 * <pre>
 * data/prompt-optimization/history/
 *   &lt;backupId&gt;-&lt;fichier&gt;   copie intégrale du prompt avant remplacement
 *   promotions.jsonl        index chronologique des sauvegardes/promotions
 * </pre>
 */
@Service
public class AgentPromptHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(AgentPromptHistoryStore.class);
    private static final String INDEX_FILE = "promotions.jsonl";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final PromptOptimizationProperties properties;
    private final PromptZoneService zoneService;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();

    public AgentPromptHistoryStore(PromptOptimizationProperties properties, PromptZoneService zoneService,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.zoneService = zoneService;
        // Champs inconnus tolérés : l'historique reste lisible même si le modèle évolue.
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Sauvegarde d'un prompt AVANT remplacement : permet de restaurer la version précédente (§17). */
    public record PromptBackup(String backupId, String agentKey, String file, String backupFile,
                               String contentHash, String reason, String createdAt) {

        public PromptBackup {
            agentKey = agentKey == null ? "" : agentKey;
            file = file == null ? "" : file;
            backupFile = backupFile == null ? "" : backupFile;
            contentHash = contentHash == null ? "" : contentHash;
            reason = reason == null ? "" : reason;
            createdAt = createdAt == null ? Instant.now().toString() : createdAt;
        }
    }

    public Path historyDir() {
        return properties.baseDir().resolve("history");
    }

    /**
     * Enregistre le contenu ACTUEL d'un prompt avant de le remplacer.
     *
     * @param agentKey clé éditable de l'agent ({@code credit_conso}, {@code principal}…)
     * @param file     fichier sous {@code ./agent} qui va être remplacé
     * @param content  contenu ACTUEL (celui qui serait perdu sans sauvegarde)
     * @param reason   motif lisible (ex. « promotion V4 de la campagne po-… »)
     */
    public PromptBackup backup(String agentKey, String file, String content, String reason) {
        String backupId = "bk-" + STAMP.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 4);
        String safeFile = file == null || file.isBlank() ? "prompt.txt"
                : file.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_");
        Path target = historyDir().resolve(backupId + "-" + safeFile);
        try {
            Files.createDirectories(historyDir());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Sauvegarde du prompt impossible : " + target, e);
        }
        PromptBackup backup = new PromptBackup(backupId, agentKey, safeFile, target.getFileName().toString(),
                zoneService.hash(content), reason, Instant.now().toString());
        try {
            JsonlFiles.append(historyDir().resolve(INDEX_FILE),
                    List.of(objectMapper.writeValueAsString(backup)), writeLock);
        } catch (Exception e) {
            log.warn("Index d'historique non mis à jour ({}) : {}", backupId, e.getMessage());
        }
        log.info("Prompt sauvegardé avant remplacement : {} ({})", backup.backupFile(), reason);
        return backup;
    }

    /** Sauvegardes connues, de la plus récente à la plus ancienne. */
    public List<PromptBackup> backups() {
        Path file = historyDir().resolve(INDEX_FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<PromptBackup> backups = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    backups.add(objectMapper.readValue(trimmed, PromptBackup.class));
                } catch (Exception e) {
                    // ligne invalide ignorée (l'index est un journal, jamais une source de vérité unique)
                }
            }
        } catch (IOException e) {
            log.warn("Lecture de l'historique impossible dans {} : {}", file, e.getMessage());
        }
        // Le fichier est APPEND-ONLY : à horodatage égal (résolution de l'horloge système), la dernière
        // ligne écrite est la plus récente. Sans cette inversion, un retour arrière pourrait restaurer une
        // sauvegarde obsolète.
        List<PromptBackup> newestFirst = new ArrayList<>(backups.size());
        for (int i = backups.size() - 1; i >= 0; i--) {
            newestFirst.add(backups.get(i));
        }
        newestFirst.sort(Comparator.comparing(PromptBackup::createdAt).reversed());
        return newestFirst;
    }

    /** Dernière sauvegarde d'un fichier donné (pour un retour arrière ciblé). */
    public Optional<PromptBackup> latestFor(String file) {
        return backups().stream().filter(backup -> backup.file().equals(file)).findFirst();
    }

    /** Contenu sauvegardé d'une version antérieure (permet le rollback). */
    public Optional<String> contentOf(PromptBackup backup) {
        if (backup == null || backup.backupFile().isBlank()) {
            return Optional.empty();
        }
        Path file = historyDir().resolve(backup.backupFile());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Contenu de sauvegarde illisible {} : {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** Contenu sauvegardé identifié par son identifiant de sauvegarde. */
    public Optional<String> contentOf(String backupId) {
        return backups().stream()
                .filter(backup -> backup.backupId().equals(backupId))
                .findFirst()
                .flatMap(this::contentOf);
    }
}
