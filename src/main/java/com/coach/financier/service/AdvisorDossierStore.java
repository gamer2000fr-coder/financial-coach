package com.coach.financier.service;

import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stockage FICHIER des dossiers de suivi évaluables :
 * {@code <advisor-feedback.dir>/dossiers/advisor_dossier_YYYY-MM-DD.jsonl}.
 * <p>
 * Écrit à chaque clôture de conversation (best effort) : c'est ce dossier que le conseiller retrouve
 * en cliquant sur le lien du mail de suivi (§41/§43). Il ne contient que des identifiants techniques
 * et le contenu préparé par le Coach — le lien ne véhicule aucune donnée personnelle (§45).
 * <p>
 * Un seul dossier est conservé par session (le plus récent) : une nouvelle clôture de la même session
 * remplace la version courante à la lecture.
 */
@Service
public class AdvisorDossierStore {
    private static final Logger log = LoggerFactory.getLogger(AdvisorDossierStore.class);
    private static final String PREFIX = "advisor_dossier_";

    private final AdvisorFeedbackProperties properties;
    private final ObjectMapper objectMapper;
    private final Set<String> knownKeys = ConcurrentHashMap.newKeySet();
    private final Object writeLock = new Object();
    private volatile boolean keysLoaded = false;

    public AdvisorDossierStore(AdvisorFeedbackProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Enregistre un dossier (idempotent par session + horodatage) ; jamais bloquant pour la clôture. */
    public Optional<AdvisorFeedbackModels.AdvisorDossier> save(AdvisorFeedbackModels.AdvisorDossier dossier) {
        if (dossier == null || dossier.sessionId() == null || dossier.sessionId().isBlank()) {
            return Optional.empty();
        }
        String key = dossier.sessionId() + "|" + dossier.timestamp();
        if (knownKeys.contains(key)) {
            return Optional.of(dossier);
        }
        try {
            String line = objectMapper.writeValueAsString(dossier);
            JsonlFiles.append(JsonlFiles.fileFor(dossiersDir(), PREFIX, dayOf(dossier)),
                    List.of(line), writeLock);
            knownKeys.add(key);
            return Optional.of(dossier);
        } catch (Exception e) {
            log.warn("Dossier de suivi non persisté pour la session {} : {}", dossier.sessionId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Dossier COURANT d'une session (le plus récent), s'il existe. */
    public Optional<AdvisorFeedbackModels.AdvisorDossier> findBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return read(null, null).stream()
                .filter(dossier -> sessionId.equals(dossier.sessionId()))
                .max(java.util.Comparator.comparing(AdvisorFeedbackModels.AdvisorDossier::timestamp,
                        java.util.Comparator.nullsFirst(String::compareTo)));
    }

    /** Dossiers d'une période. */
    public List<AdvisorFeedbackModels.AdvisorDossier> read(LocalDate from, LocalDate to) {
        JsonlFiles.ReadResult<AdvisorFeedbackModels.AdvisorDossier> result = JsonlFiles.read(dossiersDir(),
                PREFIX, from, to, AdvisorFeedbackModels.AdvisorDossier.class, objectMapper);
        List<AdvisorFeedbackModels.AdvisorDossier> values = new ArrayList<>();
        for (AdvisorFeedbackModels.AdvisorDossier dossier : result.values()) {
            if (dossier != null) {
                values.add(dossier);
                knownKeys.add(dossier.sessionId() + "|" + dossier.timestamp());
            }
        }
        return List.copyOf(values);
    }

    /** Nombre de dossiers en attente d'évaluation sur une période (statut calculé hors stockage). */
    public List<AdvisorFeedbackModels.AdvisorDossier> withoutFeedback(LocalDate from, LocalDate to,
                                                                     Set<String> sessionsWithFeedback) {
        return read(from, to).stream()
                .filter(dossier -> !sessionsWithFeedback.contains(dossier.sessionId()))
                .toList();
    }

    public java.nio.file.Path dossiersDir() {
        return properties.baseDir().resolve("dossiers");
    }

    private static LocalDate dayOf(AdvisorFeedbackModels.AdvisorDossier dossier) {
        String reference = dossier.timestamp() != null && !dossier.timestamp().isBlank()
                ? dossier.timestamp() : dossier.createdAt();
        if (reference != null && reference.length() >= 10) {
            try {
                return LocalDate.parse(reference.substring(0, 10));
            } catch (Exception ignored) {
                // horodatage inattendu : jour courant
            }
        }
        return LocalDate.now();
    }
}
