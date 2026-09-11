package com.coach.financier.service;

import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stockage FICHIER des feedbacks conseiller :
 * {@code <advisor-feedback.dir>/events/advisor_feedback_YYYY-MM-DD.jsonl}.
 * <p>
 * IDEMPOTENCE (§16) : déduplication par {@code feedbackId} <b>et</b> par clé logique
 * {@code sessionId + conseiller + version} — un double clic, un retry ou un rafraîchissement ne
 * créent jamais deux fois le même événement.
 * <p>
 * HISTORIQUE (§17) : une révision du feedback par le conseiller est AJOUTÉE (version suivante,
 * événement {@code UPDATED}) — l'ancien événement n'est jamais écrasé silencieusement.
 */
@Service
public class AdvisorFeedbackStore {
    private static final Logger log = LoggerFactory.getLogger(AdvisorFeedbackStore.class);
    private static final String PREFIX = "advisor_feedback_";

    private final AdvisorFeedbackProperties properties;
    private final ObjectMapper objectMapper;
    private final Set<String> knownKeys = ConcurrentHashMap.newKeySet();
    private final Object writeLock = new Object();
    private volatile boolean keysLoaded = false;

    public AdvisorFeedbackStore(AdvisorFeedbackProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Résultat d'un enregistrement (jamais bloquant pour le dossier conseiller, §38). */
    public record SaveResult(boolean saved, boolean duplicate, AdvisorFeedbackModels.AdvisorFeedback feedback,
                             String error) {
    }

    /** Enregistre un feedback ; version et événement (CREATED/UPDATED) sont calculés ici. */
    public SaveResult save(AdvisorFeedbackModels.AdvisorFeedbackRequest request,
                           AdvisorFeedbackModels.AdvisorFeedback built) {
        if (built == null || built.feedbackId() == null || built.feedbackId().isBlank()) {
            return new SaveResult(false, false, built, "feedback sans identifiant");
        }
        ensureKeysLoaded();
        String logicalKey = logicalKey(built.sessionId(), built.advisorIdHash(), built.version());
        if (knownKeys.contains(built.feedbackId()) || knownKeys.contains(logicalKey)) {
            return new SaveResult(false, true, built, null);
        }
        if (!write(built)) {
            return new SaveResult(false, false, built, "écriture impossible dans " + properties.eventsDir());
        }
        knownKeys.add(built.feedbackId());
        knownKeys.add(logicalKey);
        return new SaveResult(true, false, built, null);
    }

    /**
     * Prochaine version disponible pour une session donnée (1 si aucun feedback n'existe, sinon
     * version courante + 1) : garantit la conservation de l'historique.
     */
    public int nextVersion(String sessionId, String advisorIdHash) {
        return latestBySession(sessionId)
                .map(current -> current.version() + 1)
                .orElse(1);
    }

    /** Feedback COURANT (version la plus élevée) d'une session, s'il existe. */
    public Optional<AdvisorFeedbackModels.AdvisorFeedback> latestBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return read(null, null).feedback().stream()
                .filter(item -> sessionId.equals(item.sessionId()))
                .max(java.util.Comparator.comparingInt(AdvisorFeedbackModels.AdvisorFeedback::version));
    }

    /** Résultat de lecture : feedbacks + lignes invalides comptées. */
    public record ReadResult(List<AdvisorFeedbackModels.AdvisorFeedback> feedback, long invalidLines) {
    }

    /** Feedbacks d'une période (bornes incluses). */
    public ReadResult read(LocalDate from, LocalDate to) {
        JsonlFiles.ReadResult<AdvisorFeedbackModels.AdvisorFeedback> result = JsonlFiles.read(
                properties.eventsDir(), PREFIX, from, to, AdvisorFeedbackModels.AdvisorFeedback.class,
                objectMapper);
        List<AdvisorFeedbackModels.AdvisorFeedback> values = new ArrayList<>();
        for (AdvisorFeedbackModels.AdvisorFeedback item : result.values()) {
            if (item == null) {
                continue;
            }
            values.add(item);
            if (item.feedbackId() != null) {
                knownKeys.add(item.feedbackId());
            }
            knownKeys.add(logicalKey(item.sessionId(), item.advisorIdHash(), item.version()));
        }
        return new ReadResult(List.copyOf(values), result.invalidLines());
    }

    /** Jours pour lesquels un fichier de feedback conseiller existe. */
    public List<LocalDate> availableDays() {
        return JsonlFiles.days(properties.eventsDir(), PREFIX);
    }

    public java.nio.file.Path eventsDir() {
        return properties.eventsDir();
    }

    /** Ajout EN LOT (jeu de démonstration), dédupliqué par identifiant. */
    public List<AdvisorFeedbackModels.AdvisorFeedback> appendDemo(
            List<AdvisorFeedbackModels.AdvisorFeedback> feedback) {
        if (feedback == null || feedback.isEmpty()) {
            return List.of();
        }
        ensureKeysLoaded();
        Map<LocalDate, List<AdvisorFeedbackModels.AdvisorFeedback>> byDay = new LinkedHashMap<>();
        List<AdvisorFeedbackModels.AdvisorFeedback> written = new ArrayList<>();
        for (AdvisorFeedbackModels.AdvisorFeedback item : feedback) {
            if (item == null || item.feedbackId() == null || item.feedbackId().isBlank()) {
                continue;
            }
            if (!knownKeys.add(item.feedbackId())) {
                continue;
            }
            byDay.computeIfAbsent(dayOf(item), day -> new ArrayList<>()).add(item);
            written.add(item);
        }
        for (Map.Entry<LocalDate, List<AdvisorFeedbackModels.AdvisorFeedback>> entry : byDay.entrySet()) {
            List<String> lines = new ArrayList<>();
            for (AdvisorFeedbackModels.AdvisorFeedback item : entry.getValue()) {
                try {
                    lines.add(objectMapper.writeValueAsString(item));
                } catch (Exception e) {
                    log.warn("Feedback conseiller non sérialisable : {}", e.getMessage());
                }
            }
            try {
                JsonlFiles.append(JsonlFiles.fileFor(properties.eventsDir(), PREFIX, entry.getKey()),
                        lines, writeLock);
            } catch (Exception e) {
                log.warn("Feedbacks conseiller non stockés : {}", e.getMessage());
                written.clear();
            }
        }
        return List.copyOf(written);
    }

    private boolean write(AdvisorFeedbackModels.AdvisorFeedback feedback) {
        try {
            String line = objectMapper.writeValueAsString(feedback);
            JsonlFiles.append(JsonlFiles.fileFor(properties.eventsDir(), PREFIX, dayOf(feedback)),
                    List.of(line), writeLock);
            return true;
        } catch (Exception e) {
            log.warn("Feedback conseiller non stocké ({}) : {}", feedback.feedbackId(), e.getMessage());
            return false;
        }
    }

    private static String logicalKey(String sessionId, String advisorIdHash, int version) {
        return "key:" + sessionId + "|" + (advisorIdHash == null ? "" : advisorIdHash) + "|" + version;
    }

    private static LocalDate dayOf(AdvisorFeedbackModels.AdvisorFeedback feedback) {
        String reference = feedback.timestamp() != null && !feedback.timestamp().isBlank()
                ? feedback.timestamp() : feedback.createdAt();
        if (reference != null && reference.length() >= 10) {
            try {
                return LocalDate.parse(reference.substring(0, 10));
            } catch (Exception ignored) {
                // horodatage inattendu : jour courant
            }
        }
        return LocalDate.now();
    }

    private void ensureKeysLoaded() {
        if (keysLoaded) {
            return;
        }
        synchronized (writeLock) {
            if (keysLoaded) {
                return;
            }
            keysLoaded = true;
            read(null, null);
        }
    }
}
