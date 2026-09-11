package com.coach.financier.service;

import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.QualityModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stockage FICHIER des feedbacks clients (aucune base de données) :
 * {@code <quality.dir>/feedback/coach_feedback_YYYY-MM-DD.jsonl}.
 * <p>
 * IDEMPOTENCE (§44) : un feedback est identifié par {@code feedbackId} ET par {@code sessionId} —
 * un double clic, un retry réseau ou un appel répété ne crée JAMAIS un second enregistrement pour la
 * même clôture de conversation.
 * <p>
 * L'analyse IA des commentaires n'est PAS implémentée dans cette version (P2) : les thèmes de
 * commentaires affichés proviennent d'une heuristique locale déterministe (voir
 * {@code QualityAnalyticsService}), jamais d'un appel IA par feedback.
 */
@Service
public class QualityFeedbackStore {
    private static final Logger log = LoggerFactory.getLogger(QualityFeedbackStore.class);
    private static final String PREFIX = "coach_feedback_";

    private final QualityProperties properties;
    private final ObjectMapper objectMapper;
    private final Set<String> knownFeedbackIds = ConcurrentHashMap.newKeySet();
    private final Set<String> knownSessions = ConcurrentHashMap.newKeySet();
    private final Object writeLock = new Object();
    private volatile boolean idsLoaded = false;

    public QualityFeedbackStore(QualityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Résultat d'un enregistrement de feedback (jamais bloquant pour la clôture de conversation). */
    public record SaveResult(boolean saved, boolean duplicate, QualityModels.CoachFeedback feedback, String error) {
    }

    /** Enregistre un feedback, en dédupliquant par {@code feedbackId} et par {@code sessionId}. */
    public SaveResult save(QualityModels.CoachFeedback feedback) {
        if (feedback == null || feedback.feedbackId() == null || feedback.feedbackId().isBlank()) {
            return new SaveResult(false, false, feedback, "feedback sans identifiant");
        }
        ensureIdsLoaded();
        if (knownFeedbackIds.contains(feedback.feedbackId())
                || (feedback.sessionId() != null && knownSessions.contains(feedback.sessionId()))) {
            return new SaveResult(false, true, feedback, null);
        }
        Path file = JsonlFiles.fileFor(properties.feedbackDir(), PREFIX, dayOf(feedback));
        try {
            String line = objectMapper.writeValueAsString(feedback);
            JsonlFiles.append(file, List.of(line), writeLock);
        } catch (Exception e) {
            // Un échec d'écriture ne doit JAMAIS empêcher la clôture de la conversation (§43).
            log.warn("Feedback qualité non stocké ({}) : {}", feedback.feedbackId(), e.getMessage());
            return new SaveResult(false, false, feedback, e.getMessage());
        }
        knownFeedbackIds.add(feedback.feedbackId());
        if (feedback.sessionId() != null) {
            knownSessions.add(feedback.sessionId());
        }
        return new SaveResult(true, false, feedback, null);
    }

    /** Feedbacks d'une session (au plus un), pour l'idempotence exposée à l'API. */
    public Optional<QualityModels.CoachFeedback> findBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return read(null, null).feedback().stream()
                .filter(item -> sessionId.equals(item.sessionId()))
                .findFirst();
    }

    /**
     * Ajout EN LOT (jeu de démonstration) : déduplication par {@code feedbackId} uniquement, chaque
     * session de démonstration pouvant porter son propre avis.
     */
    public List<QualityModels.CoachFeedback> appendDemo(List<QualityModels.CoachFeedback> feedback) {
        if (feedback == null || feedback.isEmpty()) {
            return List.of();
        }
        ensureIdsLoaded();
        Map<LocalDate, List<String>> byDay = new LinkedHashMap<>();
        List<QualityModels.CoachFeedback> written = new ArrayList<>();
        for (QualityModels.CoachFeedback item : feedback) {
            if (item == null || item.feedbackId() == null || item.feedbackId().isBlank()) {
                continue;
            }
            if (!knownFeedbackIds.add(item.feedbackId())) {
                continue; // déjà généré (relance) : aucun doublon
            }
            try {
                byDay.computeIfAbsent(dayOf(item), day -> new ArrayList<>())
                        .add(objectMapper.writeValueAsString(item));
                written.add(item);
                if (item.sessionId() != null) {
                    knownSessions.add(item.sessionId());
                }
            } catch (Exception e) {
                knownFeedbackIds.remove(item.feedbackId());
                log.warn("Feedback de démonstration non sérialisable : {}", e.getMessage());
            }
        }
        for (Map.Entry<LocalDate, List<String>> entry : byDay.entrySet()) {
            try {
                JsonlFiles.append(JsonlFiles.fileFor(properties.feedbackDir(), PREFIX, entry.getKey()),
                        entry.getValue(), writeLock);
            } catch (Exception e) {
                log.warn("Feedbacks de démonstration non stockés : {}", e.getMessage());
                written.clear();
            }
        }
        return List.copyOf(written);
    }

    /** Résultat de lecture : feedbacks + lignes invalides comptées. */
    public record ReadResult(List<QualityModels.CoachFeedback> feedback, long invalidLines) {
    }

    /** Feedbacks d'une période (bornes incluses). */
    public ReadResult read(LocalDate from, LocalDate to) {
        JsonlFiles.ReadResult<QualityModels.CoachFeedback> result = JsonlFiles.read(
                properties.feedbackDir(), PREFIX, from, to, QualityModels.CoachFeedback.class, objectMapper);
        List<QualityModels.CoachFeedback> values = new ArrayList<>();
        for (QualityModels.CoachFeedback item : result.values()) {
            if (item == null) {
                continue;
            }
            values.add(item);
            if (item.feedbackId() != null) {
                knownFeedbackIds.add(item.feedbackId());
            }
            if (item.sessionId() != null) {
                knownSessions.add(item.sessionId());
            }
        }
        return new ReadResult(List.copyOf(values), result.invalidLines());
    }

    /** Jours pour lesquels un fichier de feedback existe. */
    public List<LocalDate> availableDays() {
        return JsonlFiles.days(properties.feedbackDir(), PREFIX);
    }

    public Path feedbackDir() {
        return properties.feedbackDir();
    }

    private static LocalDate dayOf(QualityModels.CoachFeedback feedback) {
        String reference = feedback.timestamp() != null && !feedback.timestamp().isBlank()
                ? feedback.timestamp() : feedback.createdAt();
        if (reference != null && reference.length() >= 10) {
            try {
                return LocalDate.parse(reference.substring(0, 10));
            } catch (Exception ignored) {
                // horodatage inattendu : on retombe sur le jour courant
            }
        }
        return LocalDate.now();
    }

    private void ensureIdsLoaded() {
        if (idsLoaded) {
            return;
        }
        synchronized (writeLock) {
            if (idsLoaded) {
                return;
            }
            idsLoaded = true; // marqué AVANT la lecture (la lecture alimente les mêmes ensembles)
            read(null, null);
        }
    }
}
