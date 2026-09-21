package com.coach.financier.service;

import com.coach.financier.model.DirectoryModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Historique des STATUTS d'avancement des dossiers (page « Centre d'appels ») :
 * {@code <call-center.dir>/status_events_YYYY-MM-DD.jsonl}.
 * <p>
 * Append-only, comme les autres modules du POC : chaque changement de statut écrit une ligne (statut
 * précédent, nouveau statut, commentaire, horodatage). Le statut COURANT d'un dossier est le dernier
 * événement écrit pour sa session — un dossier sans événement est « Nouveau ».
 * <p>
 * Un échec d'écriture n'est jamais bloquant : il est signalé à l'appelant (le dossier reste consultable).
 */
@Service
public class CallCenterStatusStore {
    private static final Logger log = LoggerFactory.getLogger(CallCenterStatusStore.class);
    private static final String PREFIX = "status_events_";

    private final ObjectMapper objectMapper;
    private final Path dir;
    private final Object writeLock = new Object();

    public CallCenterStatusStore(ObjectMapper objectMapper,
                                 @Value("${app.call-center.dir:./data/call-center}") String directory) {
        this.objectMapper = objectMapper;
        this.dir = Path.of(directory == null || directory.isBlank() ? "./data/call-center" : directory.trim());
    }

    public Path dir() {
        return dir;
    }

    /** Enregistre un changement de statut (horodatage et identifiant attribués ici). */
    public Optional<DirectoryModels.DossierStatusEvent> save(String sessionId, String status, String statusLabel,
                                                            String previousStatus, String comment) {
        if (sessionId == null || sessionId.isBlank() || status == null || status.isBlank()) {
            return Optional.empty();
        }
        DirectoryModels.DossierStatusEvent event = new DirectoryModels.DossierStatusEvent(
                "status-" + UUID.randomUUID(), sessionId.trim(), status.trim().toUpperCase(java.util.Locale.ROOT),
                statusLabel, previousStatus, comment == null || comment.isBlank() ? null : comment.trim(),
                Instant.now().toString());
        try {
            String line = objectMapper.writeValueAsString(event);
            LocalDate day = dayOf(event.timestamp());
            JsonlFiles.append(JsonlFiles.fileFor(dir, PREFIX, day), List.of(line), writeLock);
            return Optional.of(event);
        } catch (Exception e) {
            log.warn("Statut non enregistré pour la session {} : {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Statut COURANT d'une session : le dernier événement, s'il en existe un. */
    public Optional<DirectoryModels.DossierStatusEvent> latestBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return history(sessionId).stream().reduce((first, second) -> second);
    }

    /**
     * Vue d'ensemble du suivi pour une période : dernier événement de chaque session + nombre de messages
     * laissés (une seule lecture de fichiers pour toute la liste, quel que soit le nombre de dossiers).
     */
    public record StatusSummary(Map<String, DirectoryModels.DossierStatusEvent> latest,
                                Map<String, Integer> noteCounts) {

        public StatusSummary {
            latest = latest == null ? Map.of() : Map.copyOf(latest);
            noteCounts = noteCounts == null ? Map.of() : Map.copyOf(noteCounts);
        }
    }

    /** Dernier événement de CHAQUE session et nombre de messages, en une seule lecture. */
    public StatusSummary summary(LocalDate from, LocalDate to) {
        Map<String, DirectoryModels.DossierStatusEvent> latest = new LinkedHashMap<>();
        Map<String, Integer> noteCounts = new LinkedHashMap<>();
        for (DirectoryModels.DossierStatusEvent event : read(from, to)) {
            DirectoryModels.DossierStatusEvent current = latest.get(event.sessionId());
            if (current == null || compare(event, current) >= 0) {
                latest.put(event.sessionId(), event);
            }
            if (event.comment() != null && !event.comment().isBlank()) {
                noteCounts.merge(event.sessionId(), 1, Integer::sum);
            }
        }
        return new StatusSummary(latest, noteCounts);
    }

    /** Dernier événement de CHAQUE session (une seule lecture pour toute la liste). */
    public Map<String, DirectoryModels.DossierStatusEvent> latestAll(LocalDate from, LocalDate to) {
        return summary(from, to).latest();
    }

    /** Historique complet d'une session, du plus ancien au plus récent. */
    public List<DirectoryModels.DossierStatusEvent> history(String sessionId) {
        List<DirectoryModels.DossierStatusEvent> events = new ArrayList<>();
        for (DirectoryModels.DossierStatusEvent event : read(null, null)) {
            if (sessionId != null && sessionId.equals(event.sessionId())) {
                events.add(event);
            }
        }
        events.sort(Comparator.comparing(DirectoryModels.DossierStatusEvent::timestamp,
                Comparator.nullsFirst(String::compareTo)));
        return List.copyOf(events);
    }

    /** Événements d'une période (bornes incluses) ; {@code null} = tout l'historique disponible. */
    public List<DirectoryModels.DossierStatusEvent> read(LocalDate from, LocalDate to) {
        JsonlFiles.ReadResult<DirectoryModels.DossierStatusEvent> result = JsonlFiles.read(dir, PREFIX, from, to,
                DirectoryModels.DossierStatusEvent.class, objectMapper);
        List<DirectoryModels.DossierStatusEvent> values = new ArrayList<>();
        for (DirectoryModels.DossierStatusEvent event : result.values()) {
            if (event != null && event.sessionId() != null) {
                values.add(event);
            }
        }
        return List.copyOf(values);
    }

    private static int compare(DirectoryModels.DossierStatusEvent left, DirectoryModels.DossierStatusEvent right) {
        return value(left.timestamp()).compareTo(value(right.timestamp()));
    }

    private static String value(String raw) {
        return raw == null ? "" : raw;
    }

    private static LocalDate dayOf(String timestamp) {
        if (timestamp != null && timestamp.length() >= 10) {
            try {
                return LocalDate.parse(timestamp.substring(0, 10));
            } catch (Exception ignored) {
                // horodatage inattendu : jour courant
            }
        }
        return LocalDate.now();
    }
}
