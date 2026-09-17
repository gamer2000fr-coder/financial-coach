package com.coach.financier.service;

import com.coach.financier.config.PromptOptimizationProperties;
import com.coach.financier.model.PromptOptimizationModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Persistance de l'ATELIER d'amélioration itérative des prompts : AUCUNE base de données, des fichiers
 * sous {@code <dir>/campaigns/<campaignId>/} (convention des autres modules du POC) :
 * <pre>
 * campaigns/&lt;campaignId&gt;/
 *   campaign.json     état de la campagne (machine d'état, compteurs, versions retenues)
 *   snapshot.json     SNAPSHOT DE RÉFÉRENCE immuable (question, contexte figé, prompt de référence)
 *   iterations.jsonl  une ligne par itération (JAMAIS écrasée : l'historique complet est conservé)
 *   feedback.jsonl    retours humains, dans l'ordre chronologique
 * threads/&lt;threadId&gt;.json
 *   FIL DE CONVERSATION : la mémoire qui enchaîne les campagnes (historique rejoué au cycle suivant)
 * </pre>
 * Garanties :
 * <ul>
 *   <li>écriture ATOMIQUE des fichiers d'état ({@code .tmp} puis déplacement atomique) : un crash
 *   laisse toujours un {@code campaign.json} lisible ;</li>
 *   <li>VERROU par campagne ({@link #withCampaignLock}) : un double clic ou une requête réseau répétée
 *   ne peut pas lancer deux itérations en parallèle (§36) ;</li>
 *   <li>reprise après redémarrage : l'état complet est reconstruit depuis les fichiers (§37) ;</li>
 *   <li>identifiants de campagne VALIDÉS (aucune traversée de répertoire).</li>
 * </ul>
 */
@Service
public class PromptOptimizationStore {

    private static final Logger log = LoggerFactory.getLogger(PromptOptimizationStore.class);
    private static final String CAMPAIGN_FILE = "campaign.json";
    private static final String SNAPSHOT_FILE = "snapshot.json";
    private static final String ITERATIONS_FILE = "iterations.jsonl";
    private static final String EDITIONS_FILE = "editions.jsonl";
    private static final String FEEDBACK_FILE = "feedback.jsonl";
    /** Identifiant accepté (campagne ET fil de conversation) : jamais de séparateur de chemin. */
    private static final Pattern CAMPAIGN_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private final PromptOptimizationProperties properties;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();
    private final Map<String, ReentrantLock> campaignLocks = new ConcurrentHashMap<>();

    public PromptOptimizationStore(PromptOptimizationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Résultat d'une lecture JSONL : valeurs valides + lignes invalides COMPTÉES (jamais ignorées en silence). */
    public record ReadResult<T>(List<T> values, long invalidLines) {
        static <T> ReadResult<T> empty() {
            return new ReadResult<>(List.of(), 0);
        }
    }

    // --- Chemins -------------------------------------------------------------------------------

    /** Répertoire d'une campagne (l'identifiant est validé : aucune traversée de chemin possible). */
    public Path campaignDir(String campaignId) {
        if (campaignId == null || !CAMPAIGN_ID.matcher(campaignId).matches()) {
            throw new IllegalArgumentException("Identifiant de campagne invalide : " + campaignId);
        }
        return properties.campaignsDir().resolve(campaignId);
    }

    public boolean exists(String campaignId) {
        return Files.isRegularFile(campaignDir(campaignId).resolve(CAMPAIGN_FILE));
    }

    // --- Campagne / snapshot ---------------------------------------------------------------------

    /** Crée une campagne (état initial + snapshot de référence) : refuse d'écraser une campagne existante. */
    public void create(PromptOptimizationModels.Campaign campaign, PromptOptimizationModels.Snapshot snapshot) {
        Path dir = campaignDir(campaign.campaignId());
        if (Files.isRegularFile(dir.resolve(CAMPAIGN_FILE))) {
            throw new IllegalStateException("Campagne déjà existante : " + campaign.campaignId());
        }
        saveSnapshot(snapshot);
        saveCampaign(campaign);
    }

    /** Écrit l'état de la campagne (écriture ATOMIQUE). */
    public void saveCampaign(PromptOptimizationModels.Campaign campaign) {
        writeJson(campaignDir(campaign.campaignId()).resolve(CAMPAIGN_FILE), campaign);
    }

    /** Écrit l'état de la campagne et renvoie l'état écrit (commodité d'orchestration). */
    public PromptOptimizationModels.Campaign saveCampaignAndReturn(PromptOptimizationModels.Campaign campaign) {
        saveCampaign(campaign);
        return campaign;
    }

    public Optional<PromptOptimizationModels.Campaign> find(String campaignId) {
        return readJson(campaignDir(campaignId).resolve(CAMPAIGN_FILE), PromptOptimizationModels.Campaign.class);
    }

    public PromptOptimizationModels.Campaign require(String campaignId) {
        return find(campaignId).orElseThrow(
                () -> new IllegalArgumentException("Campagne inconnue : " + campaignId));
    }

    /** Campagnes connues, de la plus récemment modifiée à la plus ancienne. */
    public List<PromptOptimizationModels.Campaign> list() {
        List<PromptOptimizationModels.Campaign> campaigns = new ArrayList<>();
        Path root = properties.campaignsDir();
        if (!Files.isDirectory(root)) {
            return campaigns;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                readJson(dir.resolve(CAMPAIGN_FILE), PromptOptimizationModels.Campaign.class).ifPresent(campaigns::add);
            }
        } catch (IOException e) {
            log.warn("Liste des campagnes impossible dans {} : {}", root, e.getMessage());
        }
        campaigns.sort(Comparator.comparing(PromptOptimizationModels.Campaign::updatedAt).reversed());
        return campaigns;
    }

    public void saveSnapshot(PromptOptimizationModels.Snapshot snapshot) {
        writeJson(campaignDir(snapshot.campaignId()).resolve(SNAPSHOT_FILE), snapshot);
    }

    /** Snapshot de référence de la campagne (figé pour toute la durée de la campagne). */
    public Optional<PromptOptimizationModels.Snapshot> snapshot(String campaignId) {
        return readJson(campaignDir(campaignId).resolve(SNAPSHOT_FILE), PromptOptimizationModels.Snapshot.class);
    }

    // --- Itérations ------------------------------------------------------------------------------

    /**
     * Ajoute une itération. Une itération DÉJÀ enregistrée pour ce numéro n'est jamais réécrite :
     * l'historique est append-only (§15 : « ne jamais écraser V1 avec V2 »).
     */
    public void appendIteration(PromptOptimizationModels.Iteration iteration) {
        Path file = campaignDir(iteration.campaignId()).resolve(ITERATIONS_FILE);
        boolean known = readJsonl(file, PromptOptimizationModels.Iteration.class).values().stream()
                .anyMatch(existing -> existing.iterationNumber() == iteration.iterationNumber());
        if (known) {
            log.warn("Itération {} déjà enregistrée pour la campagne {} : écriture ignorée",
                    iteration.iterationNumber(), iteration.campaignId());
            return;
        }
        appendJsonl(file, iteration);
    }

    /** Itérations enregistrées, dans l'ordre chronologique. */
    public ReadResult<PromptOptimizationModels.Iteration> iterations(String campaignId) {
        return readJsonl(campaignDir(campaignId).resolve(ITERATIONS_FILE),
                PromptOptimizationModels.Iteration.class);
    }

    /** Dernière itération enregistrée (celle que l'IHM doit afficher en premier). */
    public Optional<PromptOptimizationModels.Iteration> latestIteration(String campaignId) {
        return iterations(campaignId).values().stream()
                .max(Comparator.comparingInt(PromptOptimizationModels.Iteration::iterationNumber));
    }

    /**
     * Enregistre une version produite par une ÉDITION HORS ITÉRATION — typiquement l'avis humain
     * appliqué par l'Agent A au moment d'une reprise (§12), qui ne consomme pas d'itération.
     */
    public void appendEdition(String campaignId, PromptOptimizationModels.PromptVersion version) {
        Path file = campaignDir(campaignId).resolve(EDITIONS_FILE);
        boolean known = readJsonl(file, PromptOptimizationModels.PromptVersion.class).values().stream()
                .anyMatch(existing -> existing.version().equals(version.version()));
        if (known) {
            return;
        }
        appendJsonl(file, version);
    }

    /** Éditions hors itération, dans l'ordre d'écriture. */
    public ReadResult<PromptOptimizationModels.PromptVersion> editions(String campaignId) {
        return readJsonl(campaignDir(campaignId).resolve(EDITIONS_FILE),
                PromptOptimizationModels.PromptVersion.class);
    }

    /**
     * Toutes les versions connues, dans l'ordre : V0 (snapshot) puis chaque version produite. Une version
     * peut provenir d'une ITÉRATION ou d'une ÉDITION HORS ITÉRATION (avis humain, §12). Aucune n'est
     * écrasée : toutes restent consultables et reconstructibles (§15/§16).
     */
    public List<PromptOptimizationModels.PromptVersion> versions(String campaignId) {
        Map<String, PromptOptimizationModels.PromptVersion> byVersion = new LinkedHashMap<>();
        snapshot(campaignId).ifPresent(snapshot -> byVersion.put(snapshot.promptVersion(),
                new PromptOptimizationModels.PromptVersion(snapshot.promptVersion(),
                        snapshot.initialEditableSection(), snapshot.promptHash(),
                        PromptOptimizationModels.versionNumber(snapshot.promptVersion()))));
        for (PromptOptimizationModels.PromptVersion edition : editions(campaignId).values()) {
            byVersion.putIfAbsent(edition.version(), edition);
        }
        for (PromptOptimizationModels.Iteration iteration : iterations(campaignId).values()) {
            if (!iteration.promptVersion().isBlank()) {
                byVersion.putIfAbsent(iteration.promptVersion(), new PromptOptimizationModels.PromptVersion(
                        iteration.promptVersion(), iteration.editableSection(), iteration.promptHash(),
                        PromptOptimizationModels.versionNumber(iteration.promptVersion())));
            }
            if (iteration.producedNewVersion()) {
                byVersion.putIfAbsent(iteration.resultingVersion(), new PromptOptimizationModels.PromptVersion(
                        iteration.resultingVersion(), iteration.resultingEditableSection(), iteration.promptHash(),
                        PromptOptimizationModels.versionNumber(iteration.resultingVersion())));
            }
        }
        return byVersion.values().stream()
                .sorted(Comparator.comparingInt(version -> PromptOptimizationModels.versionNumber(version.version())))
                .toList();
    }

    /** Zone éditable d'une version donnée, ou vide si la version est inconnue. */
    public Optional<String> editableSectionOf(String campaignId, String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        return versions(campaignId).stream()
                .filter(candidate -> version.equals(candidate.version()))
                .map(PromptOptimizationModels.PromptVersion::editableSection)
                .findFirst();
    }

    // --- Feedback humain --------------------------------------------------------------------------

    /**
     * Ajoute un retour humain. Le fichier reste APPEND-ONLY : marquer un avis « appliqué » écrit une
     * nouvelle ligne, et la dernière ligne d'un même {@code feedbackId} fait foi (idempotent par état).
     */
    public void appendFeedback(PromptOptimizationModels.HumanFeedback feedback) {
        Path file = campaignDir(feedback.campaignId()).resolve(FEEDBACK_FILE);
        boolean known = readJsonl(file, PromptOptimizationModels.HumanFeedback.class).values().stream()
                .anyMatch(existing -> existing.feedbackId().equals(feedback.feedbackId())
                        && existing.applied().equals(feedback.applied()));
        if (known) {
            return;
        }
        appendJsonl(file, feedback);
    }

    /** Toutes les écritures d'avis, dans l'ordre chronologique (historique complet). */
    public ReadResult<PromptOptimizationModels.HumanFeedback> feedbacks(String campaignId) {
        return readJsonl(campaignDir(campaignId).resolve(FEEDBACK_FILE),
                PromptOptimizationModels.HumanFeedback.class);
    }

    /** État COURANT de chaque avis : la dernière écriture pour un même identifiant fait foi. */
    public ReadResult<PromptOptimizationModels.HumanFeedback> currentFeedbacks(String campaignId) {
        ReadResult<PromptOptimizationModels.HumanFeedback> all = feedbacks(campaignId);
        Map<String, PromptOptimizationModels.HumanFeedback> byId = new LinkedHashMap<>();
        for (PromptOptimizationModels.HumanFeedback feedback : all.values()) {
            byId.put(feedback.feedbackId(), feedback);
        }
        return new ReadResult<>(List.copyOf(byId.values()), all.invalidLines());
    }

    /** Dernier retour humain non encore appliqué à une itération, s'il existe. */
    public Optional<PromptOptimizationModels.HumanFeedback> pendingHumanFeedback(String campaignId) {
        return currentFeedbacks(campaignId).values().stream()
                .filter(feedback -> !feedback.applied())
                .reduce((first, second) -> second);
    }

    // --- Fils de conversation (mémoire de l'atelier) ----------------------------------------------

    /**
     * Répertoire des FILS de conversation : {@code <dir>/threads/<threadId>.json}. Un fil enchaîne les
     * campagnes et porte l'historique rejoué au cycle suivant (une campagne reste, elle, une seule
     * question de test).
     */
    public Path threadsDir() {
        return properties.baseDir().resolve("threads");
    }

    /** Écrit un fil (écriture ATOMIQUE, comme les autres états de l'atelier). */
    public void saveThread(PromptOptimizationModels.ConversationThread thread) {
        if (thread == null || thread.threadId().isBlank()) {
            throw new IllegalArgumentException("Fil de conversation sans identifiant.");
        }
        writeJson(threadFile(thread.threadId()), thread);
    }

    public Optional<PromptOptimizationModels.ConversationThread> thread(String threadId) {
        return readJson(threadFile(threadId), PromptOptimizationModels.ConversationThread.class);
    }

    /** Fils connus, du plus récemment modifié au plus ancien (les plus récents en premier dans l'IHM). */
    public List<PromptOptimizationModels.ConversationThread> threads() {
        List<PromptOptimizationModels.ConversationThread> threads = new ArrayList<>();
        Path root = threadsDir();
        if (!Files.isDirectory(root)) {
            return threads;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.json")) {
            for (Path file : stream) {
                readJson(file, PromptOptimizationModels.ConversationThread.class).ifPresent(threads::add);
            }
        } catch (IOException e) {
            log.warn("Liste des fils de conversation impossible dans {} : {}", root, e.getMessage());
        }
        threads.sort(Comparator.comparing(PromptOptimizationModels.ConversationThread::updatedAt).reversed());
        return threads;
    }

    /**
     * Fil auquel appartient une campagne, s'il existe. L'association est stockée DANS le fil
     * ({@code campaignIds}) : une campagne n'a donc pas à connaître son fil, et les campagnes
     * antérieures à cette fonctionnalité restent parfaitement lisibles.
     */
    public Optional<PromptOptimizationModels.ConversationThread> threadOf(String campaignId) {
        if (campaignId == null || campaignId.isBlank()) {
            return Optional.empty();
        }
        return threads().stream().filter(thread -> thread.campaignIds().contains(campaignId)).findFirst();
    }

    private Path threadFile(String threadId) {
        if (threadId == null || !CAMPAIGN_ID.matcher(threadId).matches()) {
            throw new IllegalArgumentException("Identifiant de fil de conversation invalide : " + threadId);
        }
        return threadsDir().resolve(threadId + ".json");
    }

    // --- Concurrence ------------------------------------------------------------------------------

    /** Indique si un traitement est EN COURS pour cette campagne (verrou pris par un autre traitement). */
    public boolean isBusy(String campaignId) {
        ReentrantLock lock = campaignLocks.get(campaignId);
        return lock != null && lock.isLocked() && !lock.isHeldByCurrentThread();
    }

    /**
     * Exécute une action sous VERROU de campagne : un second traitement simultané sur la même campagne
     * est REFUSÉ (double clic, requête répétée) au lieu de lancer deux itérations en parallèle (§36).
     *
     * @throws IllegalStateException si un traitement est déjà en cours pour cette campagne
     */
    public <T> T withCampaignLock(String campaignId, Supplier<T> action) {
        ReentrantLock lock = campaignLocks.computeIfAbsent(campaignId, key -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IllegalStateException("Un traitement est déjà en cours pour la campagne " + campaignId);
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    // --- Entrées / sorties ------------------------------------------------------------------------

    private void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, objectMapper.writeValueAsString(value), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Système de fichiers sans déplacement atomique : remplacement simple (le fichier
                // précédent reste lisible en cas d'échec).
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Écriture impossible : " + file, e);
        }
    }

    private <T> Optional<T> readJson(Path file, Class<T> type) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), type));
        } catch (Exception e) {
            log.warn("Fichier illisible {} : {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private <T> ReadResult<T> readJsonl(Path file, Class<T> type) {
        if (!Files.isRegularFile(file)) {
            return ReadResult.empty();
        }
        List<T> values = new ArrayList<>();
        long invalidLines = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    values.add(objectMapper.readValue(trimmed, type));
                } catch (Exception e) {
                    invalidLines++;
                }
            }
        } catch (IOException e) {
            log.warn("Lecture impossible {} : {}", file, e.getMessage());
        }
        return new ReadResult<>(values, invalidLines);
    }

    private void appendJsonl(Path file, Object value) {
        try {
            JsonlFiles.append(file, List.of(objectMapper.writeValueAsString(value)), writeLock);
        } catch (Exception e) {
            throw new IllegalStateException("Écriture JSONL impossible : " + file, e);
        }
    }
}
